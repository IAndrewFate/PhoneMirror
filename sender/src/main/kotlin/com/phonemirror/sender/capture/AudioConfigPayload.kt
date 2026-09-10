package com.phonemirror.sender.capture

import kotlinx.serialization.Serializable

@Serializable
data class AudioConfigPayload(
    val codec: String,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val frameMs: Int = 20,
    val bitrate: Int = 128_000,
    val csd: List<String> = emptyList()
)