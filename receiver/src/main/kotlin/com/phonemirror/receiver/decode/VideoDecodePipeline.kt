package com.phonemirror.receiver.decode

import android.view.Surface
import com.phonemirror.protocol.session.SessionEvent
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.receiver.server.VideoSink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

sealed class VideoDecodeState {
    data object Idle : VideoDecodeState()
    data class Configured(val width: Int, val height: Int) : VideoDecodeState()
    data class Decoding(val width: Int, val height: Int) : VideoDecodeState()
    data class Error(val message: String) : VideoDecodeState()
}

data class QueuedVideoFrame(
    val ptsUs: Long,
    val keyframe: Boolean,
    val nal: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedVideoFrame) return false
        return ptsUs == other.ptsUs && keyframe == other.keyframe && nal.contentEquals(other.nal)
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + keyframe.hashCode()
        result = 31 * result + nal.contentHashCode()
        return result
    }
}

class VideoDecodePipeline(
    val sessionPolicy: SessionPolicy,
    var avSync: AvSync = NowAvSync(),
    private val decoderFactory: VideoDecoderFactory = DefaultVideoDecoderFactory,
    var onRequestKeyframe: () -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : VideoSink {

    private val _state = MutableStateFlow<VideoDecodeState>(VideoDecodeState.Idle)
    val state: StateFlow<VideoDecodeState> = _state.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var pipelineScope: CoroutineScope? = null
    private var feedJob: Job? = null
    private var pumpJob: Job? = null

    private var decoder: VideoDecoder? = null
    var surface: Surface? = null
        private set

    var currentWidth: Int = 1920
        private set
    var currentHeight: Int = 1080
        private set
    var currentDpi: Int = 320
        private set

    var cachedSps: ByteArray? = null
        private set
    var cachedPps: ByteArray? = null
        private set

    var awaitKeyframe: Boolean = true
        private set

    val inputQueue = ArrayDeque<QueuedVideoFrame>()
    var droppedFrameCount: Long = 0L
        private set
    var renderedFrameCount: Long = 0L
        private set

    fun setSurface(newSurface: Surface?) {
        surface = newSurface
        if (newSurface != null && cachedSps != null) {
            reconfigureDecoder()
        }
    }

    fun onSurfaceDestroyed() {
        surface = null
    }

    fun start(scope: CoroutineScope) {
        if (!isRunning.compareAndSet(false, true)) return
        pipelineScope = scope
        startLoops(scope)
    }

    private fun startLoops(scope: CoroutineScope) {
        feedJob?.cancel()
        feedJob = scope.launch(dispatcher) {
            runFeedLoop()
        }

        pumpJob?.cancel()
        pumpJob = scope.launch(dispatcher) {
            runPumpLoop()
        }
    }

    override fun onConfig(spsPps: ByteArray) {
        val config = AnnexBParser.parseSpsPps(spsPps)
        cachedSps = config.sps
        cachedPps = config.pps
        awaitKeyframe = true

        reconfigureDecoder()
    }

    override fun onResolutionChange(w: Int, h: Int, dpi: Int) {
        currentWidth = w
        currentHeight = h
        currentDpi = dpi
        awaitKeyframe = true

        reconfigureDecoder()
    }

    override fun onFrame(ptsUs: Long, keyframe: Boolean, nal: ByteArray) {
        synchronized(inputQueue) {
            if (awaitKeyframe) {
                if (keyframe) {
                    awaitKeyframe = false
                    inputQueue.add(QueuedVideoFrame(ptsUs, keyframe, nal))
                } else {
                    droppedFrameCount++
                }
            } else {
                if (inputQueue.size >= 2) {
                    // GOP-aligned overflow drop: drop queued and incoming until next keyframe
                    inputQueue.clear()
                    awaitKeyframe = true
                    droppedFrameCount++
                    onRequestKeyframe()
                } else {
                    inputQueue.add(QueuedVideoFrame(ptsUs, keyframe, nal))
                }
            }
        }
    }

    private fun reconfigureDecoder() {
        val dec = decoder ?: decoderFactory.createDecoder().also { decoder = it }
        dec.stop()
        dec.release()

        val format = VideoDecoderFormat(
            width = currentWidth,
            height = currentHeight,
            sps = cachedSps,
            pps = cachedPps
        )

        try {
            dec.configure(format, surface)
            dec.start()
            _state.value = VideoDecodeState.Decoding(currentWidth, currentHeight)
        } catch (e: Throwable) {
            _state.value = VideoDecodeState.Error("Failed to configure video decoder: ")
        }
    }

    /**
     * Attempts to feed one queued frame into the decoder.
     */
    fun feedOnce(): Boolean {
        val dec = decoder ?: return false
        val frame = synchronized(inputQueue) {
            if (inputQueue.isNotEmpty()) inputQueue.removeFirst() else null
        } ?: return false

        return dec.queueInput(frame.nal, frame.ptsUs, frame.keyframe)
    }

    /**
     * Attempts to dequeue and render/drop one decoded output frame.
     */
    fun pumpOnce(): Boolean {
        val dec = decoder ?: return false
        val output = dec.dequeueOutput(10_000L) ?: return false

        return when (output) {
            is DecoderOutput.Frame -> {
                if (awaitKeyframe && !output.isKeyframe) {
                    dec.releaseOutputBuffer(output.bufferIndex, false)
                    droppedFrameCount++
                } else {
                    if (output.isKeyframe) {
                        awaitKeyframe = false
                        sessionPolicy.onEvent(SessionEvent.KeyframeReceived)
                    }

                    when (val decision = avSync.scheduleRender(output.ptsUs)) {
                        is RenderDecision.Now -> {
                            dec.releaseOutputBuffer(output.bufferIndex, true)
                            renderedFrameCount++
                        }
                        is RenderDecision.At -> {
                            dec.releaseOutputBuffer(output.bufferIndex, true, decision.ns)
                            renderedFrameCount++
                        }
                        is RenderDecision.Drop -> {
                            dec.releaseOutputBuffer(output.bufferIndex, false)
                            droppedFrameCount++
                        }
                    }
                }
                true
            }
            is DecoderOutput.Error -> {
                _state.value = VideoDecodeState.Error(output.message)
                false
            }
            is DecoderOutput.TryAgainLater -> {
                false
            }
        }
    }

    private suspend fun CoroutineScope.runFeedLoop() {
        while (isActive && isRunning.get()) {
            val fed = feedOnce()
            if (!fed) {
                delay(10)
            }
        }
    }

    private suspend fun CoroutineScope.runPumpLoop() {
        while (isActive && isRunning.get()) {
            val pumped = pumpOnce()
            if (!pumped) {
                delay(10)
            }
        }
    }

    fun resetDecoder() {
        awaitKeyframe = true
        decoder?.stop()
        decoder?.release()
        decoder = null
        synchronized(inputQueue) {
            inputQueue.clear()
        }
        _state.value = VideoDecodeState.Idle
    }

    fun stop() {
        isRunning.set(false)
        feedJob?.cancel()
        feedJob = null
        pumpJob?.cancel()
        pumpJob = null

        resetDecoder()
    }
}