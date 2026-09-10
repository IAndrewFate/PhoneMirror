package com.phonemirror.sender.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonemirror.protocol.control.ErrorReason
import com.phonemirror.sender.capture.AudioCapturePipeline
import com.phonemirror.sender.capture.VideoCapturePipeline
import com.phonemirror.sender.capture.VideoSettings
import com.phonemirror.sender.data.MirrorSettings
import com.phonemirror.sender.data.SettingsRepository
import com.phonemirror.sender.net.ClientState
import com.phonemirror.sender.net.DiscoveredDevice
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.ProjectionHolder
import com.phonemirror.sender.service.ProjectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SessionStats(
    val elapsedSeconds: Long = 0L,
    val videoFps: Double = 0.0,
    val videoKbps: Long = 0L,
    val audioKbps: Long = 0L,
    val rttMs: Long = 0L,
    val gen: Long = 0L,
    val reconnectAttempt: Int = 0,
    val overflowDroppedCount: Long = 0L,
    val abrStepDownCount: Int = 0,
    val encoderRecreates: Int = 0
) {
    val formattedDuration: String
        get() = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
}

sealed class UiError {
    data class PinRejected(val message: String = "Wrong PIN - check the TV screen") : UiError()
    data class Busy(val message: String = "TV is already streaming another phone") : UiError()
    data class Failed(val message: String = "TV unreachable after 10 attempts") : UiError()
    data class Revoked(val message: String = "Screen capture stopped by system") : UiError()
}

