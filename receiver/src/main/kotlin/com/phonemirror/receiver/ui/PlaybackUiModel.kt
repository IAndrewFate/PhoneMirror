package com.phonemirror.receiver.ui

import com.phonemirror.protocol.control.ControlMessage
import com.phonemirror.receiver.sync.AvSyncStats
import com.phonemirror.receiver.sync.SyncMode
import java.util.Locale

data class PlaybackStats(
    val renderedFps: Double = 0.0,
    val droppedFps: Double = 0.0,
    val avgOffsetMs: Double = 0.0,
    val mode: SyncMode = SyncMode.AUDIO_MASTER,
    val resolution: String = "1920x1080",
    val rttMs: Long = 0L,
    val currentJitterDelayMs: Long = 0L
) {
    fun formatOverlay(): String {
        val rttStr = if (rttMs > 0) "${rttMs}ms" else "--"
        val jitterStr = if (currentJitterDelayMs > 0) " (jitter +${currentJitterDelayMs}ms)" else ""
        return String.format(
            Locale.US,
            "Video: %s @ %.1f fps (drop %.1f)\nA/V Sync: %.1fms [%s]%s\nRTT: %s",
            resolution, renderedFps, droppedFps, avgOffsetMs, mode.name, jitterStr, rttStr
        )
    }
}

interface DispatcherController {
    fun sendStop()
    fun isRunning(): Boolean
}

class PlaybackController(
    private val dispatcherController: DispatcherController,
    private val onFinishActivity: () -> Unit
) {
    var isOverlayVisible: Boolean = false
        private set

    fun toggleOverlay(): Boolean {
        isOverlayVisible = !isOverlayVisible
        return isOverlayVisible
    }

    fun handleBackKey(): Boolean {
        dispatcherController.sendStop()
        onFinishActivity()
        return true
    }

    fun handleKey(keyCode: Int): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> handleBackKey()
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_MENU -> {
                toggleOverlay()
                true
            }
            else -> false // Other keys no-op per spec
        }
    }
}
