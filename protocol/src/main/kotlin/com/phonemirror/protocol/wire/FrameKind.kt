package com.phonemirror.protocol.wire

enum class FrameKind(val id: Int) {
    CONTROL(0x01),
    VIDEO_CONFIG(0x02),
    VIDEO_FRAME(0x03),
    AUDIO_CONFIG(0x04),
    AUDIO_FRAME(0x05);

    companion object {
        fun fromId(id: Int): FrameKind = when (id) {
            0x01 -> CONTROL
            0x02 -> VIDEO_CONFIG
            0x03 -> VIDEO_FRAME
            0x04 -> AUDIO_CONFIG
            0x05 -> AUDIO_FRAME
            else -> throw ProtocolException("Unknown frame kind id: $id")
        }
    }
}