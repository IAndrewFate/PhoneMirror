package com.phonemirror.receiver.server

import java.security.MessageDigest

object Defaults {
    const val BASE_PORT = 47700
    const val MAX_PORT = 47710
}

interface PinProvider {
    val current: String
    fun regenerate(): String
}

class DefaultPinProvider : PinProvider {
    private var _current: String = generate()
    override val current: String get() = _current

    override fun regenerate(): String {
        _current = generate()
        return _current
    }

    private fun generate(): String {
        return kotlin.random.Random.nextInt(1000, 10000).toString()
    }
}

interface PairingStore {
    fun isTrusted(deviceId: String): Boolean
    fun trust(deviceId: String)
    fun clearTrusted()
    val pairedCount: Int
}

class InMemoryPairingStore : PairingStore {
    private val trustedHashes = mutableSetOf<String>()

    override fun isTrusted(deviceId: String): Boolean {
        return trustedHashes.contains(hashDeviceId(deviceId))
    }

    override fun trust(deviceId: String) {
        trustedHashes.add(hashDeviceId(deviceId))
    }

    override fun clearTrusted() {
        trustedHashes.clear()
    }

    override val pairedCount: Int
        get() = trustedHashes.size
}

fun hashDeviceId(deviceId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(deviceId.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
}

interface VideoSink {
    fun onConfig(spsPps: ByteArray)
    fun onFrame(ptsUs: Long, keyframe: Boolean, nal: ByteArray)
    fun onResolutionChange(w: Int, h: Int, dpi: Int)
}

class NoOpVideoSink : VideoSink {
    override fun onConfig(spsPps: ByteArray) {}
    override fun onFrame(ptsUs: Long, keyframe: Boolean, nal: ByteArray) {}
    override fun onResolutionChange(w: Int, h: Int, dpi: Int) {}
}

interface AudioSink {
    fun onConfig(json: ByteArray)
    fun onFrame(ptsUs: Long, packet: ByteArray)
}

class NoOpAudioSink : AudioSink {
    override fun onConfig(json: ByteArray) {}
    override fun onFrame(ptsUs: Long, packet: ByteArray) {}
}