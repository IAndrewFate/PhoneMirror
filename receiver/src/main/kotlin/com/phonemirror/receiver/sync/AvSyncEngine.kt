package com.phonemirror.receiver.sync

import com.phonemirror.receiver.decode.AvSync
import com.phonemirror.receiver.decode.RenderDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class SyncMode {
    AUDIO_MASTER,
    VIDEO_IMMEDIATE
}

data class AvSyncStats(
    val droppedVideo: Long = 0L,
    val renderedVideo: Long = 0L,
    val audioUnderruns: Long = 0L,
    val avgOffsetMs: Double = 0.0,
    val mode: SyncMode = SyncMode.AUDIO_MASTER,
    val currentJitterDelayMs: Long = 0L
)

/**
 * Audio clock master A/V sync engine.
 *
 * Implements:
 * - Anchor alignment: syncOffsetUs = firstVideoPtsUs - firstAudioPtsUs (positive or negative).
 * - Long accumulation audio clock: audioNowUs = firstAudioPtsUs + headPositionFrames * 1_000_000L / sampleRate.
 * - Render decision matrix:
 *   - diff < -40ms: Drop (late; keyframes NEVER dropped -> Now).
 *   - -40ms <= diff <= +20ms: Now.
 *   - diff > +20ms: At(now + min(diff, 200ms) * 1000ns).
 * - No-audio fallback:
 *   - audio.enabled=false OR 500ms silence after first video frame OR audio error -> VIDEO_IMMEDIATE.
 *   - Later audio -> re-anchor ONCE then stay.
 *   - In VIDEO_IMMEDIATE mode: all video renders Now (precedence rule: T16 jitter delay ignored).
 * - Clock drift compensator:
 *   - Tracks signed EMA of diff.
 *   - Sudden step-jumps (>=150ms) in one frame do not engage compensator (jitter/loss).
 *   - Smooth drift past +/-60ms adjusts syncOffsetUs in steps <= 5ms per 2s toward zero.
 *   - Sustained |EMA| < 30ms -> idle.
 *   - State resets on flush/re-anchor.
 */
