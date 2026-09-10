package com.phonemirror.receiver.decode

import android.view.Surface

data class VideoDecoderFormat(
    val width: Int,
    val height: Int,
    val sps: ByteArray?,
    val pps: ByteArray?
)

interface VideoDecoder {
    fun configure(format: VideoDecoderFormat, surface: Surface?)
    fun start()
    fun stop()
    fun flush()
    fun release()
    fun queueInput(data: ByteArray, ptsUs: Long, isKeyframe: Boolean): Boolean
    fun dequeueOutput(timeoutUs: Long): DecoderOutput?
    fun releaseOutputBuffer(index: Int, render: Boolean, renderTimestampNs: Long? = null)
}

sealed class DecoderOutput {
    data class Frame(val bufferIndex: Int, val ptsUs: Long, val isKeyframe: Boolean) : DecoderOutput()
    data object TryAgainLater : DecoderOutput()
    data class Error(val message: String) : DecoderOutput()
}

fun interface VideoDecoderFactory {
    fun createDecoder(): VideoDecoder
}