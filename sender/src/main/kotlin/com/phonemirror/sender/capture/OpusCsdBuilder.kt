package com.phonemirror.sender.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

object OpusCsdBuilder {

    /**
     * Builds RFC 7845 OpusHead (19 bytes):
     * - 'OpusHead' (8 bytes)
     * - version = 1 (1 byte)
     * - channels = 2 (1 byte)
     * - pre-skip = 312 (2 bytes little endian)
     * - sample rate = 48000 (4 bytes little endian)
     * - output gain = 0 (2 bytes little endian)
     * - mapping family = 0 (1 byte)
     */
    fun buildOpusHead(sampleRate: Int = 48000, channels: Int = 2, preSkip: Int = 312): ByteArray {
        val buffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("OpusHead".toByteArray(Charsets.US_ASCII))
        buffer.put(1.toByte())
        buffer.put(channels.toByte())
        buffer.putShort(preSkip.toShort())
        buffer.putInt(sampleRate)
        buffer.putShort(0.toShort())
        buffer.put(0.toByte())
        return buffer.array()
    }

    /**
     * Builds csd-1 (codec delay in nanoseconds, 8 bytes little endian)
     */
    fun buildCodecDelay(delayNs: Long = 6_500_000L): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(delayNs)
        return buffer.array()
    }

    /**
     * Builds csd-2 (seek pre-roll in nanoseconds, 8 bytes little endian: 80ms standard)
     */
    fun buildSeekPreRoll(preRollNs: Long = 80_000_000L): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putLong(preRollNs)
        return buffer.array()
    }
}