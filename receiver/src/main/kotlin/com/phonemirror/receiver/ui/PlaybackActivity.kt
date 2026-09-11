package com.phonemirror.receiver.ui

import android.app.Activity
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.phonemirror.protocol.control.ControlMessage
import com.phonemirror.receiver.decode.LetterboxCalculator
import com.phonemirror.receiver.decode.VideoDecodeState
import com.phonemirror.receiver.service.ReceiverSessionHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: TextView
    private lateinit var waitingView: TextView

    private var wifiLock: WifiManager.WifiLock? = null
    private var statsJob: Job? = null
    private var stateJob: Job? = null
    private val activityScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private var lastRenderedCount = 0L
    private var lastDroppedCount = 0L
    private var lastTimeMs = System.currentTimeMillis()

    lateinit var playbackController: PlaybackController
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen and Keep Screen On flags
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        playbackController = PlaybackController(
            dispatcherController = object : DispatcherController {
                override fun sendStop() {
                    ReceiverSessionHolder.activeServer?.sendStop()
                }
                override fun isRunning(): Boolean {
                    return ReceiverSessionHolder.activeServer?.hasActiveClient() == true
                }
            },
            onFinishActivity = {
                finish()
            }
        )

        container = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }

        surfaceView = SurfaceView(this).apply {
            keepScreenOn = true // User-review fold: keep-screen-on on SurfaceView itself prevents Daydream/screensaver teardown
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    ReceiverSessionHolder.videoPipeline?.setSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    updateSurfaceLayout(width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    ReceiverSessionHolder.videoPipeline?.onSurfaceDestroyed()
                }
            })
        }
        container.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        // Waiting message displayed while awaiting first video frame
        waitingView = TextView(this).apply {
            text = "PhoneMirror TV\n\nОжидание видеопотока с телефона..."
            textSize = 22f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            visibility = View.VISIBLE
        }
        container.addView(
            waitingView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // Overlay is stacked ABOVE the SurfaceView in the window UI layer
        overlayView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC111111.toInt())
            setPadding(32, 24, 32, 24)
            visibility = View.GONE
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val overlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(48, 48, 0, 0)
        }
        container.addView(overlayView, overlayParams)

        setContentView(container)

        observePipeline()
    }

    private fun updateSurfaceLayout(containerW: Int, containerH: Int) {
        val pipeline = ReceiverSessionHolder.videoPipeline ?: return
        val w = if (pipeline.currentWidth > 0) pipeline.currentWidth else 1920
        val h = if (pipeline.currentHeight > 0) pipeline.currentHeight else 1080
        if (containerW <= 0 || containerH <= 0) return

        val rect = LetterboxCalculator.letterboxRect(w, h, containerW, containerH)
        val params = surfaceView.layoutParams as FrameLayout.LayoutParams
        params.width = rect.width
        params.height = rect.height
        params.leftMargin = rect.x
        params.topMargin = rect.y
        params.gravity = Gravity.TOP or Gravity.START
        surfaceView.layoutParams = params
    }

    private fun observePipeline() {
        val pipeline = ReceiverSessionHolder.videoPipeline
        if (pipeline != null) {
            stateJob = activityScope.launch {
                pipeline.state.collect { state ->
                    when (state) {
                        is VideoDecodeState.Decoding -> {
                            waitingView.visibility = View.GONE
                            if (container.width > 0 && container.height > 0) {
                                updateSurfaceLayout(container.width, container.height)
                            }
                        }
                        is VideoDecodeState.Error -> {
                            waitingView.text = "Ошибка декодирования видео:\n${state.message}\n\nОжидание ключевого кадра..."
                            waitingView.visibility = View.VISIBLE
                            ReceiverSessionHolder.activeDispatcher?.sendControl(ControlMessage.RequestKeyframe())
                        }
                        else -> {}
                    }
                }
            }
        }

        statsJob = activityScope.launch {
            while (isActive) {
                delay(500)
                if ((ReceiverSessionHolder.videoPipeline?.renderedFrameCount ?: 0L) > 0) {
                    if (waitingView.visibility == View.VISIBLE && pipeline?.state?.value !is VideoDecodeState.Error) {
                        waitingView.visibility = View.GONE
                    }
                }
                if (playbackController.isOverlayVisible) {
                    updateOverlay()
                }
            }
        }
    }

    private fun updateOverlay() {
        val pipeline = ReceiverSessionHolder.videoPipeline
        val avSync = ReceiverSessionHolder.avSyncEngine
        val now = System.currentTimeMillis()
        val dt = (now - lastTimeMs).coerceAtLeast(1) / 1000.0

        val currentRendered = pipeline?.renderedFrameCount ?: 0L
        val currentDropped = pipeline?.droppedFrameCount ?: 0L
        val renderedFps = ((currentRendered - lastRenderedCount) / dt).coerceAtLeast(0.0)
        val droppedFps = ((currentDropped - lastDroppedCount) / dt).coerceAtLeast(0.0)

        lastRenderedCount = currentRendered
        lastDroppedCount = currentDropped
        lastTimeMs = now

        val avStats = avSync?.stats?.value
        val res = if (pipeline != null && pipeline.currentWidth > 0) {
            "${pipeline.currentWidth}x${pipeline.currentHeight}"
        } else "1920x1080"

        val stats = PlaybackStats(
            renderedFps = renderedFps,
            droppedFps = droppedFps,
            avgOffsetMs = avStats?.avgOffsetMs ?: 0.0,
            mode = avStats?.mode ?: com.phonemirror.receiver.sync.SyncMode.AUDIO_MASTER,
            resolution = res,
            rttMs = ReceiverSessionHolder.rttMs.value,
            currentJitterDelayMs = avStats?.currentJitterDelayMs ?: 0L
        )

        overlayView.text = stats.formatOverlay()
        overlayView.visibility = View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        wifiLock = ReceiverWifiLockHelper.acquireWifiLock(this)
    }

    override fun onStop() {
        super.onStop()
        try {
            wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (playbackController.handleKey(keyCode)) {
            if (playbackController.isOverlayVisible) {
                updateOverlay()
            } else {
                overlayView.visibility = View.GONE
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        stateJob?.cancel()
        statsJob?.cancel()
        activityScope.cancel()
        ReceiverSessionHolder.videoPipeline?.setSurface(null)
    }
}
