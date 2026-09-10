package com.phonemirror.receiver.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.phonemirror.receiver.service.ReceiverService
import java.net.Inet4Address
import java.net.NetworkInterface

class StatusActivity : Activity() {

    private var receiverService: ReceiverService? = null
    private var isBound = false

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvPort: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvPairedCount: TextView
    private lateinit var btnRegenPin: Button
    private lateinit var btnClearPaired: Button
    private lateinit var btnExit: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ReceiverService.LocalBinder
            val s = binder.getService()
            receiverService = s
            isBound = true
            updateUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Start and bind service
        val serviceIntent = Intent(this, ReceiverService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        tvDeviceName = TextView(this).apply {
            text = "Device: ${Build.MODEL}"
            textSize = 24f
        }
        layout.addView(tvDeviceName)

        tvStatus = TextView(this).apply {
            text = "Status: Initializing..."
            textSize = 20f
        }
        layout.addView(tvStatus)

        tvIpAddress = TextView(this).apply {
            text = "IP: ${getWlanIpv4Addresses().joinToString(", ").ifEmpty { "No Wi-Fi" }}"
            textSize = 22f
        }
        layout.addView(tvIpAddress)

        tvPort = TextView(this).apply {
            text = "Port: 47700"
            textSize = 22f
        }
        layout.addView(tvPort)

        tvPin = TextView(this).apply {
            text = "PIN: ----"
            textSize = 48f
            setPadding(0, 24, 0, 24)
        }
        layout.addView(tvPin)

        tvPairedCount = TextView(this).apply {
            text = "Paired devices: 0"
            textSize = 18f
        }
        layout.addView(tvPairedCount)

        btnRegenPin = Button(this).apply {
            text = "Regenerate PIN"
            isFocusable = true
            setOnClickListener {
                receiverService?.regeneratePin()
                updateUi()
            }
        }
        layout.addView(btnRegenPin)

        btnClearPaired = Button(this).apply {
            text = "Clear Paired Devices"
            isFocusable = true
            setOnClickListener {
                receiverService?.clearPairedDevices()
                updateUi()
            }
        }
        layout.addView(btnClearPaired)

        btnExit = Button(this).apply {
            text = "Exit"
            isFocusable = true
            setOnClickListener {
                finish()
            }
        }
        layout.addView(btnExit)

        setContentView(layout)
    }

    private fun updateUi() {
        val s = receiverService ?: return
        val state = s.uiState.value
        tvStatus.text = if (state.isStreaming) "Status: Streaming (${state.connectedDevice ?: "connected"})" else "Status: Waiting for phone connection..."
        tvPort.text = "Port: ${state.boundPort}"
        tvPin.text = "PIN: ${state.pin}"
        tvPairedCount.text = "Paired devices: ${state.pairedCount}"
        tvIpAddress.text = "IP: ${getWlanIpv4Addresses().joinToString(", ").ifEmpty { "No Wi-Fi" }}"
    }

    private fun getWlanIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        result.add(addr.hostAddress)
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}