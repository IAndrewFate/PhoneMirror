package com.phonemirror.sender.capture

data class VideoSettings(
    val bitrate: Int = 8_000_000,
    val fps: Int = 60,
    val resolutionCap: Int = 1920,
    val iFrameIntervalSeconds: Int = 2
)