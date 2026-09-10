package com.phonemirror.receiver.sync

import com.google.common.truth.Truth.assertThat
import com.phonemirror.receiver.decode.RenderDecision
import org.junit.Test

class AdaptiveJitterDetectorTest {

    @Test
    fun testCleanCadenceProducesZeroDelay() {
        var fakeNs = 1_000_000_000L
        val detector = AdaptiveJitterDetector(
            expectedFps = 60,
            clock = { fakeNs }
        )

        // Clean 16.666ms cadence (60fps)
        val stepNs = 16_666_667L
        for (i in 0 until 60) {
            detector.onFrameArrival(fakeNs)
            fakeNs += stepNs
        }

        assertThat(detector.lastJitterMs).isAtMost(15.0)
        assertThat(detector.currentDelayMs).isEqualTo(0L)
    }

    @Test
    fun testNoisyCadenceProduces40msDelay() {
        var fakeNs = 1_000_000_000L
        val detector = AdaptiveJitterDetector(
            expectedFps = 60,
            clock = { fakeNs }
        )

        // Alternating ±25ms noise around 16.67ms
        val baseNs = 16_666_667L
        val noiseNs = 25_000_000L
        for (i in 0 until 60) {
            detector.onFrameArrival(fakeNs)
            val offset = if (i % 2 == 0) noiseNs else -noiseNs
            fakeNs += (baseNs + offset).coerceAtLeast(1_000_000L)
        }

        assertThat(detector.lastJitterMs).isGreaterThan(15.0)
        assertThat(detector.currentDelayMs).isEqualTo(40L)
    }

    @Test
    fun testSustainedClean3sResetsDelayToZero() {
        var fakeNs = 1_000_000_000L
        val detector = AdaptiveJitterDetector(
            expectedFps = 60,
            clock = { fakeNs }
        )

        // 1. Inject noise to trigger 40ms delay
        val baseNs = 16_666_667L
        val noiseNs = 25_000_000L
        for (i in 0 until 40) {
            detector.onFrameArrival(fakeNs)
            val offset = if (i % 2 == 0) noiseNs else -noiseNs
            fakeNs += (baseNs + offset).coerceAtLeast(1_000_000L)
        }
        assertThat(detector.currentDelayMs).isEqualTo(40L)

        // 2. Clean cadence for 4.5 seconds (1s to flush sliding window + 3s sustained)
        val framesFor3s = (4.5 * 60).toInt()
        for (i in 0 until framesFor3s) {
            detector.onFrameArrival(fakeNs)
            fakeNs += baseNs
        }

        assertThat(detector.lastJitterMs).isAtMost(15.0)
        assertThat(detector.currentDelayMs).isEqualTo(0L)
    }

    @Test
    fun testHardCap80msOnExtremeJitter() {
        var fakeNs = 1_000_000_000L
        val detector = AdaptiveJitterDetector(
            expectedFps = 60,
            clock = { fakeNs }
        )

        // Extreme noise: ±60ms
        val baseNs = 16_666_667L
        val extremeNoiseNs = 60_000_000L
        for (i in 0 until 50) {
            detector.onFrameArrival(fakeNs)
            val offset = if (i % 2 == 0) extremeNoiseNs else -extremeNoiseNs
            fakeNs += (baseNs + offset).coerceAtLeast(1_000_000L)
        }

        assertThat(detector.lastJitterMs).isGreaterThan(30.0)
        assertThat(detector.currentDelayMs).isEqualTo(80L) // hard cap
    }

    @Test
    fun testPrecedenceRuleVideoImmediateForcesZeroDelay() {
        val engine = AvSyncEngine(
            audioEnabled = false,
            adaptiveJitterDelayUsProvider = { 80_000L } // 80ms reported by detector
        )

        assertThat(engine.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)
        assertThat(engine.stats.value.currentJitterDelayMs).isEqualTo(0L)

        // scheduleRender returns Now directly in VIDEO_IMMEDIATE mode
        val decision = engine.scheduleRender(ptsUs = 100_000L)
        assertThat(decision).isEqualTo(RenderDecision.Now)
        assertThat(engine.stats.value.currentJitterDelayMs).isEqualTo(0L)
    }
}
