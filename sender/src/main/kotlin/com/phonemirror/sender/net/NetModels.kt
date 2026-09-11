package com.phonemirror.sender.net

import com.phonemirror.protocol.control.ErrorReason
import com.phonemirror.protocol.wire.Frame

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int
)

sealed class ClientState {
    data object Idle : ClientState()
    data class Connecting(val host: String, val port: Int) : ClientState()
    data class PairingFailure(val reason: ErrorReason) : ClientState()
    data class Connected(val host: String, val port: Int) : ClientState()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int = 10) : ClientState()
    data object Failed : ClientState()
}

interface OverflowPolicy {
    fun shouldAcceptFrame(frame: Frame, currentQueueBytes: Long, capacityBytes: Long): Boolean
    fun onCongestion()

    fun handleEnqueue(
        frame: Frame,
        queue: java.util.ArrayDeque<Frame>,
        currentQueueBytes: java.util.concurrent.atomic.AtomicLong,
        capacityBytes: Long
    ): Boolean {
        val current = currentQueueBytes.get()
        if (!shouldAcceptFrame(frame, current, capacityBytes)) {
            onCongestion()
            return false
        }
        currentQueueBytes.addAndGet(frame.body.size.toLong())
        queue.addLast(frame)
        return true
    }
}

class DropOldestAudioOverflowPolicy : OverflowPolicy {
    var congestionSignalCount: Int = 0
        private set

    override fun shouldAcceptFrame(frame: Frame, currentQueueBytes: Long, capacityBytes: Long): Boolean {
        return currentQueueBytes + frame.body.size <= capacityBytes
    }

    override fun onCongestion() {
        congestionSignalCount++
    }
}

interface EndpointStore {
    fun saveEndpoint(host: String, port: Int, name: String)
    fun loadEndpoint(): DiscoveredDevice?
    fun getDeviceId(): String
    fun savePin(pin: String) {}
    fun loadPin(): String = ""
}

class InMemoryEndpointStore(
    private var deviceId: String = "test-device-uuid"
) : EndpointStore {
    private var saved: DiscoveredDevice? = null
    private var savedPin: String = ""

    override fun saveEndpoint(host: String, port: Int, name: String) {
        saved = DiscoveredDevice(name, host, port)
    }

    override fun loadEndpoint(): DiscoveredDevice? = saved

    override fun getDeviceId(): String = deviceId

    override fun savePin(pin: String) {
        savedPin = pin
    }

    override fun loadPin(): String = savedPin
}

class SharedPreferencesEndpointStore(
    context: android.content.Context
) : EndpointStore {
    private val prefs = context.getSharedPreferences("mirror_endpoints", android.content.Context.MODE_PRIVATE)

    override fun saveEndpoint(host: String, port: Int, name: String) {
        prefs.edit()
            .putString("last_host", host)
            .putInt("last_port", port)
            .putString("last_name", name)
            .apply()
    }

    override fun loadEndpoint(): DiscoveredDevice? {
        val host = prefs.getString("last_host", null) ?: return null
        val port = prefs.getInt("last_port", 47700)
        val name = prefs.getString("last_name", "Android TV") ?: "Android TV"
        return DiscoveredDevice(name, host, port)
    }

    override fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    override fun savePin(pin: String) {
        prefs.edit().putString("last_pin", pin).apply()
    }

    override fun loadPin(): String {
        return prefs.getString("last_pin", "") ?: ""
    }
}