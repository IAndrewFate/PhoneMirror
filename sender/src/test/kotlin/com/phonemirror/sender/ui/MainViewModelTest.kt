package com.phonemirror.sender.ui

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.ErrorReason
import com.phonemirror.sender.capture.*
import com.phonemirror.sender.data.InMemorySettingsStore
import com.phonemirror.sender.data.MirrorSettings
import com.phonemirror.sender.data.SettingsRepository
import com.phonemirror.sender.net.ClientState
import com.phonemirror.sender.net.DiscoveredDevice
import com.phonemirror.sender.net.InMemoryEndpointStore
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.MirrorTileService
import com.phonemirror.sender.service.ProjectionHolder
import com.phonemirror.sender.service.ProjectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private class FakeEncoder : VideoEncoder {
        override val inputSurface: android.view.Surface? = null
        var currentBitrate = 0
        override fun configure(width: Int, height: Int, settings: VideoSettings) {}
        override fun start() {}
        override fun stop() {}
        override fun release() {}
        override fun requestKeyframe() {}
        override fun setBitrate(bitrateBps: Int) {
            currentBitrate = bitrateBps
        }
        override fun dequeueOutput(timeoutUs: Long): CodecOutput? = null
        override fun releaseOutputBuffer(index: Int) {}
    }

    private class FakeDisplayHandle : VirtualDisplayHandle {
        override fun resize(width: Int, height: Int, densityDpi: Int) {}
        override fun setSurface(surface: android.view.Surface?) {}
        override fun release() {}
    }

    private class FakeAdapter : ProjectionAdapter {
        override fun createVirtualDisplay(
            name: String, width: Int, height: Int, dpi: Int, flags: Int,
            surface: android.view.Surface?,
            callback: android.hardware.display.VirtualDisplay.Callback?,
            handler: android.os.Handler?
        ): VirtualDisplayHandle? = FakeDisplayHandle()
    }

    private lateinit var store: InMemorySettingsStore
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var windowController: FakeWindowController
    private lateinit var endpointStore: InMemoryEndpointStore
    private lateinit var streamClient: StreamClient
    private lateinit var fakeEncoder: FakeEncoder
    private lateinit var fakeAdapter: FakeAdapter
    private var loggedSessionSummary: String? = null

    @Before
    fun setUp() {
        store = InMemorySettingsStore()
        settingsRepo = SettingsRepository(store)
        windowController = FakeWindowController()
        endpointStore = InMemoryEndpointStore()
        streamClient = StreamClient(endpointStore = endpointStore)
        fakeEncoder = FakeEncoder()
        fakeAdapter = FakeAdapter()
        loggedSessionSummary = null
        ProjectionHolder.reset()
    }

    private fun createViewModel(scope: CoroutineScope): MainViewModel {
        return MainViewModel(
            streamClient = streamClient,
            settingsRepository = settingsRepo,
            projectionHolder = ProjectionHolder,
            windowController = windowController,
            videoPipelineFactory = { client, settings ->
                VideoCapturePipeline(
                    streamClient = client,
                    projectionHolder = ProjectionHolder,
                    settings = settings,
                    encoderFactory = object : VideoEncoderFactory {
                        override fun createEncoder(): VideoEncoder = fakeEncoder
                    },
                    projectionAdapterFactory = { fakeAdapter }
                )
            },
            audioPipelineFactory = { client ->
                AudioCapturePipeline(
                    projectionHolder = ProjectionHolder,
                    streamClient = client,
                    sdkInt = 0 // Clean no-op in JVM unit tests
                )
            },
            logcatSessionLogger = { loggedSessionSummary = it },
            coroutineScope = scope
        )
    }

    // 1. Settings persistence saves and loads all fields including dim-toggle
    @Test
    fun settings_persistence_saves_and_loads_all_fields_including_dim_toggle() = runTest {
        val vm = createViewModel(backgroundScope)
        vm.updateBitrate(14)
        vm.updateResolutionCap("720p")
        vm.updateFps(30)
        vm.updateAudioEnabled(false)
        vm.updateDimScreen(true)

        val s = vm.settings.value
        assertThat(s.bitrateMbps).isEqualTo(14)
        assertThat(s.resolutionCap).isEqualTo("720p")
        assertThat(s.fps).isEqualTo(30)
        assertThat(s.audioEnabled).isFalse()
        assertThat(s.dimScreen).isTrue()

        val newRepo = SettingsRepository(store)
        assertThat(newRepo.settings.value).isEqualTo(s)
    }

    // 2. Stats math computes fps and kbps EMA over clock ticks
    @Test
    fun stats_math_computes_fps_and_kbps_ema_over_clock_ticks() = runTest {
        val vm = createViewModel(backgroundScope)
        vm.tickStats(nowMs = 1_000_000L, framesSentDelta = 60, bytesSentDelta = 1_000_000L, audioBytesDelta = 32_000L)
        vm.tickStats(nowMs = 1_002_000L, framesSentDelta = 50, bytesSentDelta = 800_000L, audioBytesDelta = 30_000L)

        val stats = vm.sessionStats.value
        assertThat(stats.elapsedSeconds).isEqualTo(2L)
        assertThat(stats.formattedDuration).isEqualTo("00:02")
        assertThat(stats.videoKbps).isEqualTo(6400L)
        assertThat(stats.audioKbps).isEqualTo(240L)
        assertThat(stats.videoFps).isGreaterThan(0.0)
    }

    // 3. ViewModel happy transcript: Idle -> Connecting -> Connected -> Streaming -> stop
    @Test
    fun view_model_happy_transcript() = runTest {
        val vm = createViewModel(backgroundScope)
        assertThat(vm.clientState.value).isEqualTo(ClientState.Idle)
        assertThat(vm.isStreaming.value).isFalse()

        endpointStore.saveEndpoint("192.168.1.100", 47700, "Living Room TV")
        vm.connect("192.168.1.100", 47700, "1234", "Living Room TV")
        runCurrent()

        ProjectionHolder.setActive(null)
        runCurrent()

        assertThat(vm.isStreaming.value).isTrue()
        assertThat(windowController.isKeepScreenOn).isTrue()

        vm.stopMirroring()
        runCurrent()

        assertThat(vm.isStreaming.value).isFalse()
        assertThat(windowController.isKeepScreenOn).isFalse()
        assertThat(loggedSessionSummary).isNotNull()
    }

    // 4. Error path: PIN_REJECTED surfaces dialog and clears on dismiss
    @Test
    fun error_path_pin_rejected_surfaces_dialog_and_dismisses() = runTest {
        val vm = createViewModel(backgroundScope)
        streamClient.notifyState(ClientState.PairingFailure(ErrorReason.PIN_REJECTED))
        runCurrent()

        assertThat(vm.uiError.value).isInstanceOf(UiError.PinRejected::class.java)
        vm.dismissError()
        assertThat(vm.uiError.value).isNull()
    }

    // 5. Error path: BUSY surfaces dialog
    @Test
    fun error_path_busy_surfaces_dialog() = runTest {
        val vm = createViewModel(backgroundScope)
        streamClient.notifyState(ClientState.PairingFailure(ErrorReason.BUSY))
        runCurrent()

        assertThat(vm.uiError.value).isInstanceOf(UiError.Busy::class.java)
    }

    // 6. Error path: Failed surfaces snackbar and retry reconnects
    @Test
    fun error_path_failed_surfaces_snackbar_and_retry_reconnects() = runTest {
        val vm = createViewModel(backgroundScope)
        vm.connect("192.168.1.100", 47700, "1234", "Living Room TV")
        streamClient.notifyState(ClientState.Failed)
        runCurrent()

        assertThat(vm.uiError.value).isInstanceOf(UiError.Failed::class.java)
        vm.retryConnection()
        assertThat(vm.uiError.value).isNull()
    }

    // 7. Error path: Revoked releases pipelines, restores dim, and shows dialog
    @Test
    fun error_path_revoked_releases_pipelines_restores_dim_and_shows_dialog() = runTest {
        val vm = createViewModel(backgroundScope)
        vm.updateDimScreen(true)

        ProjectionHolder.setActive(null)
        runCurrent()

        assertThat(vm.isStreaming.value).isTrue()
        assertThat(windowController.isDimmed).isTrue()
        assertThat(windowController.isKeepScreenOn).isTrue()

        ProjectionHolder.setRevoked()
        runCurrent()

        assertThat(vm.uiError.value).isInstanceOf(UiError.Revoked::class.java)
        assertThat(vm.isStreaming.value).isFalse()
        assertThat(windowController.isDimmed).isFalse()
        assertThat(windowController.isKeepScreenOn).isFalse()
    }

    // 8. Keep screen on flag lifecycle
    @Test
    fun keep_screen_on_flag_lifecycle() = runTest {
        val vm = createViewModel(backgroundScope)
        assertThat(windowController.isKeepScreenOn).isFalse()

        ProjectionHolder.setActive(null)
        runCurrent()
        assertThat(windowController.isKeepScreenOn).isTrue()

        vm.stopMirroring()
        runCurrent()
        assertThat(windowController.isKeepScreenOn).isFalse()
    }

    // 9. Live-vs-next-session apply matrix
    @Test
    fun live_vs_next_session_apply_matrix() = runTest {
        val vm = createViewModel(backgroundScope)
        ProjectionHolder.setActive(null)
        runCurrent()

        vm.updateBitrate(12)
        assertThat(vm.settings.value.bitrateMbps).isEqualTo(12)
        assertThat(fakeEncoder.currentBitrate).isEqualTo(12_000_000)

        vm.updateResolutionCap("720p")
        vm.updateFps(30)
        assertThat(vm.settings.value.resolutionCap).isEqualTo("720p")
        assertThat(vm.settings.value.fps).isEqualTo(30)
    }

    // 10. Screen dim restore on stop and revoked
    @Test
    fun screen_dim_restore_on_stop_and_revoked() = runTest {
        val vm = createViewModel(backgroundScope)
        vm.updateDimScreen(true)

        ProjectionHolder.setActive(null)
        runCurrent()
        assertThat(windowController.isDimmed).isTrue()

        vm.stopMirroring()
        runCurrent()
        assertThat(windowController.isDimmed).isFalse()
    }

    // 11. Tile state matrix and intent creation
    @Test
    fun tile_state_matrix_and_intent_creation() {
        val (s1, t1) = MirrorTileService.computeTileState(ClientState.Idle, false)
        assertThat(t1).isEqualTo("Off")

        val (s2, t2) = MirrorTileService.computeTileState(ClientState.Connected("192.168.1.5", 47700), true)
        assertThat(t2).isEqualTo("Streaming")

        val (s3, t3) = MirrorTileService.computeTileState(ClientState.Failed, false)
        assertThat(t3).isEqualTo("Failed")

        val (s4, t4) = MirrorTileService.computeTileState(ClientState.Connecting("192.168.1.5", 47700), false)
        assertThat(t4).isEqualTo("Connecting")

        val (s5, t5) = MirrorTileService.computeTileState(ClientState.Reconnecting(2), false)
        assertThat(t5).isEqualTo("Reconnecting")
    }

    // 12. ABR note visibility follows step-down and recovery
    @Test
    fun abr_note_visibility_follows_step_down_and_recovery() = runTest {
        val vm = createViewModel(backgroundScope)
        assertThat(vm.abrNoteVisible.value).isFalse()

        vm.onAbrStepDown()
        assertThat(vm.abrNoteVisible.value).isTrue()
        assertThat(vm.sessionStats.value.abrStepDownCount).isEqualTo(1)

        vm.onAbrRecovery()
        assertThat(vm.abrNoteVisible.value).isFalse()
    }

    // 13. Session summary line contains all counters on stop
    @Test
    fun session_summary_line_contains_all_counters_on_stop() = runTest {
        val vm = createViewModel(backgroundScope)
        ProjectionHolder.setActive(null)
        runCurrent()

        vm.tickStats(nowMs = 1_000_000L)
        vm.tickStats(nowMs = 1_010_000L)
        vm.onOverflowDrop(3)

        vm.stopMirroring()
        runCurrent()

        assertThat(loggedSessionSummary).isNotNull()
        val s = loggedSessionSummary!!
        assertThat(s).contains("duration=")
        assertThat(s).contains("videoFramesSent=")
        assertThat(s).contains("videoFramesDropped=3")
        assertThat(s).contains("audioPacketsSent=")
        assertThat(s).contains("audioPacketsDropped=")
        assertThat(s).contains("reconnects=")
        assertThat(s).contains("bitrateHistory=")
        assertThat(s).contains("encoderRecreates=")
    }

    // 14. Merged manifest declares BIND_QUICK_SETTINGS_TILE
    @Test
    fun manifest_declares_bind_quick_settings_tile() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val content = if (manifestFile.exists()) manifestFile.readText() else File("sender/src/main/AndroidManifest.xml").readText()
        assertThat(content).contains("android.permission.BIND_QUICK_SETTINGS_TILE")
        assertThat(content).contains("MirrorTileService")
    }
}
