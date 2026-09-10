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
}

class InMemoryEndpointStore(
    private var deviceId: String = "test-device-uuid"
) : EndpointStore {
    private var saved: DiscoveredDevice? = null

    override fun saveEndpoint(host: String, port: Int, name: String) {
        saved = DiscoveredDevice(name, host, port)
    }

    override fun loadEndpoint(): DiscoveredDevice? = saved

    override fun getDeviceId(): String = deviceId
}