package com.phonemirror.sender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MirrorService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val consentFlow = ConsentFlow()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val tvName = intent.getStringExtra(EXTRA_TV_NAME) ?: "TV"

        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // STEP 1 & 2: Consent was requested and granted
        consentFlow.requestConsent(System.currentTimeMillis())
        consentFlow.onConsentGranted(consentFlow.currentSessionId)

        // STEP 3: Start foreground service BEFORE getMediaProjection
        val notification = buildNotification(tvName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        consentFlow.onForegroundServiceStarted()

        // STEP 4: getMediaProjection inside service
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection
        consentFlow.onMediaProjectionAcquired()

        // STEP 5: registerCallback BEFORE createVirtualDisplay
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    consentFlow.onRevoked()
                    ProjectionHolder.setRevoked()
                    teardown()
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper())
        )
        consentFlow.onCallbackRegistered()

        // STEP 6: Expose projection
        ProjectionHolder.setActive(projection)

        return START_NOT_STICKY
    }

    private fun teardown() {
        consentFlow.onStop()
        ProjectionHolder.setStopped()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mirroring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screen mirroring notification"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(tvName: String): Notification {
        val stopIntent = Intent(this, MirrorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneMirror")
            .setContentText("Mirroring to $tvName")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    companion object {
        const val CHANNEL_ID = "phonemirror_sender_mirroring"
        const val NOTIFICATION_ID = 34567
        const val ACTION_STOP = "com.phonemirror.sender.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TV_NAME = "extra_tv_name"
    }
}