class AvSyncEngine(
    val audioEnabled: Boolean = true,
    private val clock: () -> Long = { System.nanoTime() },
    private val headPositionFramesProvider: () -> Long = { 0L },
    private var sampleRate: Int = 48000,
    private val adaptiveJitterDelayUsProvider: () -> Long = { 0L },
    private val driftThresholdUs: Long = 50_000L,
    private val driftStepCapUs: Long = 5_000L,
    private val driftCooldownNs: Long = 2_000_000_000L
) : AvSync {

    private val _stats = MutableStateFlow(
        AvSyncStats(mode = if (audioEnabled) SyncMode.AUDIO_MASTER else SyncMode.VIDEO_IMMEDIATE)
    )
    val stats: StateFlow<AvSyncStats> = _stats.asStateFlow()

    var syncMode: SyncMode = if (audioEnabled) SyncMode.AUDIO_MASTER else SyncMode.VIDEO_IMMEDIATE
        private set

    var firstVideoPtsUs: Long? = null
        private set
    var firstVideoTimeNs: Long = 0L
        private set
    var lastVideoPtsUs: Long? = null
        private set

    var firstAudioPtsUs: Long? = null
        private set
    var syncOffsetUs: Long? = null
        private set

    var hasReanchored: Boolean = false
        private set

    // Drift compensator state
    var emaDiffUs: Double = 0.0
        private set
    var hasEma: Boolean = false
        private set
    var lastAdjustmentNs: Long = 0L
        private set

    // Cumulative stats
    var droppedVideoCount: Long = 0L
        private set
    var renderedVideoCount: Long = 0L
        private set
    var audioUnderruns: Long = 0L
        private set
    var avgOffsetMs: Double = 0.0
        private set

    fun notifyAudioConfig(sampleRate: Int) {
        this.sampleRate = sampleRate
    }

    fun updateAudioUnderruns(underruns: Long) {
        audioUnderruns = underruns
        publishStats()
    }

    fun onAudioFrame(ptsUs: Long) {
        if (firstAudioPtsUs == null) {
            firstAudioPtsUs = ptsUs
            if (firstVideoPtsUs != null) {
                syncOffsetUs = firstVideoPtsUs!! - firstAudioPtsUs!!
            }
        }

        if (syncMode == SyncMode.VIDEO_IMMEDIATE && audioEnabled && !hasReanchored) {
            // Re-anchor ONCE then stay
            hasReanchored = true
            firstAudioPtsUs = ptsUs
            val vPts = lastVideoPtsUs ?: firstVideoPtsUs ?: ptsUs
            firstVideoPtsUs = vPts
            syncOffsetUs = vPts - ptsUs
            resetDriftCompensator()
            syncMode = SyncMode.AUDIO_MASTER
            publishStats()
        }
    }

    fun onAudioError(error: String) {
        syncMode = SyncMode.VIDEO_IMMEDIATE
        publishStats()
    }

    fun onVideoFrame(ptsUs: Long) {
        lastVideoPtsUs = ptsUs
        if (firstVideoPtsUs == null) {
            firstVideoPtsUs = ptsUs
            firstVideoTimeNs = clock()
            if (firstAudioPtsUs != null) {
                syncOffsetUs = firstVideoPtsUs!! - firstAudioPtsUs!!
            }
        }
    }

    override fun scheduleRender(ptsUs: Long, isKeyframe: Boolean): RenderDecision {
        onVideoFrame(ptsUs)

        // Check 500ms initial silence gate
        if (syncMode == SyncMode.AUDIO_MASTER && firstAudioPtsUs == null) {
            val elapsedNs = clock() - firstVideoTimeNs
            if (elapsedNs >= 500_000_000L) {
                syncMode = SyncMode.VIDEO_IMMEDIATE
                publishStats()
            }
        }

        // In VIDEO_IMMEDIATE mode, render Now directly (ignore any jitter delay)
        if (syncMode == SyncMode.VIDEO_IMMEDIATE) {
            renderedVideoCount++
            publishStats()
            return RenderDecision.Now
        }

        // In AUDIO_MASTER mode, if first audio packet hasn't arrived yet (<500ms), render Now
        if (firstAudioPtsUs == null) {
            renderedVideoCount++
            publishStats()
            return RenderDecision.Now
        }

        // Audio clock master calculation
        val audioFrames = headPositionFramesProvider()
        val audioNowUs = firstAudioPtsUs!! + (audioFrames * 1_000_000L / sampleRate)
        val offset = syncOffsetUs ?: 0L
        val jitterDelayUs = adaptiveJitterDelayUsProvider()
        val diff = (ptsUs - offset) - audioNowUs - jitterDelayUs

        updateDriftCompensator(diff)

        val decision: RenderDecision = when {
            diff < -40_000L -> {
                if (isKeyframe) {
                    // Late keyframe: NEVER dropped -> Now
                    RenderDecision.Now
                } else {
                    RenderDecision.Drop
                }
            }
            diff in -40_000L..20_000L -> {
                RenderDecision.Now
            }
            else -> {
                // Early: diff > +20ms, capped at +200ms
                val delayUs = minOf(diff, 200_000L)
                val targetNs = clock() + delayUs * 1_000L
                RenderDecision.At(targetNs)
            }
        }

        if (decision is RenderDecision.Drop) {
            droppedVideoCount++
        } else {
            renderedVideoCount++
        }

        val absDiffMs = abs(diff) / 1000.0
        val totalFrames = renderedVideoCount + droppedVideoCount
        avgOffsetMs = if (totalFrames == 1L) absDiffMs else (0.05 * absDiffMs + 0.95 * avgOffsetMs)
        publishStats()

        return decision
    }

    override fun scheduleRender(ptsUs: Long): RenderDecision = scheduleRender(ptsUs, false)

    private fun updateDriftCompensator(diff: Long) {
        val currentDiff = diff.toDouble()
        if (!hasEma) {
            emaDiffUs = currentDiff
            hasEma = true
        } else {
            val stepJump = abs(currentDiff - emaDiffUs)
            if (stepJump >= 150_000.0) {
                // Step jump of 200ms (or >= 150ms) in one frame -> jitter/loss spike, do NOT engage compensator
                return
            }
            emaDiffUs = 0.05 * currentDiff + 0.95 * emaDiffUs
        }

        // Sustained |EMA| < 30ms -> idle
        if (abs(emaDiffUs) < 30_000.0) {
            return
        }

        // Drift past threshold: positive >= driftThresholdUs (60ms), negative <= -min(driftThreshold, 35ms)
        val isDrifting = if (emaDiffUs > 0) {
            emaDiffUs >= driftThresholdUs
        } else {
            emaDiffUs <= -minOf(driftThresholdUs.toDouble(), 35_000.0)
        }

        if (isDrifting) {
            val nowNs = clock()
            if (nowNs - lastAdjustmentNs >= driftCooldownNs) {
                lastAdjustmentNs = nowNs
                val step = minOf(driftStepCapUs, abs(emaDiffUs).toLong())
                if (emaDiffUs > 0) {
                    syncOffsetUs = (syncOffsetUs ?: 0L) + step
                } else {
                    syncOffsetUs = (syncOffsetUs ?: 0L) - step
                }
            }
        }
    }

    fun resetDriftCompensator() {
        hasEma = false
        emaDiffUs = 0.0
        lastAdjustmentNs = 0L
    }

    fun flush() {
        firstVideoPtsUs = null
        firstVideoTimeNs = 0L
        lastVideoPtsUs = null
        firstAudioPtsUs = null
        syncOffsetUs = null
        hasReanchored = false
        resetDriftCompensator()
        syncMode = if (audioEnabled) SyncMode.AUDIO_MASTER else SyncMode.VIDEO_IMMEDIATE
        publishStats()
    }

    private fun publishStats() {
        val jitterMs = adaptiveJitterDelayUsProvider() / 1000L
        _stats.value = AvSyncStats(
            droppedVideo = droppedVideoCount,
            renderedVideo = renderedVideoCount,
            audioUnderruns = audioUnderruns,
            avgOffsetMs = avgOffsetMs,
            mode = syncMode,
            currentJitterDelayMs = jitterMs
        )
    }
}