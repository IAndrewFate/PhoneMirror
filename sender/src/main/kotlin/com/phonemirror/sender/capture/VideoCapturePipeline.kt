package com.phonemirror.sender.capture

import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.phonemirror.protocol.control.ControlMessage
import com.phonemirror.protocol.control.encodeControl
import com.phonemirror.protocol.session.SessionEvent
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.protocol.wire.VideoFrameBody
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.ProjectionHolder
import com.phonemirror.sender.service.ProjectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed class VideoCaptureState {
    data object Idle : VideoCaptureState()
    data class Capturing(val width: Int, val height: Int, val dpi: Int, val fps: Int) : VideoCaptureState()
    data class Error(val message: String) : VideoCaptureState()
}

/**
 * Manages the screen capture pipeline:
 * VirtualDisplay -> MediaCodec H.264 (surface input) -> Framed output.
 *
 * Enforces Android 14+ constraint: VirtualDisplay created EXACTLY ONCE per consent session.
 * Dynamic rotation/resolution changes recreate only the encoder and update the existing VirtualDisplay.
 */
class VideoCapturePipeline(
    private val projectionHolder: ProjectionHolder = ProjectionHolder,
    private val streamClient: StreamClient,
    val sessionPolicy: SessionPolicy = streamClient.sessionPolicy,
    var settings: VideoSettings = VideoSettings(),
    private val encoderFactory: VideoEncoderFactory = DefaultVideoEncoderFactory,
    private val projectionAdapterFactory: (MediaProjection?) -> ProjectionAdapter = { AndroidProjectionAdapter(it!!) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() }
) {
    private val _state = MutableStateFlow<VideoCaptureState>(VideoCaptureState.Idle)
    val state: StateFlow<VideoCaptureState> = _state.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var pipelineScope: CoroutineScope? = null
    private var pumpJob: Job? = null
    private var debounceJob: Job? = null

    private var virtualDisplayHandle: VirtualDisplayHandle? = null
    private var encoder: VideoEncoder? = null

    var currentWidth: Int = 0
        private set
    var currentHeight: Int = 0
        private set
    var currentDpi: Int = 0
        private set

    var cachedVideoConfig: ByteArray? = null
        private set

    var watchdogRecreateCount: Int = 0
        private set
    var lastOutputTimestampNs: Long = 0L
        private set

    private val cleanupHook: () -> Unit = { stop() }

    init {
        projectionHolder.cleanupHooks.add(cleanupHook)
    }

    fun start(
        scope: CoroutineScope,
        initialScreenW: Int,
        initialScreenH: Int,
        initialDpi: Int
    ) {
        if (!isRunning.compareAndSet(false, true)) return

        pipelineScope = scope
        watchdogRecreateCount = 0
        lastOutputTimestampNs = clock()

        val (targetW, targetH) = VideoSizeSelector.chooseEncodeSize(initialScreenW, initialScreenH, settings.resolutionCap)
        val targetDpi = VideoSizeSelector.scaleDpi(initialDpi, initialScreenW, initialScreenH, targetW, targetH)

        currentWidth = targetW
        currentHeight = targetH
        currentDpi = targetDpi

        // Immediately inform receiver of the encoded dimensions
        val resMsg = ControlMessage.ResolutionChange(targetW, targetH, targetDpi)
        streamClient.sendFrame(Frame(FrameKind.CONTROL, encodeControl(resMsg)))
        sessionPolicy.onEvent(SessionEvent.ResolutionChanged(targetW, targetH, targetDpi))

        val pState = projectionHolder.state.value

        val enc = encoderFactory.createEncoder()
        enc.configure(targetW, targetH, settings)
        encoder = enc

        if (pState is ProjectionState.Active) {
            val adapter = projectionAdapterFactory(pState.projection)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            val handler = try { Handler(Looper.getMainLooper()) } catch (_: Throwable) { null }
            virtualDisplayHandle = adapter.createVirtualDisplay(
                "PhoneMirror",
                targetW,
                targetH,
                targetDpi,
                flags,
                enc.inputSurface,
                null,
                handler
            )
        }

        enc.start()
        _state.value = VideoCaptureState.Capturing(targetW, targetH, targetDpi, settings.fps)

        startPump(scope)
    }

    private fun startPump(scope: CoroutineScope) {
        pumpJob?.cancel()
        pumpJob = scope.launch(dispatcher) {
            runPumpLoop()
        }
    }

    /**
     * Attempts to dequeue and process a single output buffer from the encoder.
     * Returns true if a config or frame was processed, false otherwise.
     */
    fun pumpOnce(): Boolean {
        val enc = encoder ?: return false
        val output = enc.dequeueOutput(10_000L) ?: return false

        return when (output) {
            is CodecOutput.Config -> {
                cachedVideoConfig = output.data
                lastOutputTimestampNs = clock()
                streamClient.sendFrame(Frame(FrameKind.VIDEO_CONFIG, output.data))
                sessionPolicy.onEvent(SessionEvent.VideoConfigSent)
                true
            }
            is CodecOutput.Frame -> {
                lastOutputTimestampNs = clock()
                val body = VideoFrameBody(
                    ptsUs = output.ptsUs,
                    keyframe = output.isKeyframe,
                    nal = output.data
                )
                streamClient.sendFrame(Frame(FrameKind.VIDEO_FRAME, body.encode()))
                if (output.isKeyframe) {
                    sessionPolicy.onEvent(SessionEvent.KeyframeSent)
                }
                enc.releaseOutputBuffer(output.bufferIndex)
                true
            }
            is CodecOutput.TryAgainLater -> {
                false
            }
        }
    }

    private suspend fun CoroutineScope.runPumpLoop() {
        while (isActive && isRunning.get()) {
            checkWatchdog()
            val processed = pumpOnce()
            if (!processed) {
                delay(10)
            }
        }
    }

    /**
     * Watchdog: checks if encoder has stalled for >3s while projection is active.
     */
    fun checkWatchdog() {
        val projectionActive = projectionHolder.state.value is ProjectionState.Active
        if (!projectionActive) {
            return
        }

        val now = clock()
        val silenceDurationNs = now - lastOutputTimestampNs
        if (silenceDurationNs > 3_000_000_000L) { // 3 seconds
            if (watchdogRecreateCount < 2) {
                watchdogRecreateCount++
                lastOutputTimestampNs = now
                triggerEncoderRecreate()
            } else {
                _state.value = VideoCaptureState.Error("Encoder stalled after 2 watchdog restarts")
                stop()
            }
        }
    }

    private fun triggerEncoderRecreate() {
        val scope = pipelineScope ?: return
        pumpJob?.cancel()

        val oldEnc = encoder
        val newEnc = encoderFactory.createEncoder()
        newEnc.configure(currentWidth, currentHeight, settings)

        virtualDisplayHandle?.setSurface(newEnc.inputSurface)
        oldEnc?.stop()
        oldEnc?.release()

        encoder = newEnc
        newEnc.start()

        // Resend cached config or request keyframe
        cachedVideoConfig?.let {
            streamClient.sendFrame(Frame(FrameKind.VIDEO_CONFIG, it))
            sessionPolicy.onEvent(SessionEvent.VideoConfigSent)
        }
        newEnc.requestKeyframe()

        startPump(scope)
    }

    fun onDisplayChanged(newScreenW: Int, newScreenH: Int, newDpi: Int, debounceMs: Long = 300L) {
        val scope = pipelineScope ?: return
        debounceJob?.cancel()
        if (debounceMs <= 0L) {
            handleDisplayChanged(newScreenW, newScreenH, newDpi)
        } else {
            debounceJob = scope.launch(dispatcher) {
                delay(debounceMs)
                handleDisplayChanged(newScreenW, newScreenH, newDpi)
            }
        }
    }

    fun handleDisplayChanged(newScreenW: Int, newScreenH: Int, newDpi: Int) {
        val (targetW, targetH) = VideoSizeSelector.chooseEncodeSize(newScreenW, newScreenH, settings.resolutionCap)
        val targetDpi = VideoSizeSelector.scaleDpi(newDpi, newScreenW, newScreenH, targetW, targetH)

        if (targetW == currentWidth && targetH == currentHeight && targetDpi == currentDpi) {
            return
        }

        currentWidth = targetW
        currentHeight = targetH
        currentDpi = targetDpi

        val scope = pipelineScope ?: return
        pumpJob?.cancel()

        val oldEnc = encoder
        val newEnc = encoderFactory.createEncoder()
        newEnc.configure(targetW, targetH, settings)

        // Android 14 compliant: resize & swap surface on the EXACT SAME VirtualDisplay
        virtualDisplayHandle?.resize(targetW, targetH, targetDpi)
        virtualDisplayHandle?.setSurface(newEnc.inputSurface)

        oldEnc?.stop()
        oldEnc?.release()

        encoder = newEnc
        newEnc.start()

        // Send ResolutionChange control message
        val resMsg = ControlMessage.ResolutionChange(targetW, targetH, targetDpi)
        streamClient.sendFrame(Frame(FrameKind.CONTROL, encodeControl(resMsg)))
        sessionPolicy.onEvent(SessionEvent.ResolutionChanged(targetW, targetH, targetDpi))

        // Force keyframe request
        newEnc.requestKeyframe()

        _state.value = VideoCaptureState.Capturing(targetW, targetH, targetDpi, settings.fps)
        startPump(scope)
    }

    fun resendConfigOnReconnect() {
        val config = cachedVideoConfig ?: return
        streamClient.sendFrame(Frame(FrameKind.VIDEO_CONFIG, config))
        sessionPolicy.onEvent(SessionEvent.VideoConfigSent)
        encoder?.requestKeyframe()
    }

    fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    fun updateBitrate(newBitrateBps: Int) {
        settings = settings.copy(bitrate = newBitrateBps)
        encoder?.setBitrate(newBitrateBps)
    }

    fun stop() {
        isRunning.set(false)
        pumpJob?.cancel()
        pumpJob = null
        debounceJob?.cancel()
        debounceJob = null

        encoder?.stop()
        encoder?.release()
        encoder = null

        virtualDisplayHandle?.release()
        virtualDisplayHandle = null

        if (_state.value !is VideoCaptureState.Error) {
            _state.value = VideoCaptureState.Idle
        }
    }
}