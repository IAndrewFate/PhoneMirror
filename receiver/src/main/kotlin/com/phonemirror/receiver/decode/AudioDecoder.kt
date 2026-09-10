package com.phonemirror.receiver.decode

data class AudioDecoderFormat(
    val mime: String,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val csdBuffers: List<ByteArray> = emptyList()
)

sealed class AudioDecoderOutput {
    data class Pcm(val bufferIndex: Int, val pcm: ByteArray, val ptsUs: Long) : AudioDecoderOutput()
    data object TryAgainLater : AudioDecoderOutput()
    data class FormatChanged(val sampleRate: Int, val channelCount: Int) : AudioDecoderOutput()
    data class Error(val message: String) : AudioDecoderOutput()
}

interface AudioDecoder {
    fun configure(format: AudioDecoderFormat)
    fun start()
    fun stop()
    fun flush()
    fun release()
    fun queueInput(data: ByteArray, ptsUs: Long): Boolean
    fun dequeueOutput(timeoutUs: Long): AudioDecoderOutput?
    fun releaseOutputBuffer(index: Int)
}

interface AudioPlayer {
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
    fun write(pcm: ByteArray, offset: Int, size: Int): Int
    val playbackHeadPositionFrames: Long
    val underrunCount: Int
}