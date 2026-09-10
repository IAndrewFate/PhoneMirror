package com.phonemirror.sender.capture

import android.view.Surface

interface VideoEncoder {
    val inputSurface: Surface?
    fun configure(width: Int, height: Int, settings: VideoSettings)
    fun start()
    fun stop()
    fun release()
    fun requestKeyframe()
    fun setBitrate(bitrateBps: Int)
    fun dequeueOutput(timeoutUs: Long): CodecOutput?
    fun releaseOutputBuffer(index: Int)
}

sealed class CodecOutput {
    data class Config(val data: ByteArray) : CodecOutput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Config) return false
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Frame(
        val data: ByteArray,
        val ptsUs: Long,
        val isKeyframe: Boolean,
        val bufferIndex: Int
    ) : CodecOutput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return ptsUs == other.ptsUs && isKeyframe == other.isKeyframe &&
                    bufferIndex == other.bufferIndex && data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + ptsUs.hashCode()
            result = 31 * result + isKeyframe.hashCode()
            result = 31 * result + bufferIndex
            return result
        }
    }

    data object TryAgainLater : CodecOutput()
}

interface VideoEncoderFactory {
    fun createEncoder(): VideoEncoder
}