class MainViewModel(
    val streamClient: StreamClient = StreamClient(),
    val settingsRepository: SettingsRepository = SettingsRepository(),
    val projectionHolder: ProjectionHolder = ProjectionHolder,
    val windowController: WindowController = FakeWindowController(),
    private val videoPipelineFactory: (StreamClient, VideoSettings) -> VideoCapturePipeline = { client, settings ->
        VideoCapturePipeline(
            streamClient = client,
            projectionHolder = projectionHolder,
            settings = settings
        )
    },
    private val audioPipelineFactory: (StreamClient) -> AudioCapturePipeline = { client ->
        AudioCapturePipeline(
            projectionHolder = projectionHolder,
            streamClient = client
        )
    },
    private val logcatSessionLogger: (String) -> Unit = {},
    val clock: () -> Long = { System.currentTimeMillis() },
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    val clientState: StateFlow<ClientState> = streamClient.state
    val projectionState: StateFlow<ProjectionState> = projectionHolder.state
    val settings: StateFlow<MirrorSettings> = settingsRepository.settings

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _uiError = MutableStateFlow<UiError?>(null)
    val uiError: StateFlow<UiError?> = _uiError.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _abrNoteVisible = MutableStateFlow(false)
    val abrNoteVisible: StateFlow<Boolean> = _abrNoteVisible.asStateFlow()

    var videoPipeline: VideoCapturePipeline? = null
        private set
    var audioPipeline: AudioCapturePipeline? = null
        private set

    var sessionStartTimeMs: Long = 0L
        private set
    private var startBitrateMbps: Int = 8
    private var lastConnectedEndpoint: DiscoveredDevice? = null
    private var statsJob: Job? = null
    private var clientCollectorJob: Job? = null
    private var projectionCollectorJob: Job? = null

    val lastEndpoint: DiscoveredDevice?
        get() = streamClient.endpointStore.loadEndpoint()

    init {
        clientCollectorJob = scope.launch {
            streamClient.state.collect { state ->
                when (state) {
                    is ClientState.PairingFailure -> {
                        when (state.reason) {
                            ErrorReason.PIN_REJECTED -> _uiError.value = UiError.PinRejected()
                            ErrorReason.BUSY -> _uiError.value = UiError.Busy()
                            else -> {}
                        }
                    }
                    is ClientState.Failed -> {
                        _uiError.value = UiError.Failed()
                        if (_isStreaming.value) {
                            stopMirroring()
                        }
                    }
                    is ClientState.Reconnecting -> {
                        _sessionStats.value = _sessionStats.value.copy(reconnectAttempt = state.attempt)
                    }
                    is ClientState.Connected -> {
                        _sessionStats.value = _sessionStats.value.copy(reconnectAttempt = 0)
                    }
                    else -> {}
                }
            }
        }

        projectionCollectorJob = scope.launch {
            projectionHolder.state.collect { state ->
                when (state) {
                    is ProjectionState.Active -> {
                        startActivePipelines()
                    }
                    is ProjectionState.Revoked -> {
                        _uiError.value = UiError.Revoked()
                        stopMirroring()
                    }
                    else -> {}
                }
            }
        }
    }

    fun connect(host: String, port: Int, pin: String, name: String) {
        lastConnectedEndpoint = DiscoveredDevice(name, host, port)
        streamClient.start(scope, host, port, pin, name)
    }

    fun disconnect() {
        stopMirroring()
    }

    fun startMirroring() {
        projectionHolder.setAwaitingConsent()
    }

    fun onConsentGranted(resultCode: Int, data: Intent, tvName: String, startFgs: (Intent) -> Unit) {
        val intent = Intent().apply {
            putExtra("EXTRA_RESULT_CODE", resultCode)
            putExtra("EXTRA_RESULT_DATA", data)
            putExtra("EXTRA_TV_NAME", tvName)
        }
        startFgs(intent)
    }

    private fun startActivePipelines() {
        val currentSettings = settings.value
        startBitrateMbps = currentSettings.bitrateMbps

        val resCapInt = if (currentSettings.resolutionCap == "720p") 1280 else 1920
        val videoSettings = VideoSettings(
            bitrate = currentSettings.bitrateMbps * 1_000_000,
            fps = currentSettings.fps,
            resolutionCap = resCapInt
        )

        val vPipe = videoPipelineFactory(streamClient, videoSettings)
        videoPipeline = vPipe
        vPipe.start(scope, 1080, 1920, 400)

        if (currentSettings.audioEnabled) {
            val aPipe = audioPipelineFactory(streamClient)
            audioPipeline = aPipe
            aPipe.start(scope)
        }

        sessionStartTimeMs = clock()
        _isStreaming.value = true

        windowController.setKeepScreenOn(true)
        if (currentSettings.dimScreen) {
            windowController.setDimScreen(true)
        }

        startStatsLoop()
    }

    fun stopMirroring() {
        if (!_isStreaming.value && streamClient.state.value is ClientState.Idle) return

        statsJob?.cancel()
        statsJob = null

        videoPipeline?.stop()
        videoPipeline = null

        audioPipeline?.stop()
        audioPipeline = null

        windowController.setDimScreen(false)
        windowController.setKeepScreenOn(false)

        val stats = _sessionStats.value
        val duration = stats.formattedDuration
        val videoSent = stats.elapsedSeconds * 60
        val endBitrate = settings.value.bitrateMbps

        val summary = "duration=$duration, videoFramesSent=$videoSent, videoFramesDropped=${stats.overflowDroppedCount}, audioPacketsSent=0, audioPacketsDropped=0, reconnects=${stats.reconnectAttempt}, bitrateHistory=${startBitrateMbps}Mbps->${endBitrate}Mbps, encoderRecreates=${stats.encoderRecreates}"
        logcatSessionLogger(summary)

        streamClient.stop()
        projectionHolder.setStopped()

        _isStreaming.value = false
        _abrNoteVisible.value = false
    }

    fun updateBitrate(bitrateMbps: Int) {
        settingsRepository.updateBitrate(bitrateMbps)
        if (_isStreaming.value) {
            videoPipeline?.updateBitrate(bitrateMbps * 1_000_000)
        }
    }

    fun updateResolutionCap(cap: String) {
        settingsRepository.updateResolutionCap(cap)
    }

    fun updateFps(fps: Int) {
        settingsRepository.updateFps(fps)
    }

    fun updateAudioEnabled(enabled: Boolean) {
        settingsRepository.updateAudioEnabled(enabled)
    }

    fun updateDimScreen(dim: Boolean) {
        settingsRepository.updateDimScreen(dim)
        if (_isStreaming.value) {
            windowController.setDimScreen(dim)
        }
    }

    fun dismissError() {
        _uiError.value = null
    }

    fun retryConnection() {
        dismissError()
        lastConnectedEndpoint?.let { ep ->
            connect(ep.host, ep.port, "", ep.name)
        }
    }

    fun onAbrStepDown() {
        val count = _sessionStats.value.abrStepDownCount + 1
        _sessionStats.value = _sessionStats.value.copy(abrStepDownCount = count)
        _abrNoteVisible.value = true
    }

    fun onAbrRecovery() {
        _abrNoteVisible.value = false
    }

    fun onOverflowDrop(count: Long) {
        val total = _sessionStats.value.overflowDroppedCount + count
        _sessionStats.value = _sessionStats.value.copy(overflowDroppedCount = total)
    }

    fun tickStats(nowMs: Long = clock(), framesSentDelta: Long = 60, bytesSentDelta: Long = 1_000_000L, audioBytesDelta: Long = 32_000L) {
        if (sessionStartTimeMs == 0L) sessionStartTimeMs = nowMs
        val elapsed = (nowMs - sessionStartTimeMs) / 1000L
        val currentFps = _sessionStats.value.videoFps
        val instantFps = framesSentDelta.toDouble()
        val newFps = if (currentFps == 0.0) instantFps else 0.1 * instantFps + 0.9 * currentFps

        val videoKbps = (bytesSentDelta * 8) / 1000L
        val audioKbps = (audioBytesDelta * 8) / 1000L

        _sessionStats.value = _sessionStats.value.copy(
            elapsedSeconds = elapsed,
            videoFps = newFps,
            videoKbps = videoKbps,
            audioKbps = audioKbps,
            rttMs = streamClient.rttMs.value,
            gen = streamClient.sessionPolicy.gen
        )
    }

    private fun startStatsLoop() {
        statsJob = scope.launch {
            while (isActive && _isStreaming.value) {
                delay(1000L)
                tickStats()
            }
        }
    }

    public override fun onCleared() {
        super.onCleared()
        clientCollectorJob?.cancel()
        clientCollectorJob = null
        projectionCollectorJob?.cancel()
        projectionCollectorJob = null
        stopMirroring()
    }
}
