package com.phonemirror.receiver.sync

import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Adaptive jitter detector for receiver video rendering.
 *
 * Implements:
 * - 1s sliding window of video PTS-arrival deltas vs expected cadence (from Hello fps).
 * - Jitter = standard deviation of deltas.
 * - Jitter > 15ms -> target delay 40ms (extreme jitter > 30ms -> up to 80ms hard cap).
 * - Jitter <= 15ms sustained 3s -> 0ms.
 * - Hard cap 80ms.
 * - Applies ONLY in AUDIO_MASTER mode (in VIDEO_IMMEDIATE mode delay is forced to 0).
 */
class AdaptiveJitterDetector(
    var expectedFps: Int = 60,
    val clock: () -> Long = { System.nanoTime() },
    val windowDurationNs: Long = 1_000_000_000L, // 1s
    val cleanSustainedNs: Long = 3_000_000_000L, // 3s
    val jitterThresholdMs: Double = 15.0,
    val targetDelayMs: Long = 40L,
    val hardCapMs: Long = 80L
) {
    data class ArrivalSample(val arrivalNs: Long, val deltaMs: Double)

    var expectedCadenceMs: Double = 1000.0 / expectedFps
        private set

    private val samples = ArrayDeque<ArrivalSample>()
    private var lastArrivalNs: Long? = null
    private var cleanStartNs: Long? = null

    var currentDelayMs: Long = 0L
        private set
    val currentDelayUs: Long get() = currentDelayMs * 1000L
    val currentDelayNs: Long get() = currentDelayMs * 1_000_000L

    var lastJitterMs: Double = 0.0
        private set

    fun setFps(fps: Int) {
        expectedFps = fps
        expectedCadenceMs = 1000.0 / fps
    }

    fun onFrameArrival(arrivalNs: Long = clock()) {
        val last = lastArrivalNs
        lastArrivalNs = arrivalNs
        if (last == null) return

        val interArrivalNs = arrivalNs - last
        val deltaMs = (interArrivalNs / 1_000_000.0) - expectedCadenceMs
        samples.addLast(ArrivalSample(arrivalNs, deltaMs))

        // Prune samples older than 1s
        val cutoff = arrivalNs - windowDurationNs
        while (samples.isNotEmpty() && samples.first().arrivalNs < cutoff) {
            samples.removeFirst()
        }

        // Compute stddev
        if (samples.size >= 2) {
            val n = samples.size
            var sum = 0.0
            for (s in samples) {
                sum += s.deltaMs
            }
            val mean = sum / n
            var sumSq = 0.0
            for (s in samples) {
                val diff = s.deltaMs - mean
                sumSq += diff * diff
            }
            val stddev = sqrt(sumSq / n)
            lastJitterMs = stddev
        } else {
            lastJitterMs = 0.0
        }

        // Jitter decision
        if (lastJitterMs > jitterThresholdMs) {
            cleanStartNs = null
            val calculated = if (lastJitterMs > 30.0) {
                hardCapMs
            } else {
                targetDelayMs
            }
            currentDelayMs = calculated
        } else {
            if (currentDelayMs > 0L) {
                if (cleanStartNs == null) {
                    cleanStartNs = arrivalNs
                } else if (arrivalNs - cleanStartNs!! >= cleanSustainedNs) {
                    currentDelayMs = 0L
                    cleanStartNs = null
                }
            } else {
                cleanStartNs = null
            }
        }
    }

    fun reset() {
        samples.clear()
        lastArrivalNs = null
        cleanStartNs = null
        currentDelayMs = 0L
        lastJitterMs = 0.0
    }
}
