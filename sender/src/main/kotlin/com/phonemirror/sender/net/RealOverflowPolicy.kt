package com.phonemirror.sender.net

import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.protocol.wire.VideoFrameBody
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Production backpressure & overflow policy for PhoneMirror sender.
 *
 * Implements:
 * - Time-derived byte capacity: clamp(bitrate * 0.3 / 8, 256 KiB, 1 MiB) (~300ms max backlog).
 * - Overflow drop order:
 *   1) Drop oldest AUDIO_FRAMEs (Opus/AAC frames are independently decodable).
 *   2) Still over capacity -> GOP-aligned video shed:
 *      - Triggers encoder PARAMETER_KEY_REQUEST_SYNC_FRAME.
 *      - Drops all queued and incoming video frames until the next IDR keyframe arrives.
 *      - Resends cached VIDEO_CONFIG + keyframe upon keyframe arrival.
 *      - NEVER drops VIDEO_CONFIG, AUDIO_CONFIG, or CONTROL frames.
 *   3) Continuous overflow > 3s -> bitrate step-down * 0.8 (floor 2 Mbps) via live hook + UI event.
 *   4) Recovery: queue < 50% capacity for 5s -> step-up * 1.25 up to original user setting (never above).
 * - Counters fed to SessionStats / MainViewModel.
 */
