package com.phonemirror.receiver.decode

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

class AndroidAudioDecoder : AudioDecoder {
    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    override fun configure(format: AudioDecoderFormat) {
        val mediaFormat = MediaFormat.createAudioFormat(format.mime, format.sampleRate, format.channelCount)
        format.csdBuffers.forEachIndexed { index, bytes ->
            mediaFormat.setByteBuffer("csd-" + index, ByteBuffer.wrap(bytes))
        }
        val dec = MediaCodec.createDecoderByType(format.mime)
        dec.configure(mediaFormat, null, null, 0)
        codec = dec
    }

    override fun start() {
        codec?.start()
    }

    override fun stop() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
    }

    override fun flush() {
        try {
            codec?.flush()
        } catch (_: Exception) {}
    }

    override fun release() {
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }

    override fun queueInput(data: ByteArray, ptsUs: Long): Boolean {
        val dec = codec ?: return false
        val index = dec.dequeueInputBuffer(10_000L)
        if (index < 0) return false
        val buf = dec.getInputBuffer(index) ?: return false
        buf.clear()
        buf.put(data)
        dec.queueInputBuffer(index, 0, data.size, ptsUs, 0)
        return true
    }

    override fun dequeueOutput(timeoutUs: Long): AudioDecoderOutput? {
        val dec = codec ?: return null
        val index = dec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        return when {
            index >= 0 -> {
                val buf = dec.getOutputBuffer(index)
                val pcm = if (buf != null && bufferInfo.size > 0) {
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    val bytes = ByteArray(bufferInfo.size)
                    buf.get(bytes)
                    bytes
                } else {
                    ByteArray(0)
                }
                AudioDecoderOutput.Pcm(index, pcm, bufferInfo.presentationTimeUs)
            }
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = dec.outputFormat
                val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                AudioDecoderOutput.FormatChanged(sr, ch)
            }
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> AudioDecoderOutput.TryAgainLater
            else -> null
        }
    }

    override fun releaseOutputBuffer(index: Int) {
        codec?.releaseOutputBuffer(index, false)
    }
}
