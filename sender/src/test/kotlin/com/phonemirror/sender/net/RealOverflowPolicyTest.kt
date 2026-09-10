package com.phonemirror.sender.net

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.protocol.wire.VideoFrameBody
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class RealOverflowPolicyTest {

    @Test
    fun testOverflowDropsOldestAudioFirst() {
        val policy = RealOverflowPolicy(configuredBitrateBps = 8_000_000)
        val capacity = 300_000L // ~300ms at 8 Mbps
        val queue = ArrayDeque<Frame>()
        val currentBytes = AtomicLong(0)

        // Add 3 audio frames (50,000 bytes each) and 1 video frame (100,000 bytes)
        // Total = 250,000 bytes
        val a1 = Frame(FrameKind.AUDIO_FRAME, ByteArray(50_000) { 1 })
        val a2 = Frame(FrameKind.AUDIO_FRAME, ByteArray(50_000) { 2 })
        val a3 = Frame(FrameKind.AUDIO_FRAME, ByteArray(50_000) { 3 })
        val v1 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(100L, false, ByteArray(100_000)).encode())

        assertThat(policy.handleEnqueue(a1, queue, currentBytes, capacity)).isTrue()
        assertThat(policy.handleEnqueue(a2, queue, currentBytes, capacity)).isTrue()
        assertThat(policy.handleEnqueue(a3, queue, currentBytes, capacity)).isTrue()
        assertThat(policy.handleEnqueue(v1, queue, currentBytes, capacity)).isTrue()
        val expectedInitialBytes = a1.body.size.toLong() + a2.body.size.toLong() + a3.body.size.toLong() + v1.body.size.toLong()
        assertThat(currentBytes.get()).isEqualTo(expectedInitialBytes)

        // Now enqueue another video frame of 80,000 bytes payload
        // 250k + ~80k > 300k -> must drop oldest audio (a1)
        val v2 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(200L, false, ByteArray(80_000)).encode())
        val accepted = policy.handleEnqueue(v2, queue, currentBytes, capacity)

        assertThat(accepted).isTrue()
        assertThat(policy.droppedAudioCount).isAtLeast(1L)
        // Verify oldest audio (a1) was dropped and is not in queue
        assertThat(queue).doesNotContain(a1)
        // Verify v1 and v2 are intact
        assertThat(queue).contains(v1)
        assertThat(queue).contains(v2)
    }

    @Test
    fun testConfigAndControlNeverDropped() {
        val policy = RealOverflowPolicy(configuredBitrateBps = 8_000_000)
        val capacity = 100_000L
        val queue = ArrayDeque<Frame>()
        val currentBytes = AtomicLong(100_000L) // already full!

        val ctrl = Frame(FrameKind.CONTROL, ByteArray(500))
        val vConfig = Frame(FrameKind.VIDEO_CONFIG, ByteArray(200))
        val aConfig = Frame(FrameKind.AUDIO_CONFIG, ByteArray(150))

        assertThat(policy.handleEnqueue(ctrl, queue, currentBytes, capacity)).isTrue()
        assertThat(policy.handleEnqueue(vConfig, queue, currentBytes, capacity)).isTrue()
        assertThat(policy.handleEnqueue(aConfig, queue, currentBytes, capacity)).isTrue()

        assertThat(policy.droppedVideoCount).isEqualTo(0L)
        assertThat(policy.droppedAudioCount).isEqualTo(0L)
        assertThat(queue).contains(ctrl)
        assertThat(queue).contains(vConfig)
        assertThat(queue).contains(aConfig)
        assertThat(policy.cachedVideoConfig).isEqualTo(vConfig.body)
    }

    @Test
    fun testGopAlignedVideoShedding() {
        var keyframeRequested = false
        val policy = RealOverflowPolicy(
            configuredBitrateBps = 8_000_000,
            onRequestKeyframe = { keyframeRequested = true }
        )
        val capacity = 250_000L
        val queue = ArrayDeque<Frame>()
        val currentBytes = AtomicLong(0)

        // Cache video config
        val vConfig = Frame(FrameKind.VIDEO_CONFIG, byteArrayOf(1, 2, 3, 4))
        policy.handleEnqueue(vConfig, queue, currentBytes, capacity)

        // Fill queue with video frames only (no audio to drop)
        val v1 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(100L, false, ByteArray(120_000)).encode())
        val v2 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(150L, false, ByteArray(120_000)).encode())
        policy.handleEnqueue(v1, queue, currentBytes, capacity)
        policy.handleEnqueue(v2, queue, currentBytes, capacity)

        // Now enqueue another video frame -> overflow!
        val v3 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(200L, false, ByteArray(50_000)).encode())
        val acceptedV3 = policy.handleEnqueue(v3, queue, currentBytes, capacity)

        assertThat(acceptedV3).isFalse()
        assertThat(keyframeRequested).isTrue()
        assertThat(policy.isVideoShedding).isTrue()
        // Queued video frames shed!
        assertThat(queue).doesNotContain(v1)
        assertThat(queue).doesNotContain(v2)
        // VIDEO_CONFIG preserved!
        assertThat(queue).contains(vConfig)
        assertThat(policy.droppedVideoCount).isAtLeast(3L) // v1, v2, v3

        // Subsequent non-keyframe is dropped
        val vDelta = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(250L, false, ByteArray(10_000)).encode())
        assertThat(policy.handleEnqueue(vDelta, queue, currentBytes, capacity)).isFalse()

        // Keyframe arrives!
        val vKey = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(300L, true, ByteArray(10_000)).encode())
        val acceptedKey = policy.handleEnqueue(vKey, queue, currentBytes, capacity)

        assertThat(acceptedKey).isTrue()
        assertThat(policy.isVideoShedding).isFalse()
        // Verify keyframe accepted and in queue
        assertThat(queue).contains(vKey)
    }

    @Test
    fun testSustainedOverflowStepDownToFloor2M() {
        var fakeTimeNs = 1_000_000_000L
        var reportedBitrate = 0
        var stepDownCount = 0

        val policy = RealOverflowPolicy(
            originalBitrateBps = 8_000_000,
            configuredBitrateBps = 8_000_000,
            clock = { fakeTimeNs },
            onBitrateChanged = { reportedBitrate = it },
            onAbrStepDown = { stepDownCount++ }
        )

        val capacity = 300_000L
        val current = 350_000L // overflow

        // Initial check: sets overflow start time
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.abrStepDownCount).isEqualTo(0)

        // 2 seconds later -> no step down yet (< 3s)
        fakeTimeNs += 2_000_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.abrStepDownCount).isEqualTo(0)

        // 3.5s later -> step down: 8 * 0.8 = 6.4 -> 6 Mbps
        fakeTimeNs += 1_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.abrStepDownCount).isEqualTo(1)
        assertThat(policy.configuredBitrateBps).isEqualTo(6_000_000)
        assertThat(reportedBitrate).isEqualTo(6_000_000)

        // Another 3.5s -> 6 * 0.8 = 4.8 -> 4 Mbps
        fakeTimeNs += 3_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.abrStepDownCount).isEqualTo(2)
        assertThat(policy.configuredBitrateBps).isEqualTo(4_000_000)

        // Another 3.5s -> 4 * 0.8 = 3.2 -> 3 Mbps
        fakeTimeNs += 3_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.abrStepDownCount).isEqualTo(3)
        assertThat(policy.configuredBitrateBps).isEqualTo(3_000_000)

        // Another 3.5s -> 3 * 0.8 = 2.4 -> 2 Mbps
        fakeTimeNs += 3_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.abrStepDownCount).isEqualTo(4)
        assertThat(policy.configuredBitrateBps).isEqualTo(2_000_000)

        // Another 3.5s -> stays at floor 2 Mbps
        fakeTimeNs += 3_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(2_000_000)
    }

    @Test
    fun testRecoveryStepUpCappedAtOriginalBitrate() {
        var fakeTimeNs = 1_000_000_000L
        var reportedBitrate = 0
        var recoveryCount = 0

        val policy = RealOverflowPolicy(
            originalBitrateBps = 8_000_000,
            configuredBitrateBps = 2_000_000, // stepped down to 2 Mbps
            clock = { fakeTimeNs },
            onBitrateChanged = { reportedBitrate = it },
            onAbrRecovery = { recoveryCount++ }
        )

        val capacity = 300_000L
        val current = 50_000L // low queue < 50%

        // 4 seconds sustained -> no step up yet (< 5s)
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        fakeTimeNs += 4_000_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(2_000_000)

        // 5.5s sustained -> step up: 2 * 1.25 = 2.5 -> 3 Mbps
        fakeTimeNs += 1_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(3_000_000)
        assertThat(recoveryCount).isEqualTo(1)

        // Another 5.5s -> 3 * 1.25 = 3.75 -> 4 Mbps
        fakeTimeNs += 5_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(4_000_000)

        // Another 5.5s -> 4 * 1.25 = 5 Mbps
        fakeTimeNs += 5_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(5_000_000)

        // Another 5.5s -> 5 * 1.25 = 6 Mbps
        fakeTimeNs += 5_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(6_000_000)

        // Another 5.5s -> 6 * 1.25 = 7.5 -> 8 Mbps
        fakeTimeNs += 5_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(8_000_000)

        // Another 5.5s -> stays at original user setting (8 Mbps), never above!
        fakeTimeNs += 5_500_000_000L
        policy.checkCapacityAndTimers(current, capacity, fakeTimeNs)
        assertThat(policy.configuredBitrateBps).isEqualTo(8_000_000)
    }

    @Test
    fun testCalculateCapacityBytesTimeDerived() {
        val policy = RealOverflowPolicy()
        // 1 Mbps: 1_000_000 * 0.3 / 8 = 37_500 -> clamped to 256 KiB
        assertThat(policy.calculateCapacityBytes(1_000_000)).isEqualTo(256 * 1024L)

        // 8 Mbps: 8_000_000 * 0.3 / 8 = 300_000 bytes
        assertThat(policy.calculateCapacityBytes(8_000_000)).isEqualTo(300_000L)

        // 50 Mbps: 50_000_000 * 0.3 / 8 = 1_875_000 -> clamped to 1 MiB
        assertThat(policy.calculateCapacityBytes(50_000_000)).isEqualTo(1024 * 1024L)
    }

    @Test
    fun testStalledConsumerTranscriptAudioDroppedBitrateSteppedDownAndRestored() {
        var fakeNs = 1_000_000_000L
        var reportedBitrate = 8_000_000
        var abrStepDowns = 0
        var abrRecoveries = 0

        val policy = RealOverflowPolicy(
            originalBitrateBps = 8_000_000,
            configuredBitrateBps = 8_000_000,
            clock = { fakeNs },
            onBitrateChanged = { reportedBitrate = it },
            onAbrStepDown = { abrStepDowns++ },
            onAbrRecovery = { abrRecoveries++ }
        )

        val capacity = policy.calculateCapacityBytes(8_000_000)
        val queue = ArrayDeque<Frame>()
        val currentBytes = AtomicLong(0)

        // Cache video config
        val vConfig = Frame(FrameKind.VIDEO_CONFIG, byteArrayOf(1, 2, 3))
        policy.handleEnqueue(vConfig, queue, currentBytes, capacity)

        // Initial keyframe + audio
        val kf = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(0L, true, ByteArray(50_000)).encode())
        val a1 = Frame(FrameKind.AUDIO_FRAME, ByteArray(20_000))
        val a2 = Frame(FrameKind.AUDIO_FRAME, ByteArray(20_000))
        policy.handleEnqueue(kf, queue, currentBytes, capacity)
        policy.handleEnqueue(a1, queue, currentBytes, capacity)
        policy.handleEnqueue(a2, queue, currentBytes, capacity)

        // Consumer stalls: add frames exceeding capacity
        val vDelta1 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(16666L, false, ByteArray(150_000)).encode())
        val vDelta2 = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(33333L, false, ByteArray(150_000)).encode())
        policy.handleEnqueue(vDelta1, queue, currentBytes, capacity)
        policy.handleEnqueue(vDelta2, queue, currentBytes, capacity)

        // Audio dropped first
        assertThat(policy.droppedAudioCount).isAtLeast(1L)
        // Config and keyframes intact
        assertThat(queue).contains(vConfig)

        // Continuous overflow > 3s
        fakeNs += 3_500_000_000L
        policy.checkCapacityAndTimers(currentBytes.get(), capacity, fakeNs)
        assertThat(abrStepDowns).isEqualTo(1)
        assertThat(reportedBitrate).isEqualTo(6_000_000)

        // Consumer resumes: queue is drained, encoder delivers keyframe
        queue.clear()
        currentBytes.set(0L)
        val resumeKf = Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(50_000L, true, ByteArray(10_000)).encode())
        policy.handleEnqueue(resumeKf, queue, currentBytes, capacity)
        assertThat(policy.isVideoShedding).isFalse()

        // Sustained clean 5s
        fakeNs += 5_500_000_000L
        policy.checkCapacityAndTimers(currentBytes.get(), capacity, fakeNs)
        assertThat(abrRecoveries).isEqualTo(1)
        assertThat(reportedBitrate).isEqualTo(8_000_000)
    }

    @Test
    fun testConsumerStallsForeverQueueBoundsInvariant() {
        val policy = RealOverflowPolicy(configuredBitrateBps = 16_000_000)
        // Max capacity clamped to 1 MiB
        val capacity = policy.calculateCapacityBytes(16_000_000)
        assertThat(capacity).isAtMost(1024 * 1024L)

        val queue = ArrayDeque<Frame>()
        val currentBytes = AtomicLong(0)

        // Spam 1000 frames into stalled queue
        for (i in 0 until 1000) {
            val frame = if (i % 2 == 0) {
                Frame(FrameKind.VIDEO_FRAME, VideoFrameBody(i * 16666L, false, ByteArray(20_000)).encode())
            } else {
                Frame(FrameKind.AUDIO_FRAME, ByteArray(5_000))
            }
            policy.handleEnqueue(frame, queue, currentBytes, capacity)
        }

        // Invariant: queue size never exceeds capacity + single max frame
        val maxFrameSize = 30_000L
        assertThat(currentBytes.get()).isAtMost(capacity + maxFrameSize)
    }
}
