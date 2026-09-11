package com.phonemirror.receiver.decode

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer

class AndroidVideoDecoder : VideoDecoder {
    private var codec: MediaCodec? = null

    override fun configure(format: VideoDecoderFormat, surface: Surface?) {
        val decoder = MediaCodec.createDecoderByType("video/avc")
        codec = decoder

        val targetSurface = if (surface != null && surface.isValid) surface else null

        val primaryFormat = MediaFormat.createVideoFormat("video/avc", format.width, format.height).apply {
            format.sps?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            format.pps?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }

            // Low-latency mode (API 30+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            } catch (_: Throwable) {}

            // Vendor key low-latency
            try {
                setInteger("low-latency", 1)
            } catch (_: Throwable) {}
        }

        try {
            decoder.configure(primaryFormat, targetSurface, null, 0)
        } catch (_: Throwable) {
            // Fallback: standard format without vendor/low-latency keys
            val fallbackFormat = MediaFormat.createVideoFormat("video/avc", format.width, format.height).apply {
                format.sps?.let { setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                format.pps?.let { setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
            decoder.configure(fallbackFormat, targetSurface, null, 0)
        }
    }

    override fun start() {
        codec?.start()
    }

    override fun stop() {
        try { codec?.stop() } catch (_: Throwable) {}
    }

    override fun flush() {
        try { codec?.flush() } catch (_: Throwable) {}
    }

    override fun release() {
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
    }

    override fun queueInput(data: ByteArray, ptsUs: Long, isKeyframe: Boolean): Boolean {
        val c = codec ?: return false
        val index = try {
            c.dequeueInputBuffer(10_000L)
        } catch (_: Throwable) {
            -1
        }
        if (index >= 0) {
            val buf = try { c.getInputBuffer(index) } catch (_: Throwable) { null } ?: return false
            buf.clear()
            buf.put(data)
            val flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            c.queueInputBuffer(index, 0, data.size, ptsUs, flags)
            return true
        }
        return false
    }

    override fun dequeueOutput(timeoutUs: Long): DecoderOutput? {
        val c = codec ?: return null
        val bufferInfo = MediaCodec.BufferInfo()
        val index = try {
            c.dequeueOutputBuffer(bufferInfo, timeoutUs)
        } catch (e: Throwable) {
            return DecoderOutput.Error(e.message ?: "Decoder error")
        }

        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return DecoderOutput.TryAgainLater
        }
        if (index >= 0) {
            val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            return DecoderOutput.Frame(
                bufferIndex = index,
                ptsUs = bufferInfo.presentationTimeUs,
                isKeyframe = isKeyframe
            )
        }
        return DecoderOutput.TryAgainLater
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean, renderTimestampNs: Long?) {
        val c = codec ?: return
        try {
            if (render && renderTimestampNs != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                c.releaseOutputBuffer(index, renderTimestampNs)
            } else {
                c.releaseOutputBuffer(index, render)
            }
        } catch (_: Throwable) {}
    }
}

object DefaultVideoDecoderFactory : VideoDecoderFactory {
    override fun createDecoder(): VideoDecoder = AndroidVideoDecoder()
}