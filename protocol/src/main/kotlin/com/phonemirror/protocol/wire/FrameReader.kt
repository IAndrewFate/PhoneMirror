package com.phonemirror.protocol.wire

import java.io.EOFException
import java.io.InputStream

class FrameReader(private val input: InputStream) {

    fun readFrame(): Frame? {
        val b0 = input.read()
        if (b0 == -1) {
            return null // Clean EOF before first byte
        }
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b1 == -1 || b2 == -1 || b3 == -1) {
            throw ProtocolException("Truncated frame length header")
        }

        val totalLength = ((b0 and 0xFF) shl 24) or
                ((b1 and 0xFF) shl 16) or
                ((b2 and 0xFF) shl 8) or
                (b3 and 0xFF)

        if (totalLength < 1) {
            throw ProtocolException("Invalid frame total length: $totalLength")
        }

        val bodySize = totalLength - 1
        if (bodySize > WireConstants.MAX_FRAME_BODY_SIZE) {
            throw FrameTooLargeException("Frame body size exceeds maximum allowed: $bodySize > ${WireConstants.MAX_FRAME_BODY_SIZE}")
        }

        val kindByte = input.read()
        if (kindByte == -1) {
            throw ProtocolException("Truncated frame: missing kind byte")
        }
        val kind = FrameKind.fromId(kindByte)

        val body = ByteArray(bodySize)
        var bytesRead = 0
        while (bytesRead < bodySize) {
            val r = input.read(body, bytesRead, bodySize - bytesRead)
            if (r == -1) {
                throw ProtocolException("Truncated frame body: expected $bodySize bytes, got $bytesRead")
            }
            bytesRead += r
        }

        return Frame(kind, body)
    }
}