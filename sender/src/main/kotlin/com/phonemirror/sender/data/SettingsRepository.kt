package com.phonemirror.sender.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MirrorSettings(
    val bitrateMbps: Int = 8,
    val resolutionCap: String = "1080p",
    val fps: Int = 60,
    val audioEnabled: Boolean = true,
    val dimScreen: Boolean = false
) {
    init {
        require(bitrateMbps in 2..16) { "Bitrate must be between 2 and 16 Mbps, was $bitrateMbps" }
        require(resolutionCap in listOf("1080p", "720p")) { "Resolution cap must be 1080p or 720p, was $resolutionCap" }
        require(fps in listOf(30, 60)) { "FPS must be 30 or 60, was $fps" }
    }
}

interface SettingsStore {
    fun load(): MirrorSettings
    fun save(settings: MirrorSettings)
}

class InMemorySettingsStore(initial: MirrorSettings = MirrorSettings()) : SettingsStore {
    private var current = initial
    override fun load(): MirrorSettings = current
    override fun save(settings: MirrorSettings) {
        current = settings
    }
}

class SharedPreferencesSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("mirror_settings", Context.MODE_PRIVATE)

    override fun load(): MirrorSettings {
        val bitrate = prefs.getInt(KEY_BITRATE, 8).coerceIn(2, 16)
        val resCap = prefs.getString(KEY_RESOLUTION_CAP, "1080p") ?: "1080p"
        val fps = prefs.getInt(KEY_FPS, 60).let { if (it in listOf(30, 60)) it else 60 }
        val audio = prefs.getBoolean(KEY_AUDIO_ENABLED, true)
        val dim = prefs.getBoolean(KEY_DIM_SCREEN, false)
        return MirrorSettings(
            bitrateMbps = bitrate,
            resolutionCap = if (resCap in listOf("1080p", "720p")) resCap else "1080p",
            fps = fps,
            audioEnabled = audio,
            dimScreen = dim
        )
    }

    override fun save(settings: MirrorSettings) {
        prefs.edit()
            .putInt(KEY_BITRATE, settings.bitrateMbps)
            .putString(KEY_RESOLUTION_CAP, settings.resolutionCap)
            .putInt(KEY_FPS, settings.fps)
            .putBoolean(KEY_AUDIO_ENABLED, settings.audioEnabled)
            .putBoolean(KEY_DIM_SCREEN, settings.dimScreen)
            .apply()
    }

    companion object {
        private const val KEY_BITRATE = "bitrate_mbps"
        private const val KEY_RESOLUTION_CAP = "resolution_cap"
        private const val KEY_FPS = "fps"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_DIM_SCREEN = "dim_screen"
    }
}

class SettingsRepository(
    private val store: SettingsStore = InMemorySettingsStore()
) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<MirrorSettings> = _settings.asStateFlow()

    fun updateBitrate(bitrateMbps: Int) {
        val clamped = bitrateMbps.coerceIn(2, 16)
        val updated = _settings.value.copy(bitrateMbps = clamped)
        _settings.value = updated
        store.save(updated)
    }

    fun updateResolutionCap(cap: String) {
        if (cap in listOf("1080p", "720p")) {
            val updated = _settings.value.copy(resolutionCap = cap)
            _settings.value = updated
            store.save(updated)
        }
    }

    fun updateFps(fps: Int) {
        if (fps in listOf(30, 60)) {
            val updated = _settings.value.copy(fps = fps)
            _settings.value = updated
            store.save(updated)
        }
    }

    fun updateAudioEnabled(enabled: Boolean) {
        val updated = _settings.value.copy(audioEnabled = enabled)
        _settings.value = updated
        store.save(updated)
    }

    fun updateDimScreen(dim: Boolean) {
        val updated = _settings.value.copy(dimScreen = dim)
        _settings.value = updated
        store.save(updated)
    }
}