class RealOverflowPolicy(
    val originalBitrateBps: Int = 8_000_000,
    var configuredBitrateBps: Int = originalBitrateBps,
    val clock: () -> Long = { System.nanoTime() },
    var onRequestKeyframe: () -> Unit = {},
    var onBitrateChanged: (Int) -> Unit = {},
    var onAbrStepDown: () -> Unit = {},
    var onAbrRecovery: () -> Unit = {}
) : OverflowPolicy {

    var congestionSignalCount: Int = 0
        private set
    var droppedAudioCount: Long = 0L
        private set
    var droppedVideoCount: Long = 0L
        private set
    var abrStepDownCount: Int = 0
        private set
    var isVideoShedding: Boolean = false
        private set

    var cachedVideoConfig: ByteArray? = null

    private var overflowStartTimeNs: Long? = null
    private var recoveryStartTimeNs: Long? = null

    fun calculateCapacityBytes(bitrateBps: Int = configuredBitrateBps): Long {
        return (bitrateBps * 0.3 / 8.0).toLong().coerceIn(256 * 1024L, 1024 * 1024L)
    }

    override fun shouldAcceptFrame(frame: Frame, currentQueueBytes: Long, capacityBytes: Long): Boolean {
        if (frame.kind == FrameKind.CONTROL || frame.kind == FrameKind.VIDEO_CONFIG || frame.kind == FrameKind.AUDIO_CONFIG) {
            return true
        }
        if (isVideoShedding && frame.kind == FrameKind.VIDEO_FRAME) {
            val isKeyframe = try {
                VideoFrameBody.decode(frame.body).keyframe
            } catch (_: Exception) {
                false
            }
            if (!isKeyframe) return false
        }
        return currentQueueBytes + frame.body.size <= capacityBytes
    }

    override fun onCongestion() {
        congestionSignalCount++
    }

    override fun handleEnqueue(
        frame: Frame,
        queue: ArrayDeque<Frame>,
        currentQueueBytes: AtomicLong,
        capacityBytes: Long
    ): Boolean {
        val nowNs = clock()

        // 1. Config and Control frames are NEVER dropped
        if (frame.kind == FrameKind.CONTROL || frame.kind == FrameKind.VIDEO_CONFIG || frame.kind == FrameKind.AUDIO_CONFIG) {
            if (frame.kind == FrameKind.VIDEO_CONFIG) {
                cachedVideoConfig = frame.body
            }
            currentQueueBytes.addAndGet(frame.body.size.toLong())
            queue.addLast(frame)
            checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
            return true
        }

        // 2. Video shedding mode
        if (isVideoShedding) {
            if (frame.kind == FrameKind.VIDEO_FRAME) {
                val isKeyframe = try {
                    VideoFrameBody.decode(frame.body).keyframe
                } catch (_: Exception) {
                    false
                }
                if (!isKeyframe) {
                    droppedVideoCount++
                    onCongestion()
                    checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
                    return false
                } else {
                    // Keyframe arrived -> resume video stream
                    isVideoShedding = false
                    // Re-send cached VIDEO_CONFIG before keyframe if available
                    cachedVideoConfig?.let { configBytes ->
                        val configFrame = Frame(FrameKind.VIDEO_CONFIG, configBytes)
                        currentQueueBytes.addAndGet(configFrame.body.size.toLong())
                        queue.addLast(configFrame)
                    }
                    currentQueueBytes.addAndGet(frame.body.size.toLong())
                    queue.addLast(frame)
                    checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
                    return true
                }
            }
        }

        // 3. Normal enqueue check
        val frameSize = frame.body.size.toLong()
        if (currentQueueBytes.get() + frameSize <= capacityBytes) {
            currentQueueBytes.addAndGet(frameSize)
            queue.addLast(frame)
            checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
            return true
        }

        // --- OVERFLOW ---
        onCongestion()

        // Drop oldest AUDIO_FRAMEs
        val iter = queue.iterator()
        while (iter.hasNext() && currentQueueBytes.get() + frameSize > capacityBytes) {
            val qFrame = iter.next()
            if (qFrame.kind == FrameKind.AUDIO_FRAME) {
                iter.remove()
                currentQueueBytes.addAndGet(-qFrame.body.size.toLong())
                droppedAudioCount++
            }
        }

        if (currentQueueBytes.get() + frameSize <= capacityBytes) {
            currentQueueBytes.addAndGet(frameSize)
            queue.addLast(frame)
            checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
            return true
        }

        // Still over capacity
        if (frame.kind == FrameKind.AUDIO_FRAME) {
            droppedAudioCount++
            checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
            return false
        }

        // Incoming frame is VIDEO_FRAME -> GOP-aligned video shed
        onRequestKeyframe()
        isVideoShedding = true

        val videoIter = queue.iterator()
        while (videoIter.hasNext()) {
            val qFrame = videoIter.next()
            if (qFrame.kind == FrameKind.VIDEO_FRAME) {
                videoIter.remove()
                currentQueueBytes.addAndGet(-qFrame.body.size.toLong())
                droppedVideoCount++
            }
        }

        droppedVideoCount++
        checkCapacityAndTimers(currentQueueBytes.get(), capacityBytes, nowNs)
        return false
    }

    fun checkCapacityAndTimers(currentBytes: Long, capacityBytes: Long, nowNs: Long = clock()) {
        val isOver = currentBytes > capacityBytes || isVideoShedding

        if (isOver) {
            recoveryStartTimeNs = null
            if (overflowStartTimeNs == null) {
                overflowStartTimeNs = nowNs
            } else if (nowNs - overflowStartTimeNs!! >= 3_000_000_000L) {
                // Continuous overflow > 3s -> bitrate step-down * 0.8, floor 2M
                val currentMbps = configuredBitrateBps / 1_000_000
                val newMbps = (currentMbps * 0.8).toInt().coerceAtLeast(2)
                val newBitrateBps = newMbps * 1_000_000
                if (newBitrateBps < configuredBitrateBps) {
                    configuredBitrateBps = newBitrateBps
                    abrStepDownCount++
                    onBitrateChanged(newBitrateBps)
                    onAbrStepDown()
                }
                overflowStartTimeNs = nowNs
            }
        } else {
            overflowStartTimeNs = null
            // Recovery: queue < 50% capacity for 5s -> step-up * 1.25 to original user setting
            if (currentBytes < (capacityBytes * 0.5) && configuredBitrateBps < originalBitrateBps) {
                if (recoveryStartTimeNs == null) {
                    recoveryStartTimeNs = nowNs
                } else if (nowNs - recoveryStartTimeNs!! >= 5_000_000_000L) {
                    val currentMbps = configuredBitrateBps / 1_000_000
                    val origMbps = originalBitrateBps / 1_000_000
                    val targetMbps = (currentMbps * 1.25).roundToInt()
                    val newMbps = targetMbps.coerceIn(currentMbps + 1, origMbps)
                    val newBitrateBps = newMbps * 1_000_000
                    configuredBitrateBps = newBitrateBps
                    onBitrateChanged(newBitrateBps)
                    onAbrRecovery()
                    recoveryStartTimeNs = nowNs
                }
            } else {
                recoveryStartTimeNs = null
            }
        }
    }
}
