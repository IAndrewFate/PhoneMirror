package com.phonemirror.receiver.sync

import com.google.common.truth.Truth.assertThat
import com.phonemirror.receiver.decode.RenderDecision
import org.junit.Before
import org.junit.Test

class AvSyncEngineTest {

    private var fakeClockNs: Long = 1_000_000_000L
    private var fakeHeadPositionFrames: Long = 0L
    private var fakeJitterDelayUs: Long = 0L

    private lateinit var engine: AvSyncEngine

    @Before
    fun setUp() {
        fakeClockNs = 1_000_000_000L
        fakeHeadPositionFrames = 0L
        fakeJitterDelayUs = 0L
        engine = AvSyncEngine(
            audioEnabled = true,
            clock = { fakeClockNs },
            headPositionFramesProvider = { fakeHeadPositionFrames },
            sampleRate = 48000,
            adaptiveJitterDelayUsProvider = { fakeJitterDelayUs }
        )
    }

    // 1. Full sync decision truth table
    @Test
    fun full_sync_decision_truth_table() {
        // Anchor both at 10_000_000L
        engine.scheduleRender(10_000_000L, isKeyframe = true)
        engine.onAudioFrame(10_000_000L)
        fakeHeadPositionFrames = 0L

        // Ontime: diff = 0 -> Now
        val ontimeNonKey = engine.scheduleRender(10_000_000L, isKeyframe = false)
        assertThat(ontimeNonKey).isEqualTo(RenderDecision.Now)

        val ontimeKey = engine.scheduleRender(10_000_000L, isKeyframe = true)
        assertThat(ontimeKey).isEqualTo(RenderDecision.Now)

        // Early: diff = +30ms (> 20ms) -> At
        val earlyNonKey = engine.scheduleRender(10_030_000L, isKeyframe = false)
        assertThat(earlyNonKey).isInstanceOf(RenderDecision.At::class.java)

        val earlyKey = engine.scheduleRender(10_030_000L, isKeyframe = true)
        assertThat(earlyKey).isInstanceOf(RenderDecision.At::class.java)

        // Late: diff = -50ms (< -40ms)
        // Keyframe -> Now
        val lateKey = engine.scheduleRender(9_950_000L, isKeyframe = true)
        assertThat(lateKey).isEqualTo(RenderDecision.Now)

        // Non-keyframe -> Drop
        val lateNonKey = engine.scheduleRender(9_950_000L, isKeyframe = false)
        assertThat(lateNonKey).isEqualTo(RenderDecision.Drop)

        // Audio-absent mode (VIDEO_IMMEDIATE)
        val videoOnlyEngine = AvSyncEngine(
            audioEnabled = false,
            clock = { fakeClockNs }
        )
        // Late, ontime, early all return Now
        assertThat(videoOnlyEngine.scheduleRender(1_000L, false)).isEqualTo(RenderDecision.Now)
        assertThat(videoOnlyEngine.scheduleRender(10_000_000L, false)).isEqualTo(RenderDecision.Now)
        assertThat(videoOnlyEngine.scheduleRender(20_000_000L, false)).isEqualTo(RenderDecision.Now)
    }

    // 2. Anchor math with negative syncOffsetUs
    @Test
    fun anchor_math_negative_sync_offset() {
        // Video arrives first at 5_000_000L, Audio arrives second at 5_020_000L
        engine.scheduleRender(5_000_000L, isKeyframe = true)
        engine.onAudioFrame(5_020_000L)

        // syncOffsetUs = 5_000_000 - 5_020_000 = -20_000
        assertThat(engine.syncOffsetUs).isEqualTo(-20_000L)

        // When head = 0 (audioNowUs = 5_020_000):
        // video PTS = 5_000_000 -> pts - (-20_000) = 5_020_000 -> diff = 0 -> Now
        val decision = engine.scheduleRender(5_000_000L, false)
        assertThat(decision).isEqualTo(RenderDecision.Now)
    }

    // 3. HeadPosition rollover guard
    @Test
    fun head_position_rollover_guard_long_accumulation() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // Advance fake head position past 32-bit Int.MAX_VALUE (e.g. 3,000,000,000 frames)
        fakeHeadPositionFrames = 3_000_000_000L
        // 3_000_000_000 frames at 48kHz = 62,500s = 62,500,000,000 us
        val targetPtsUs = 10_000_000L + 62_500_000_000L

