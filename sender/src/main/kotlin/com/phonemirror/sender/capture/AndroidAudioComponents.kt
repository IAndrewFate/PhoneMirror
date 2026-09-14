package com.phonemirror.sender.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

class AndroidAudioCaptureSource(
    private val projection: MediaProjection?
) : AudioCaptureSource {

    private var audioRecord: AudioRecord? = null
    private val timestamp = AudioTimestamp()

    @SuppressLint("MissingPermission")
    override fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projection == null) return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioMath.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize * 2, AudioMath.BYTES_PER_CHUNK * 2)

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        record.startRecording()
        audioRecord = record
    }

    override fun stop() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
    }

    override fun release() {
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val record = audioRecord ?: return -1
        return record.read(buffer, offset, size)
    }

    override fun getTimestampNs(): Long? {
        val record = audioRecord ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val status = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
            if (status == AudioRecord.SUCCESS) {
                return timestamp.nanoTime
            }
        }
        return null
    }
}

class AndroidAudioEncoder : AudioEncoder {
    override var codecName: String = "mp4a-latm"
        private set

    private var codec: MediaCodec? = null
    private val csdList = mutableListOf<ByteArray>()

    override fun configure(sampleRate: Int, channels: Int, bitrate: Int) {
        // Try AAC-LC first (mandatory on all Android TVs & mobile devices), fallback to Opus
        var mediaCodec: MediaCodec? = null
        var chosenCodec = "mp4a-latm"

        try {
            mediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val aacFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            mediaCodec.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            chosenCodec = "mp4a-latm"
        } catch (_: Throwable) {
            try { mediaCodec?.release() } catch (_: Throwable) {}
            // Fallback to Opus
            try {
                mediaCodec = MediaCodec.createEncoderByType("audio/opus")
                val format = MediaFormat.createAudioFormat("audio/opus", sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                chosenCodec = "opus"
            } catch (t: Throwable) {
                codec = null
                codecName = "none"
                throw t
            }
        }

        codec = mediaCodec
        codecName = chosenCodec
    }

    override fun start() {
        codec?.start()
    }

    override fun stop() {
        try { codec?.stop() } catch (_: Throwable) {}
    }

    override fun release() {
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
    }

    override fun queueInput(data: ByteArray, offset: Int, size: Int, ptsUs: Long) {
        val c = codec ?: return
        val index = try {
            c.dequeueInputBuffer(10_000L)
        } catch (_: Throwable) {
            -1
        }
        if (index >= 0) {
            val inputBuf = try { c.getInputBuffer(index) } catch (_: Throwable) { null }
            if (inputBuf != null) {
                inputBuf.clear()
                inputBuf.put(data, offset, size)
                c.queueInputBuffer(index, 0, size, ptsUs, 0)
            }
        }
    }

    override fun dequeueOutput(timeoutUs: Long): AudioCodecOutput? {
        val c = codec ?: return null
        val bufferInfo = MediaCodec.BufferInfo()
        val index = try {
            c.dequeueOutputBuffer(bufferInfo, timeoutUs)
        } catch (_: Throwable) {
            return null
        }

        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return AudioCodecOutput.TryAgainLater
        }

        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val outFormat = c.outputFormat
            extractCsdFromFormat(outFormat)
            if (csdList.isNotEmpty()) {
                return AudioCodecOutput.Config(csdList.toList())
            }
            return AudioCodecOutput.TryAgainLater
        }

        if (index >= 0) {
            val buf = try { c.getOutputBuffer(index) } catch (_: Throwable) { null }
            if (buf == null) {
                try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                return AudioCodecOutput.TryAgainLater
            }

            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            val bytes = ByteArray(bufferInfo.size)
            buf.position(bufferInfo.offset)
            buf.get(bytes, 0, bufferInfo.size)
            try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) {}

            if (isConfig) {
                csdList.add(bytes)
                return AudioCodecOutput.Config(listOf(bytes))
            } else {
                return AudioCodecOutput.Frame(bytes, bufferInfo.presentationTimeUs)
            }
        }

        return AudioCodecOutput.TryAgainLater
    }

    private fun extractCsdFromFormat(format: MediaFormat) {
        var i = 0
        while (format.containsKey("csd-$i")) {
            val csdBuf = format.getByteBuffer("csd-$i")
            if (csdBuf != null) {
                val bytes = ByteArray(csdBuf.remaining())
                csdBuf.get(bytes)
                csdList.add(bytes)
            }
            i++
        }

        // If Opus and CSD is empty, construct standard OpusHead
        if (codecName == "opus" && csdList.isEmpty()) {
            csdList.add(OpusCsdBuilder.buildOpusHead())
            csdList.add(OpusCsdBuilder.buildCodecDelay())
            csdList.add(OpusCsdBuilder.buildSeekPreRoll())
        } else if (codecName == "mp4a-latm" && csdList.isEmpty()) {
            // 48000Hz stereo AAC-LC = 0x11, 0x90
            csdList.add(byteArrayOf(0x11, 0x90.toByte()))
        }
    }
}

object DefaultAudioCaptureSourceFactory : AudioCaptureSourceFactory {
    override fun createSource(projection: MediaProjection?): AudioCaptureSource =
        AndroidAudioCaptureSource(projection)
}

object DefaultAudioEncoderFactory : AudioEncoderFactory {
    override fun createEncoder(): AudioEncoder = AndroidAudioEncoder()
}