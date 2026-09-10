package com.phonemirror.protocol.wire

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VideoFrameBody(
    val ptsUs: Long,
    val keyframe: Boolean,
    val nal: ByteArray
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(12 + nal.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(ptsUs)
        buffer.putInt(if (keyframe) 1 else 0)
        buffer.put(nal)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VideoFrameBody
        if (ptsUs != other.ptsUs) return false
        if (keyframe != other.keyframe) return false
        if (!nal.contentEquals(other.nal)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + keyframe.hashCode()
        result = 31 * result + nal.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): VideoFrameBody {
            if (bytes.size < 13) {
                throw ProtocolException("VideoFrameBody too short: ${bytes.size} bytes (minimum 13 with non-empty NAL)")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val ptsUs = buffer.getLong()
            val flags = buffer.getInt()
            val keyframe = (flags and 1) != 0
            val nal = ByteArray(bytes.size - 12)
            buffer.get(nal)
            if (nal.isEmpty()) {
                throw ProtocolException("VideoFrameBody NAL cannot be empty")
            }
            return VideoFrameBody(ptsUs, keyframe, nal)
        }
    }
}