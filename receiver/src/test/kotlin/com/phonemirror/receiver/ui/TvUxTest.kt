package com.phonemirror.receiver.ui

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.phonemirror.receiver.sync.SyncMode
import org.junit.Test

class TvUxTest {

    // 1. focus-order model
    @Test
    fun testFocusOrder() {
        val presenter = StatusPresenter()
        val expectedOrder = listOf(
            StatusAction.REGENERATE_PIN,
            StatusAction.CLEAR_PAIRED_DEVICES,
            StatusAction.TOGGLE_SERVER,
            StatusAction.EXIT
        )
        assertThat(presenter.focusOrder).isEqualTo(expectedOrder)

        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.REGENERATE_PIN)

        // Step forward through entire order
        presenter.moveFocusNext()
        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.CLEAR_PAIRED_DEVICES)

        presenter.moveFocusNext()
        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.TOGGLE_SERVER)

        presenter.moveFocusNext()
        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.EXIT)

        // Wrap around to start
        presenter.moveFocusNext()
        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.REGENERATE_PIN)

        // Step backwards
        presenter.moveFocusPrevious()
        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.EXIT)

        presenter.moveFocusPrevious()
        assertThat(presenter.state.focusedAction).isEqualTo(StatusAction.TOGGLE_SERVER)
    }

    // 2. waiting->streaming->stopped->waiting transitions
    @Test
    fun testWaitingStreamingStoppedWaitingTransitions() {
        var serverStarted = true
        val presenter = StatusPresenter(
            onToggleServer = { running -> serverStarted = running }
        )

        // 1. Initial Waiting state
        assertThat(presenter.state.serverRunning).isTrue()
        assertThat(presenter.state.isStreaming).isFalse()
        assertThat(presenter.state.statusText).isEqualTo("Status: Waiting for phone connection...")

        // 2. Client connects -> Streaming
        presenter.updateStreaming(true, "Pixel 8 Pro")
        assertThat(presenter.state.isStreaming).isTrue()
        assertThat(presenter.state.connectedDevice).isEqualTo("Pixel 8 Pro")
        assertThat(presenter.state.statusText).isEqualTo("Status: Streaming (Pixel 8 Pro)")

        // 3. Client disconnects -> Back to Waiting
        presenter.updateStreaming(false, null)
        assertThat(presenter.state.isStreaming).isFalse()
        assertThat(presenter.state.connectedDevice).isNull()
        assertThat(presenter.state.statusText).isEqualTo("Status: Waiting for phone connection...")

        // 4. Server stopped by user -> Stopped
        presenter.toggleServer()
        assertThat(serverStarted).isFalse()
        assertThat(presenter.state.serverRunning).isFalse()
        assertThat(presenter.state.statusText).isEqualTo("Status: Server stopped")

        // 5. Server restarted by user -> Back to Waiting
        presenter.toggleServer()
        assertThat(serverStarted).isTrue()
        assertThat(presenter.state.serverRunning).isTrue()
        assertThat(presenter.state.statusText).isEqualTo("Status: Waiting for phone connection...")
    }

    // 3. overlay formatting
    @Test
    fun testOverlayFormatting() {
        val statsWithJitter = PlaybackStats(
            renderedFps = 59.8,
            droppedFps = 0.2,
            avgOffsetMs = -4.5,
            mode = SyncMode.AUDIO_MASTER,
            resolution = "1920x1080",
            rttMs = 14L,
            currentJitterDelayMs = 40L
        )
        val formatted = statsWithJitter.formatOverlay()
        assertThat(formatted).contains("1920x1080")
        assertThat(formatted).contains("59.8 fps")
        assertThat(formatted).contains("drop 0.2")
        assertThat(formatted).contains("-4.5ms [AUDIO_MASTER]")
        assertThat(formatted).contains("(jitter +40ms)")
        assertThat(formatted).contains("RTT: 14ms")

        val statsVideoOnlyNoRtt = PlaybackStats(
            renderedFps = 30.0,
            droppedFps = 0.0,
            avgOffsetMs = 0.0,
            mode = SyncMode.VIDEO_IMMEDIATE,
            resolution = "1280x720",
            rttMs = 0L,
            currentJitterDelayMs = 0L
        )
        val formattedVideoOnly = statsVideoOnlyNoRtt.formatOverlay()
        assertThat(formattedVideoOnly).contains("1280x720")
        assertThat(formattedVideoOnly).contains("[VIDEO_IMMEDIATE]")
        assertThat(formattedVideoOnly).doesNotContain("jitter")
        assertThat(formattedVideoOnly).contains("RTT: --")
    }

    // 4. WifiLock mode matrix (injected SDK)
    @Test
    fun testWifiLockModeMatrix() {
        // Android 10+ (SDK >= 29): WIFI_MODE_FULL_LOW_LATENCY (4)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(29)).isEqualTo(4)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(30)).isEqualTo(4)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(33)).isEqualTo(4)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(34)).isEqualTo(4)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(35)).isEqualTo(4)

        // Android 8 / 9 (SDK < 29): WIFI_MODE_FULL_HIGH_PERF (3)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(26)).isEqualTo(3)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(27)).isEqualTo(3)
        assertThat(ReceiverWifiLockHelper.getWifiLockMode(28)).isEqualTo(3)
    }

    // 5. BACK-sends-Stop with fake dispatcher
    @Test
    fun testBackSendsStopWithFakeDispatcher() {
        class FakeDispatcherController : DispatcherController {
            var stopCalled = false
            var running = true

            override fun sendStop() {
                stopCalled = true
                running = false
            }

            override fun isRunning(): Boolean = running
        }

        val fakeDispatcher = FakeDispatcherController()
        var finished = false
        val controller = PlaybackController(
            dispatcherController = fakeDispatcher,
            onFinishActivity = { finished = true }
        )

        // Other keys are no-ops
        val leftHandled = controller.handleKey(KeyEvent.KEYCODE_DPAD_LEFT)
        val rightHandled = controller.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(leftHandled).isFalse()
        assertThat(rightHandled).isFalse()
        assertThat(fakeDispatcher.stopCalled).isFalse()
        assertThat(finished).isFalse()

        // DPAD_CENTER / MENU toggles overlay
        assertThat(controller.isOverlayVisible).isFalse()
        val centerHandled = controller.handleKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(centerHandled).isTrue()
        assertThat(controller.isOverlayVisible).isTrue()

        val menuHandled = controller.handleKey(KeyEvent.KEYCODE_MENU)
        assertThat(menuHandled).isTrue()
        assertThat(controller.isOverlayVisible).isFalse()

        // BACK key sends stop and finishes activity
        val backHandled = controller.handleKey(KeyEvent.KEYCODE_BACK)
        assertThat(backHandled).isTrue()
        assertThat(fakeDispatcher.stopCalled).isTrue()
        assertThat(finished).isTrue()
    }

    // 6. IP:port presentation model (multiple interfaces -> primary first, actual port substituted)
    @Test
    fun testIpPortPresentationModel() {
        val rawAddresses = listOf(
            "127.0.0.1",
            "169.254.100.2",
            "10.0.0.45",
            "192.168.1.150",
            "172.20.10.3"
        )
        val sorted = StatusPresenter.selectPrimaryIp(rawAddresses)
        // 127.* and 169.254.* filtered out; 192.168.* ranked first
        assertThat(sorted).containsExactly(
            "192.168.1.150",
            "10.0.0.45",
            "172.20.10.3"
        ).inOrder()

        val presenter = StatusPresenter(initialPort = 47702)
        presenter.setNetworkAddresses(rawAddresses)

        assertThat(presenter.state.primaryIp).isEqualTo("192.168.1.150")
        assertThat(presenter.state.secondaryIps).containsExactly("10.0.0.45", "172.20.10.3")
        assertThat(presenter.state.formattedIpPort).isEqualTo("192.168.1.150:47702")

        // No valid IP fallback
        val emptyPresenter = StatusPresenter(initialPort = 47700)
        emptyPresenter.setNetworkAddresses(listOf("127.0.0.1"))
        assertThat(emptyPresenter.state.primaryIp).isEqualTo("No Wi-Fi")
        assertThat(emptyPresenter.state.formattedIpPort).isEqualTo("No Wi-Fi")
    }

    // 7. clear-paired confirmation model (Cancel = default focus, no store mutation until confirm)
    @Test
    fun testClearPairedConfirmationModel() {
        var clearCount = 0
        val presenter = StatusPresenter(
            onClearPaired = { clearCount++ }
        )
        presenter.updateServerState(running = true, boundPort = 47700, pin = "1234", pairedCount = 4)
        assertThat(presenter.state.pairedCount).isEqualTo(4)
        assertThat(presenter.state.isClearPairedDialogVisible).isFalse()

        // User clicks "Clear paired devices" -> dialog shown with Cancel as default focus
        presenter.requestClearPaired()
        assertThat(presenter.state.isClearPairedDialogVisible).isTrue()
        assertThat(presenter.state.dialogFocusedButton).isEqualTo(DialogButton.CANCEL)
        assertThat(clearCount).isEqualTo(0)
        assertThat(presenter.state.pairedCount).isEqualTo(4)

        // User cancels -> dismissed without clearing
        presenter.dismissClearPairedDialog()
        assertThat(presenter.state.isClearPairedDialogVisible).isFalse()
        assertThat(clearCount).isEqualTo(0)
        assertThat(presenter.state.pairedCount).isEqualTo(4)

        // User requests again and confirms -> store cleared
        presenter.requestClearPaired()
        presenter.confirmClearPaired()
        assertThat(clearCount).isEqualTo(1)
        assertThat(presenter.state.pairedCount).isEqualTo(0)
        assertThat(presenter.state.isClearPairedDialogVisible).isFalse()
    }

    // 8. QA failure scenario: decode ErrorState -> UI model returns Waiting with reason + server accepts new client
    @Test
    fun testDecodeErrorStateReturnsWaitingAndServerAcceptsNewClient() {
        val presenter = StatusPresenter()
        presenter.updateStreaming(true, "Device-A")
        assertThat(presenter.state.isStreaming).isTrue()

        // Decode failure triggers finish on playback and resets status presenter to Waiting
        presenter.updateStreaming(false, null)
        assertThat(presenter.state.isStreaming).isFalse()
        assertThat(presenter.state.statusText).isEqualTo("Status: Waiting for phone connection...")

        // Server accepts new connection
        presenter.updateStreaming(true, "Device-B")
        assertThat(presenter.state.isStreaming).isTrue()
        assertThat(presenter.state.connectedDevice).isEqualTo("Device-B")
        assertThat(presenter.state.statusText).isEqualTo("Status: Streaming (Device-B)")
    }
}
