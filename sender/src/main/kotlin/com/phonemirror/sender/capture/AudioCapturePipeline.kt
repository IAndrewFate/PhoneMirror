package com.phonemirror.sender.capture

import android.os.Build
import com.phonemirror.protocol.session.SessionEvent
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.AudioFrameBody
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.ProjectionHolder
import com.phonemirror.sender.service.ProjectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

sealed class AudioCaptureState {
    data object Idle : AudioCaptureState()
    data class Disabled(val reason: String) : AudioCaptureState()
    data class Capturing(val codec: String, val sampleRate: Int, val channels: Int) : AudioCaptureState()
    data class Error(val message: String) : AudioCaptureState()
}

/**
 * Audio playback capture pipeline:
 * AudioPlaybackCapture -> MediaCodec Opus (with AAC fallback) -> Framed output.
 * Gated to Android 10+ (API 29+).
 */
class AudioCapturePipeline(
    private val projectionHolder: ProjectionHolder = ProjectionHolder,
    private val streamClient: StreamClient,
    val sessionPolicy: SessionPolicy = streamClient.sessionPolicy,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    private val sourceFactory: AudioCaptureSourceFactory = DefaultAudioCaptureSourceFactory,
    private val encoderFactory: AudioEncoderFactory = DefaultAudioEncoderFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() }
) {
    private val _state = MutableStateFlow<AudioCaptureState>(AudioCaptureState.Idle)
    val state: StateFlow<AudioCaptureState> = _state.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var feedJob: Job? = null
    private var pumpJob: Job? = null

    private var audioSource: AudioCaptureSource? = null
    private var audioEncoder: AudioEncoder? = null

    var isConfigSent: Boolean = false
        private set
    var cachedAudioConfigPayload: AudioConfigPayload? = null
        private set
    var lastAudioBufferTimestampNs: Long = 0L
        private set

    private val cleanupHook: () -> Unit = { stop() }

    init {
        projectionHolder.cleanupHooks.add(cleanupHook)
        if (sdkInt < 29) {
            _state.value = AudioCaptureState.Disabled("API level  < 29 (AudioPlaybackCapture unsupported)")
        }
    }

    val isAudioSupported: Boolean
        get() = sdkInt >= 29

    fun start(scope: CoroutineScope) {
        if (!isAudioSupported) return
        if (!isRunning.compareAndSet(false, true)) return

        val pState = projectionHolder.state.value
        val projection = if (pState is ProjectionState.Active) pState.projection else null

        isConfigSent = false
        lastAudioBufferTimestampNs = clock()

        try {
            val encoder = encoderFactory.createEncoder()
            encoder.configure(AudioMath.SAMPLE_RATE, AudioMath.CHANNELS, 128_000)
            encoder.start()
            audioEncoder = encoder

            val source = sourceFactory.createSource(projection)
            source.start()
            audioSource = source

            _state.value = AudioCaptureState.Capturing(
                codec = encoder.codecName,
                sampleRate = AudioMath.SAMPLE_RATE,
                channels = AudioMath.CHANNELS
            )

            startLoops(scope)
        } catch (e: Throwable) {
            _state.value = AudioCaptureState.Error("Failed to initialize audio capture: ")
            stop()
        }
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

    private suspend fun CoroutineScope.runFeedLoop() {
        val source = audioSource ?: return
        val encoder = audioEncoder ?: return
        val buffer = ByteArray(AudioMath.BYTES_PER_CHUNK)

        while (isActive && isRunning.get()) {
            val bytesRead = source.read(buffer, 0, AudioMath.BYTES_PER_CHUNK)
            if (bytesRead > 0) {
                lastAudioBufferTimestampNs = clock()
                val timestampNs = source.getTimestampNs() ?: clock()
                val ptsUs = AudioMath.computePtsUs(timestampNs)
                encoder.queueInput(buffer, 0, bytesRead, ptsUs)
            } else {
                delay(10)
            }
        }
    }

    /**
     * Single-step output pump processing. Returns true if an output item was processed.
     */
    fun pumpOnce(): Boolean {
        val enc = audioEncoder ?: return false
        val output = enc.dequeueOutput(10_000L) ?: return false

        return when (output) {
            is AudioCodecOutput.Config -> {
                sendConfig(output.csdList)
                true
            }
            is AudioCodecOutput.Frame -> {
                if (!isConfigSent) {
                    // Send default/derived config if not sent yet
                    sendConfig(emptyList())
                }
                lastAudioBufferTimestampNs = clock()
                val body = AudioFrameBody(ptsUs = output.ptsUs, packet = output.data)
                streamClient.sendFrame(Frame(FrameKind.AUDIO_FRAME, body.encode()))
                true
            }
            is AudioCodecOutput.TryAgainLater -> {
                false
            }
        }
    }

    private suspend fun CoroutineScope.runPumpLoop() {
        while (isActive && isRunning.get()) {
            val processed = pumpOnce()
            if (!processed) {
                delay(10)
            }
        }
    }

    private fun sendConfig(csdList: List<ByteArray>) {
        val enc = audioEncoder ?: return
        val base64Csd = if (csdList.isNotEmpty()) {
            csdList.map { Base64.getEncoder().encodeToString(it) }
        } else if (enc.codecName == "opus") {
            listOf(
                Base64.getEncoder().encodeToString(OpusCsdBuilder.buildOpusHead()),
                Base64.getEncoder().encodeToString(OpusCsdBuilder.buildCodecDelay()),
                Base64.getEncoder().encodeToString(OpusCsdBuilder.buildSeekPreRoll())
            )
        } else {
            emptyList()
        }

        val payload = AudioConfigPayload(
            codec = enc.codecName,
            sampleRate = AudioMath.SAMPLE_RATE,
            channels = AudioMath.CHANNELS,
            frameMs = AudioMath.FRAME_MS,
            bitrate = 128_000,
            csd = base64Csd
        )
        cachedAudioConfigPayload = payload

        val jsonStr = Json.encodeToString(AudioConfigPayload.serializer(), payload)
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        streamClient.sendFrame(Frame(FrameKind.AUDIO_CONFIG, jsonBytes))
        sessionPolicy.onEvent(SessionEvent.AudioConfigSent)
        isConfigSent = true
    }

    fun isSilenceTolerated(): Boolean {
        // Apps with ALLOW_CAPTURE_BY_NONE produce silence/absence:
        // >3s without audio buffers does not fail streaming.
        val now = clock()
        return (now - lastAudioBufferTimestampNs) > 3_000_000_000L
    }

    fun stop() {
        isRunning.set(false)
        feedJob?.cancel()
        feedJob = null
        pumpJob?.cancel()
        pumpJob = null

        audioSource?.stop()
        audioSource?.release()
        audioSource = null

        audioEncoder?.stop()
        audioEncoder?.release()
        audioEncoder = null

        if (_state.value !is AudioCaptureState.Disabled && _state.value !is AudioCaptureState.Error) {
            _state.value = AudioCaptureState.Idle
        }
    }
}