package com.phonemirror.protocol.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioFrameBody(
    val ptsUs: Long,
    val packet: ByteArray
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(8 + packet.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(ptsUs)
        buffer.put(packet)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioFrameBody
        if (ptsUs != other.ptsUs) return false
        if (!packet.contentEquals(other.packet)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + packet.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): AudioFrameBody {
            if (bytes.size < 9) {
                throw ProtocolException("AudioFrameBody too short: ${bytes.size} bytes (minimum 9 with non-empty packet)")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val ptsUs = buffer.getLong()
            val packet = ByteArray(bytes.size - 8)
            buffer.get(packet)
            if (packet.isEmpty()) {
                throw ProtocolException("AudioFrameBody packet cannot be empty")
            }
            return AudioFrameBody(ptsUs, packet)
        }
    }
}