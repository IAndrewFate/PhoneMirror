package com.phonemirror.receiver.decode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

class AndroidAudioPlayer(
    val sampleRate: Int = 48000,
    val channelCount: Int = 2
) : AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private var lastRawHead: Long = 0L
    private var rolloverAdjustment: Long = 0L

    init {
        createTrack()
    }

    private fun createTrack() {
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = try {
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) {
            -1
        }
        val frameSize = (sampleRate * channelCount * 2 * 20) / 1000 // 3840 for 48k stereo 20ms
        // TVs need a larger buffer than phones to prevent underruns in hardware audio HAL
        val baseMin = if (minBuf > 0) minBuf else frameSize * 4
        val bufSize = maxOf(baseMin * 2, frameSize * 8, 32768)

        // Attempt 1: Standard AudioTrack.Builder (without low-latency mode to support Smart TVs)
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
                return
            } else {
                try { track.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Attempt 2: Legacy AudioTrack constructor fallback
        try {
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STREAM
            )
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
                return
            } else {
                try { track.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Attempt 3: Double buffer size if HAL had large buffer requirements
        try {
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize * 2,
                AudioTrack.MODE_STREAM
            )
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
            } else {
                try { track.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    override fun play() {
        try {
            val track = audioTrack ?: return
            if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        } catch (_: Throwable) {}
    }

    override fun pause() {
        try {
            val track = audioTrack ?: return
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
            }
        } catch (_: Throwable) {}
    }

    override fun flush() {
        try {
            val track = audioTrack ?: return
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.flush()
            }
        } catch (_: Throwable) {}
        rolloverAdjustment = 0L
        lastRawHead = 0L
    }

    override fun stop() {
        try {
            val track = audioTrack ?: return
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.stop()
            }
        } catch (_: Throwable) {}
    }

    override fun release() {
        try {
            audioTrack?.release()
        } catch (_: Throwable) {}
        audioTrack = null
    }

    override fun write(pcm: ByteArray, offset: Int, size: Int): Int {
        return try {
            val track = audioTrack ?: return 0
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.write(pcm, offset, size)
            } else 0
        } catch (_: Throwable) {
            0
        }
    }

    override val playbackHeadPositionFrames: Long
        get() {
            return try {
                val track = audioTrack ?: return 0L
                if (track.state != AudioTrack.STATE_INITIALIZED) return 0L
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head < lastRawHead) {
                    rolloverAdjustment += 0x100000000L
                }
                lastRawHead = head
                head + rolloverAdjustment
            } catch (_: Throwable) {
                0L
            }
        }

    override val underrunCount: Int
        get() {
            return try {
                val track = audioTrack ?: return 0
                if (track.state == AudioTrack.STATE_INITIALIZED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    track.underrunCount
                } else {
                    0
                }
            } catch (_: Throwable) {
                0
            }
        }
}