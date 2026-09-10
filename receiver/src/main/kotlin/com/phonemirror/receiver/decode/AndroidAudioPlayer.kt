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
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val frameSize = (sampleRate * channelCount * 2 * 20) / 1000 // 3840 for 48k stereo 20ms
        val bufSize = maxOf(minBuf, 2 * frameSize)

        audioTrack = AudioTrack.Builder()
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun play() {
        audioTrack?.play()
    }

    override fun pause() {
        try { audioTrack?.pause() } catch (_: Exception) {}
    }

    override fun flush() {
        try { audioTrack?.flush() } catch (_: Exception) {}
        rolloverAdjustment = 0L
        lastRawHead = 0L
    }

    override fun stop() {
        try { audioTrack?.stop() } catch (_: Exception) {}
    }

    override fun release() {
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    override fun write(pcm: ByteArray, offset: Int, size: Int): Int {
        return audioTrack?.write(pcm, offset, size) ?: 0
    }

    override val playbackHeadPositionFrames: Long
        get() {
            val track = audioTrack ?: return 0L
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head < lastRawHead) {
                rolloverAdjustment += 0x100000000L
            }
            lastRawHead = head
            return head + rolloverAdjustment
        }

    override val underrunCount: Int
        get() {
            val track = audioTrack ?: return 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                track.underrunCount
            } else {
                0
            }
        }
}