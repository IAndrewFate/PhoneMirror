package com.phonemirror.receiver.e2e

import com.phonemirror.protocol.wire.*

class FakeMediaSource {
    companion object {
        val SPS_PAYLOAD = byteArrayOf(0x67, 0x42, 0x00, 0x2A, 0x95.toByte())
        val PPS_PAYLOAD = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val VIDEO_CONFIG_FIXTURE = byteArrayOf(0, 0, 0, 1) + SPS_PAYLOAD + byteArrayOf(0, 0, 0, 1) + PPS_PAYLOAD

        fun createVideoConfigFrame(): Frame {
            return Frame(FrameKind.VIDEO_CONFIG, VIDEO_CONFIG_FIXTURE.copyOf())
        }

        fun createVideoFrame(ptsUs: Long, keyframe: Boolean, payloadSize: Int = 256, tag: Byte = 0): Frame {
            val nal = ByteArray(payloadSize) { i -> ((i + ptsUs + tag) % 250).toByte() }
            if (keyframe) {
                nal[0] = 0x65 // IDR NAL
            } else {
                nal[0] = 0x41 // Non-IDR NAL
            }
            val body = VideoFrameBody(ptsUs, keyframe, nal).encode()
            return Frame(FrameKind.VIDEO_FRAME, body)
        }

        fun createAudioConfigFrame(): Frame {
            val json = "{\"codec\":\"opus\",\"sampleRate\":48000,\"channels\":2,\"csd\":[]}"
            return Frame(FrameKind.AUDIO_CONFIG, json.toByteArray(Charsets.UTF_8))
        }

        fun createAudioFrame(ptsUs: Long, payloadSize: Int = 80, tag: Byte = 0): Frame {
            val packet = ByteArray(payloadSize) { i -> ((i + ptsUs + tag) % 250).toByte() }
            val body = AudioFrameBody(ptsUs, packet).encode()
            return Frame(FrameKind.AUDIO_FRAME, body)
        }
    }
}
