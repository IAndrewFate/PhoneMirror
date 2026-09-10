package com.phonemirror.sender.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface

/**
 * PTS BASE CONTRACT:
 * Video presentation timestamp (ptsUs) MUST BE BufferInfo.presentationTimeUs directly read
 * from the encoder output buffer - NEVER hand-stamped or overwritten.
 *
 * With surface input, the PTS is producer-assigned by the VirtualDisplay/Surface in the
 * System.nanoTime monotonic domain (converted to microseconds by the Android framework)
 * and cannot be overridden. Stamping at send time would inject encoder queue jitter into
 * the A/V synchronization offset. Audio capture (T10) maps its capture timestamps into
 * this exact same nanoTime/microsecond domain so the receiver can perform anchor-alignment.
 */
class AndroidVideoEncoder(
    private val mimeType: String = "video/avc"
) : VideoEncoder {

    private var codec: MediaCodec? = null
    override var inputSurface: Surface? = null
        private set

    override fun configure(width: Int, height: Int, settings: VideoSettings) {
        val encoder = MediaCodec.createEncoderByType(mimeType)
        codec = encoder

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

            // Baseline profile + Level
            try {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                val level = if (settings.fps > 30) {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42
                } else {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel41
                }
                setInteger(MediaFormat.KEY_LEVEL, level)
            } catch (_: Throwable) {}

            // KEY_LATENCY = 1 (API 26+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                }
            } catch (_: Throwable) {}

            // KEY_MAX_B_FRAMES = 0 (API 29+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            } catch (_: Throwable) {}

            try {
                setLong("repeat-previous-frame-after", 0L)
            } catch (_: Throwable) {}
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Throwable) {
            // Fallback: retry without profile/level keys if configure throws
            val fallbackFormat = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, settings.iFrameIntervalSeconds)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            encoder.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        inputSurface = encoder.createInputSurface()
    }

    override fun start() {
        codec?.start()
    }

    override fun stop() {
        try { codec?.stop() } catch (_: Throwable) {}
    }

    override fun release() {
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
    }

    override fun requestKeyframe() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1)
            }
            codec?.setParameters(params)
        } catch (_: Throwable) {}
    }

    override fun setBitrate(bitrateBps: Int) {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps)
            }
            codec?.setParameters(params)
        } catch (_: Throwable) {}
    }

    override fun dequeueOutput(timeoutUs: Long): CodecOutput? {
        val c = codec ?: return null
        val bufferInfo = MediaCodec.BufferInfo()
        val index = try {
            c.dequeueOutputBuffer(bufferInfo, timeoutUs)
        } catch (_: Throwable) {
            return null
        }

        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return CodecOutput.TryAgainLater
        }
        if (index >= 0) {
            val buf = try { c.getOutputBuffer(index) } catch (_: Throwable) { null }
            if (buf == null) {
                try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                return CodecOutput.TryAgainLater
            }

            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val bytes = ByteArray(bufferInfo.size)
            buf.position(bufferInfo.offset)
            buf.get(bytes, 0, bufferInfo.size)

            return if (isConfig) {
                try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                CodecOutput.Config(bytes)
            } else {
                CodecOutput.Frame(
                    data = bytes,
                    ptsUs = bufferInfo.presentationTimeUs,
                    isKeyframe = isKeyframe,
                    bufferIndex = index
                )
            }
        }
        return CodecOutput.TryAgainLater
    }

    override fun releaseOutputBuffer(index: Int) {
        try {
            codec?.releaseOutputBuffer(index, false)
        } catch (_: Throwable) {}
    }
}

object DefaultVideoEncoderFactory : VideoEncoderFactory {
    override fun createEncoder(): VideoEncoder = AndroidVideoEncoder()
}