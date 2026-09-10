package com.phonemirror.receiver.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

    lateinit var presenter: StatusPresenter
        private set

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var tvIpPort: TextView
    private lateinit var tvSecondaryIps: TextView
    private lateinit var tvHint1: TextView
    private lateinit var tvHint2: TextView
    private lateinit var tvPin: TextView
    private lateinit var tvPairedCount: TextView

    private lateinit var btnRegenPin: Button
    private lateinit var btnClearPaired: Button
    private lateinit var btnToggleServer: Button
    private lateinit var btnExit: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ReceiverService.LocalBinder
            val s = binder.getService()
            receiverService = s
            isBound = true

            val state = s.uiState.value
            presenter.updateServerState(
                running = state.serverRunning,
                boundPort = state.boundPort,
                pin = state.pin,
                pairedCount = state.pairedCount
            )
            presenter.updateStreaming(state.isStreaming, state.connectedDevice)
            updateUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        presenter = StatusPresenter(
            initialPort = 47700,
            initialPin = "----",
            onRegeneratePin = {
                receiverService?.regeneratePin() ?: "----"
            },
            onClearPaired = {
                receiverService?.clearPairedDevices()
            },
            onToggleServer = { start ->
                if (start) {
                    receiverService?.startServer()
                } else {
                    receiverService?.stopServer()
                }
            }
        )
        presenter.setNetworkAddresses(getWlanIpv4Addresses())

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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(64, 48, 64, 48)
            setBackgroundColor(0xFF121212.toInt())
        }

        tvDeviceName = TextView(this).apply {
            text = "PhoneMirror TV  •  ${Build.MODEL}"
            textSize = 22f
            setTextColor(0xFF90A4AE.toInt())
            gravity = Gravity.CENTER
        }
        rootLayout.addView(tvDeviceName)

        tvStatus = TextView(this).apply {
            text = presenter.state.statusText
            textSize = 20f
            setTextColor(0xFF81C784.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }
        rootLayout.addView(tvStatus)

        // Large centered IP:port - primary visually dominant element for couch reading
        tvIpPort = TextView(this).apply {
            text = presenter.state.formattedIpPort
            textSize = 40f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(0xFF00E5FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 4)
        }
        rootLayout.addView(tvIpPort)

        tvSecondaryIps = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF78909C.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        rootLayout.addView(tvSecondaryIps)

        // Hints
        tvHint1 = TextView(this).apply {
            text = "Open PhoneMirror on your phone - it will find this TV automatically"
            textSize = 18f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        }
        rootLayout.addView(tvHint1)

        tvHint2 = TextView(this).apply {
            text = "or enter the IP and port above manually"
            textSize = 18f
            setTextColor(0xFF78909C.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(tvHint2)

        // PIN display (>= 48sp)
        tvPin = TextView(this).apply {
            text = "PIN: ${presenter.state.pin}"
            textSize = 52f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(0xFFFFD600.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 8)
        }
        rootLayout.addView(tvPin)

        tvPairedCount = TextView(this).apply {
            text = "Paired devices: ${presenter.state.pairedCount}"
            textSize = 18f
            setTextColor(0xFF90A4AE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        rootLayout.addView(tvPairedCount)

        // Buttons layout (D-pad focus order: [PIN regenerate] -> [clear paired devices] -> [server on/off] -> [exit])
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        btnRegenPin = createTvButton("PIN regenerate").apply {
            setOnClickListener {
                presenter.triggerRegeneratePin()
                updateUi()
            }
        }
        buttonRow.addView(btnRegenPin)

        btnClearPaired = createTvButton("Clear paired devices").apply {
            setOnClickListener {
                showClearPairedConfirmationDialog()
            }
        }
        buttonRow.addView(btnClearPaired)

        btnToggleServer = createTvButton("Stop Server").apply {
            setOnClickListener {
                presenter.toggleServer()
                updateUi()
            }
        }
        buttonRow.addView(btnToggleServer)

        btnExit = createTvButton("Exit").apply {
            setOnClickListener {
                finish()
            }
        }
        buttonRow.addView(btnExit)

        rootLayout.addView(buttonRow)

        setContentView(rootLayout)
        btnRegenPin.requestFocus()
    }

    private fun createTvButton(title: String): Button {
        val btn = Button(this).apply {
            text = title
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF263238.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(28, 16, 28, 16)
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(16, 0, 16, 0)
        }
        btn.layoutParams = params

        // Focus scale and highlight for 10-foot TV experience
        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.scaleX = 1.08f
                v.scaleY = 1.08f
                v.setBackgroundColor(0xFF00E5FF.toInt())
                (v as? Button)?.setTextColor(0xFF000000.toInt())
            } else {
                v.scaleX = 1.0f
                v.scaleY = 1.0f
                v.setBackgroundColor(0xFF263238.toInt())
                (v as? Button)?.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        return btn
    }

    private fun showClearPairedConfirmationDialog() {
        presenter.requestClearPaired()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Clear Paired Devices?")
            .setMessage("This will remove all trusted phones. They will need to re-enter the PIN on the next connection.")
            .setPositiveButton("Clear") { _, _ ->
                presenter.confirmClearPaired()
                updateUi()
            }
            .setNegativeButton("Cancel") { _, _ ->
                presenter.dismissClearPairedDialog()
            }
            .setOnDismissListener {
                presenter.dismissClearPairedDialog()
            }
            .create()

        dialog.show()
        // Default focus on Cancel per user-review fold to prevent accidental D-pad mispress
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    fun updateUi() {
        val s = receiverService
        if (s != null) {
            val sState = s.uiState.value
            presenter.updateServerState(
                running = sState.serverRunning,
                boundPort = sState.boundPort,
                pin = sState.pin,
                pairedCount = sState.pairedCount
            )
            presenter.updateStreaming(sState.isStreaming, sState.connectedDevice)
        }
        presenter.setNetworkAddresses(getWlanIpv4Addresses())

        val state = presenter.state
        tvStatus.text = state.statusText
        tvIpPort.text = state.formattedIpPort
        tvPin.text = "PIN: ${state.pin}"
        tvPairedCount.text = "Paired devices: ${state.pairedCount}"

        if (state.secondaryIps.isNotEmpty()) {
            tvSecondaryIps.text = "Other IPs: ${state.secondaryIps.joinToString(", ")}"
            tvSecondaryIps.visibility = View.VISIBLE
        } else {
            tvSecondaryIps.visibility = View.GONE
        }

        btnToggleServer.text = if (state.serverRunning) "Stop Server" else "Start Server"
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

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}