        val decision = engine.scheduleRender(targetPtsUs, false)
        assertThat(decision).isEqualTo(RenderDecision.Now)
    }

    // 4. 500ms silence triggers mode switch to VIDEO_IMMEDIATE
    @Test
    fun silence_500ms_triggers_mode_switch_to_video_immediate() {
        engine.scheduleRender(10_000_000L, true)
        assertThat(engine.syncMode).isEqualTo(SyncMode.AUDIO_MASTER)

        // Advance 400ms (< 500ms)
        fakeClockNs += 400_000_000L
        val d1 = engine.scheduleRender(10_016_666L, false)
        assertThat(d1).isEqualTo(RenderDecision.Now)
        assertThat(engine.syncMode).isEqualTo(SyncMode.AUDIO_MASTER)

        // Advance another 101ms (total > 500ms)
        fakeClockNs += 101_000_000L
        val d2 = engine.scheduleRender(10_033_333L, false)
        assertThat(d2).isEqualTo(RenderDecision.Now)
        assertThat(engine.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)
        assertThat(engine.stats.value.mode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)
    }

    // 5. Re-anchor-once rule
    @Test
    fun re_anchor_once_rule() {
        // Trigger VIDEO_IMMEDIATE via 500ms silence
        engine.scheduleRender(10_000_000L, true)
        fakeClockNs += 501_000_000L
        engine.scheduleRender(10_016_666L, false)
        assertThat(engine.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)

        // Audio appears later!
        engine.onAudioFrame(12_000_000L)
        assertThat(engine.syncMode).isEqualTo(SyncMode.AUDIO_MASTER)
        assertThat(engine.hasReanchored).isTrue()

        // If audio drops again for 500ms:
        fakeClockNs += 600_000_000L
        engine.onAudioError("Temporary glitch")
        assertThat(engine.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)

        // Subsequent audio does NOT re-anchor again
        engine.onAudioFrame(20_000_000L)
        assertThat(engine.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)
    }

    // 6. Flush clears anchors and resets compensator keeping cumulative stats
    @Test
    fun flush_clears_anchors_and_resets_drift_compensator_keeping_cumulative_stats() {
        engine.scheduleRender(10_000_000L, true) // rendered 1
        engine.onAudioFrame(10_000_000L)
        engine.scheduleRender(9_900_000L, false) // diff = -100ms -> dropped 1

        assertThat(engine.renderedVideoCount).isEqualTo(1)
        assertThat(engine.droppedVideoCount).isEqualTo(1)

        engine.flush()

        assertThat(engine.firstVideoPtsUs).isNull()
        assertThat(engine.firstAudioPtsUs).isNull()
        assertThat(engine.syncOffsetUs).isNull()
        assertThat(engine.hasEma).isFalse()

        // Cumulative stats preserved
        assertThat(engine.stats.value.renderedVideo).isEqualTo(1)
        assertThat(engine.stats.value.droppedVideo).isEqualTo(1)
    }

    // 7. EMA math computes correct smoothed values
    @Test
    fun ema_math_computes_correct_smoothed_values() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // 1st diff = 0
        engine.scheduleRender(10_000_000L, false)
        assertThat(engine.emaDiffUs).isEqualTo(0.0)

        // 2nd diff = 10_000 (10ms)
        // new EMA = 0.05 * 10_000 + 0.95 * 0 = 500.0
        engine.scheduleRender(10_010_000L, false)
        assertThat(engine.emaDiffUs).isEqualTo(500.0)
    }

    // 8. At-cap clamps maximum delay to 200ms
    @Test
    fun at_cap_clamps_maximum_delay_to_200ms() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // diff = +500ms (500_000 us)
        val decision = engine.scheduleRender(10_500_000L, false)
        assertThat(decision).isInstanceOf(RenderDecision.At::class.java)
        val atDecision = decision as RenderDecision.At

        // Clamped at exactly fakeClockNs + 200ms (200_000_000 ns)
        val expectedNs = fakeClockNs + 200_000_000L
        assertThat(atDecision.ns).isEqualTo(expectedNs)
    }

    // 9. Late keyframe never drops
    @Test
    fun late_keyframe_never_drops() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // 100ms late
        val keyframeDecision = engine.scheduleRender(9_900_000L, isKeyframe = true)
        assertThat(keyframeDecision).isEqualTo(RenderDecision.Now)

        val nonKeyframeDecision = engine.scheduleRender(9_900_000L, isKeyframe = false)
        assertThat(nonKeyframeDecision).isEqualTo(RenderDecision.Drop)
    }

    // 10. Aligned synthetic streams zero diff convergence
    @Test
    fun aligned_synthetic_streams_zero_diff_convergence() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // Simulate 60 frames (1 second) of perfectly synchronized 60fps video and 48kHz audio
        for (i in 1..60) {
            fakeClockNs += 16_666_666L
            fakeHeadPositionFrames = (i * 48000L) / 60
            val ptsUs = 10_000_000L + (i * 1_000_000L) / 60

            val decision = engine.scheduleRender(ptsUs, false)
            assertThat(decision).isEqualTo(RenderDecision.Now)
        }

        assertThat(engine.droppedVideoCount).isEqualTo(0)
        assertThat(engine.renderedVideoCount).isEqualTo(61)
    }

    // 11. Audio error mid-stream transitions to VIDEO_IMMEDIATE
    @Test
    fun audio_error_mid_stream_transitions_to_video_immediate() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)
        assertThat(engine.syncMode).isEqualTo(SyncMode.AUDIO_MASTER)

        engine.onAudioError("MediaCodec hardware error")
        assertThat(engine.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)

        // Future late frames render Now instead of dropping
        val decision = engine.scheduleRender(9_000_000L, false)
        assertThat(decision).isEqualTo(RenderDecision.Now)
    }

    // 12. Synthetic drift over fake 10 minutes converges and avoids drops
    @Test
    fun synthetic_drift_over_fake_10_minutes_converges_and_avoids_drops() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // Simulate 600 seconds (10 minutes) with +0.1 ms/s clock drift
        // Video PTS drifts by 0.1ms per second (100 us/s)
        var totalDriftUs = 0L
        for (sec in 1..600) {
            fakeClockNs += 1_000_000_000L
            fakeHeadPositionFrames += 48000L
            totalDriftUs += 100L // 0.1 ms drift per second

            val ptsUs = 10_000_000L + (sec * 1_000_000L) + totalDriftUs
            engine.scheduleRender(ptsUs, false)
        }

        // With drift compensator active, syncOffsetUs stepped up to absorb drift
        assertThat(engine.syncOffsetUs).isNotNull()
        assertThat(engine.syncOffsetUs!!).isGreaterThan(0L)
        assertThat(engine.droppedVideoCount).isEqualTo(0)
    }

    // 13. Step-jump of 200ms in one frame does NOT engage compensator
    @Test
    fun step_jump_200ms_in_one_frame_does_not_engage_compensator() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // 10 on-time frames to establish 0 EMA
        for (i in 1..10) {
            engine.scheduleRender(10_000_000L + i * 16_000L, false)
            fakeHeadPositionFrames += 768L
        }
        val initialOffset = engine.syncOffsetUs

        // Sudden 200ms jump in one frame (e.g. packet loss / network stall spike)
        engine.scheduleRender(10_000_000L + 11 * 16_000L + 200_000L, false)

        // Compensator must NOT have engaged or modified syncOffsetUs
        assertThat(engine.syncOffsetUs).isEqualTo(initialOffset)
    }

    // 14. Compensator step cap <= 5ms per 2s
    @Test
    fun compensator_step_cap_le_5ms_per_2s() {
        engine.scheduleRender(10_000_000L, true)
        engine.onAudioFrame(10_000_000L)

        // Feed frames with +80ms diff to build EMA > 60ms smoothly
        for (i in 1..50) {
            engine.scheduleRender(10_000_000L + 80_000L, false)
        }
        assertThat(engine.emaDiffUs).isGreaterThan(60_000.0)

        val offsetBefore = engine.syncOffsetUs ?: 0L

        // Trigger adjustment after 2.1s
        fakeClockNs += 2_100_000_000L
        engine.scheduleRender(10_000_000L + 80_000L, false)

        val offsetAfter = engine.syncOffsetUs ?: 0L
        val step = offsetAfter - offsetBefore

        // Step must be <= 5_000 us (5ms)
        assertThat(step).isAtMost(5_000L)
        assertThat(step).isGreaterThan(0L)

        // Next adjustment immediately (<2s) must NOT happen
        fakeClockNs += 500_000_000L // 0.5s
        engine.scheduleRender(10_000_000L + 80_000L, false)
        assertThat(engine.syncOffsetUs).isEqualTo(offsetAfter)
    }
}
