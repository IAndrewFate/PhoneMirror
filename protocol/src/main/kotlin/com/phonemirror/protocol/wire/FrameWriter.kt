package com.phonemirror.protocol.wire

import java.io.OutputStream

class FrameWriter(private val out: OutputStream) {

    fun writeFrame(frame: Frame) {
        if (frame.body.size > WireConstants.MAX_FRAME_BODY_SIZE) {
            throw FrameTooLargeException("Frame body exceeds maximum allowed size: ${frame.body.size} > ${WireConstants.MAX_FRAME_BODY_SIZE}")
        }
        val totalLength = 1 + frame.body.size
        out.write((totalLength ushr 24) and 0xFF)
        out.write((totalLength ushr 16) and 0xFF)
        out.write((totalLength ushr 8) and 0xFF)
        out.write(totalLength and 0xFF)
        out.write(frame.kind.id)
        if (frame.body.isNotEmpty()) {
            out.write(frame.body)
        }
        out.flush()
    }
}