package com.phonemirror.sender.capture

import android.media.projection.MediaProjection

interface AudioCaptureSource {
    fun start()
    fun stop()
    fun release()
    fun read(buffer: ByteArray, offset: Int, size: Int): Int
    fun getTimestampNs(): Long?
}

interface AudioEncoder {
    val codecName: String // "opus" or "mp4a-latm"
    fun configure(sampleRate: Int, channels: Int, bitrate: Int)
    fun start()
    fun stop()
    fun release()
    fun queueInput(data: ByteArray, offset: Int, size: Int, ptsUs: Long)
    fun dequeueOutput(timeoutUs: Long): AudioCodecOutput?
}

sealed class AudioCodecOutput {
    data class Config(val csdList: List<ByteArray>) : AudioCodecOutput()
    data class Frame(val data: ByteArray, val ptsUs: Long) : AudioCodecOutput()
    data object TryAgainLater : AudioCodecOutput()
}

fun interface AudioCaptureSourceFactory {
    fun createSource(projection: MediaProjection?): AudioCaptureSource
}

fun interface AudioEncoderFactory {
    fun createEncoder(): AudioEncoder
}