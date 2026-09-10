package com.phonemirror.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.phonemirror.receiver.server.DefaultPinProvider
import com.phonemirror.receiver.server.InMemoryPairingStore
import com.phonemirror.receiver.server.MirrorServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReceiverUiState(
    val boundPort: Int = 0,
    val pin: String = "",
    val isStreaming: Boolean = false,
    val pairedCount: Int = 0,
    val connectedDevice: String? = null
)

class ReceiverService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pinProvider = DefaultPinProvider()
    val pairingStore = InMemoryPairingStore()

    lateinit var mirrorServer: MirrorServer
        private set

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _uiState = MutableStateFlow(ReceiverUiState())
    val uiState: StateFlow<ReceiverUiState> = _uiState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): ReceiverService = this@ReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mirrorServer = MirrorServer(
            pinProvider = pinProvider,
            pairingStore = pairingStore,
            onStreamingChanged = { streaming, deviceName ->
                _uiState.value = _uiState.value.copy(
                    isStreaming = streaming,
                    connectedDevice = deviceName,
                    pairedCount = pairingStore.pairedCount
                )
                updateNotification(streaming, deviceName)
            }
        )

        try {
            val port = mirrorServer.bind()
            _uiState.value = _uiState.value.copy(
                boundPort = port,
                pin = pinProvider.current,
                pairedCount = pairingStore.pairedCount
            )
            mirrorServer.start(scope)
            registerNsd(port)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val notification = buildNotification(false, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerNsd(port: Int) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("PhoneMirrorMulticast")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PhoneMirror ${Build.MODEL}"
            serviceType = "_phonemirror._tcp."
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mainExecutor, registrationListener!!)
            } else {
                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            }
        } catch (e: Exception) {
            // NSD failure is non-fatal: manual IP exists
            e.printStackTrace()
        }
    }

    private fun unregisterNsd() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        registrationListener = null
        try {
            multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhoneMirror TV Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhoneMirror TV background receiver service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isStreaming: Boolean, device: String?): Notification {
        val contentText = if (isStreaming) {
            "Streaming from ${device ?: "device"}"
        } else {
            "Waiting for phone connection (Port ${_uiState.value.boundPort}, PIN ${_uiState.value.pin})"
        }

        val stopIntent = Intent(this, ReceiverService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhoneMirror TV")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(isStreaming: Boolean, device: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(isStreaming, device))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun regeneratePin(): String {
        val newPin = pinProvider.regenerate()
        _uiState.value = _uiState.value.copy(pin = newPin)
        updateNotification(_uiState.value.isStreaming, _uiState.value.connectedDevice)
        return newPin
    }

    fun clearPairedDevices() {
        pairingStore.clearTrusted()
        _uiState.value = _uiState.value.copy(pairedCount = 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNsd()
        mirrorServer.stop()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "phonemirror_receiver_channel"
        const val NOTIFICATION_ID = 47701
        const val ACTION_STOP = "com.phonemirror.receiver.ACTION_STOP"
    }
}