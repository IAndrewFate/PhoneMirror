package com.phonemirror.protocol.integration

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.*
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionEvent
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.*
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class SocketConformanceTest {

    @Test(timeout = 30000)
    fun interleaved_10k_frames_real_socket_roundtrip() {
        val harness = SocketPairHarness.createRealSocketPair()
        val rng = Random(7)
        val kinds = FrameKind.entries

        val frameCount = 10_000
        val frames = ArrayList<Frame>(frameCount)
        for (i in 0 until frameCount) {
            val kind = kinds[rng.nextInt(kinds.size)]
            val size = when {
                i % 100 == 0 -> rng.nextInt(10_000, 30_000)
                i % 10 == 0 -> 0
                else -> rng.nextInt(1, 1024)
            }
            val body = ByteArray(size)
            rng.nextBytes(body)
            frames.add(Frame(kind, body))
        }

        val writerThread = Thread {
            val writer = FrameWriter(harness.clientOutput)
            for (f in frames) {
                writer.writeFrame(f)
            }
        }
        writerThread.start()

        val reader = FrameReader(harness.serverInput)
        val startTime = System.currentTimeMillis()

        for (i in 0 until frameCount) {
            val read = reader.readFrame()
            val expected = frames[i]
            assertThat(read).isNotNull()
            assertThat(read!!.kind).isEqualTo(expected.kind)
            assertThat(read.body).isEqualTo(expected.body)
        }

        writerThread.join(5000)
        harness.close()

        val elapsed = System.currentTimeMillis() - startTime
        assertThat(elapsed).isLessThan(30000)
    }

    @Test
    fun control_messages_piped_harness_roundtrip() {
        val harness = SocketPairHarness.createPipedPair()
        testControlMessagesOverHarness(harness)
    }

    @Test
    fun control_messages_real_socket_roundtrip() {
        val harness = SocketPairHarness.createRealSocketPair()
        testControlMessagesOverHarness(harness)
    }

    private fun testControlMessagesOverHarness(harness: SocketPair) {
        val writer = FrameWriter(harness.clientOutput)
        val reader = FrameReader(harness.serverInput)

        val messages = listOf(
            ControlMessage.Hello(
                v = 1,
                gen = 10L,
                deviceId = "dev-abc",
                deviceName = "Phone A",
                pin = "7777",
                video = VideoParams(1920, 1080, 420, 60, 8_000_000),
                audio = AudioParams(enabled = true)
            ),
            ControlMessage.HelloOk(),
            ControlMessage.Ping(123456789L),
            ControlMessage.Pong(123456789L),
            ControlMessage.RequestKeyframe(),
            ControlMessage.ResolutionChange(1280, 720, 320),
            ControlMessage.Error(ErrorReason.PIN_REJECTED),
            ControlMessage.Stop()
        )

        for (msg in messages) {
            val frame = Frame(FrameKind.CONTROL, encodeControl(msg))
            writer.writeFrame(frame)
            val read = reader.readFrame()
            assertThat(read).isNotNull()
            assertThat(read!!.kind).isEqualTo(FrameKind.CONTROL)
            val decoded = decodeControl(read.body)
            assertThat(decoded).isEqualTo(msg)
        }

        harness.close()
    }

    @Test
    fun split_writes_at_boundary_patterns() {
        val harness = SocketPairHarness.createPipedPair()
        val frames = listOf(
            Frame(FrameKind.CONTROL, "control-data".toByteArray(Charsets.UTF_8)),
            Frame(FrameKind.VIDEO_FRAME, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
            Frame(FrameKind.AUDIO_FRAME, byteArrayOf(9, 10, 11, 12))
        )

        val bos = ByteArrayOutputStream()
        val memWriter = FrameWriter(bos)
        for (f in frames) {
            memWriter.writeFrame(f)
        }
        val allBytes = bos.toByteArray()

        // Write to pipe in chunks of 7 bytes (prime step to test arbitrary split boundaries)
        val writerThread = Thread {
            var offset = 0
            val chunkSize = 7
            while (offset < allBytes.size) {
                val len = minOf(chunkSize, allBytes.size - offset)
                harness.clientOutput.write(allBytes, offset, len)
                harness.clientOutput.flush()
                offset += len
            }
        }
        writerThread.start()

        val reader = FrameReader(harness.serverInput)
        for (expected in frames) {
            val read = reader.readFrame()
            assertThat(read).isEqualTo(expected)
        }

        writerThread.join(2000)
        harness.close()
    }

    @Test(timeout = 5000)
    fun adversarial_1k_random_byte_streams_never_hang() {
        val rng = Random(999)
        for (i in 0 until 1000) {
            val size = rng.nextInt(0, 500)
            val bytes = ByteArray(size)
            rng.nextBytes(bytes)

            val reader = FrameReader(ByteArrayInputStream(bytes))
            try {
                while (true) {
                    val frame = reader.readFrame() ?: break
                    // Frame parsed legally
                    assertThat(frame.kind).isNotNull()
                }
            } catch (_: ProtocolException) {
                // Expected for invalid framing
            }
        }
    }

    @Test
    fun adversarial_truncated_final_frame_mid_stream() {
        val validFrame = Frame(FrameKind.VIDEO_FRAME, byteArrayOf(1, 2, 3, 4))
        val bos = ByteArrayOutputStream()
        val writer = FrameWriter(bos)
        for (i in 0 until 5) {
            writer.writeFrame(validFrame)
        }

        // Now append truncated header (only 2 bytes of 4-byte length)
        bos.write(byteArrayOf(0x00, 0x00))

        val reader = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        for (i in 0 until 5) {
            val f = reader.readFrame()
            assertThat(f).isEqualTo(validFrame)
        }

        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex).hasMessageThat().contains("Truncated frame length header")
    }

    @Test
    fun adversarial_huge_length_header_rejected() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(WireConstants.MAX_FRAME_BODY_SIZE + 10)

        val reader = FrameReader(ByteArrayInputStream(buffer.array()))
        val ex = assertThrows(FrameTooLargeException::class.java) {
            reader.readFrame()
        }
        assertThat(ex).hasMessageThat().contains("exceeds maximum allowed")
    }

    @Test
    fun adversarial_zero_length_header_rejected() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0) // totalLength < 1

        val reader = FrameReader(ByteArrayInputStream(buffer.array()))
        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex).hasMessageThat().contains("Invalid frame total length: 0")
    }

    @Test
    fun real_session_transcript_video_and_audio() {
        val harness = SocketPairHarness.createRealSocketPair()
        val senderPolicy = SessionPolicy(Role.SENDER, gen = 1L)
        val receiverPolicy = SessionPolicy(Role.RECEIVER)

        val hello = ControlMessage.Hello(
            v = 1,
            gen = 1L,
            deviceId = "dev-1",
            deviceName = "Phone",
            pin = "1234",
            video = VideoParams(1920, 1080, 420, 60, 8_000_000),
            audio = AudioParams(enabled = true)
        )

        senderPolicy.onEvent(SessionEvent.HelloSent(hello))
        receiverPolicy.onEvent(SessionEvent.HelloReceived(hello))

        senderPolicy.onEvent(SessionEvent.HelloOkReceived)
        receiverPolicy.onEvent(SessionEvent.HelloOkSent)

        val writer = FrameWriter(harness.clientOutput)
        val reader = FrameReader(harness.serverInput)

        // 1. Video Config
        val videoConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
        writer.writeFrame(Frame(FrameKind.VIDEO_CONFIG, videoConfig))
        senderPolicy.onEvent(SessionEvent.VideoConfigSent)

        val readConfig = reader.readFrame()!!
        assertThat(readConfig.kind).isEqualTo(FrameKind.VIDEO_CONFIG)
        receiverPolicy.onEvent(SessionEvent.VideoConfigReceived)

        // 2. Audio Config
        val audioConfig = """{"codec":"opus","sampleRate":48000}""".toByteArray(Charsets.UTF_8)
        writer.writeFrame(Frame(FrameKind.AUDIO_CONFIG, audioConfig))
        senderPolicy.onEvent(SessionEvent.AudioConfigSent)

        val readAudioConfig = reader.readFrame()!!
        assertThat(readAudioConfig.kind).isEqualTo(FrameKind.AUDIO_CONFIG)
        receiverPolicy.onEvent(SessionEvent.AudioConfigReceived)

        // 3. First Keyframe
        val keyframeBody = VideoFrameBody(0L, keyframe = true, nal = byteArrayOf(0x65, 1, 2, 3)).encode()
        writer.writeFrame(Frame(FrameKind.VIDEO_FRAME, keyframeBody))
        senderPolicy.onEvent(SessionEvent.KeyframeSent)

        val readKeyframe = reader.readFrame()!!
        val decodedKeyframe = VideoFrameBody.decode(readKeyframe.body)
        val tKey = receiverPolicy.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, decodedKeyframe.keyframe))
        assertThat(tKey.isDroppable).isFalse()

        // Both are now STREAMING!
        assertThat(senderPolicy.state.name).isEqualTo("STREAMING")
        assertThat(receiverPolicy.state.name).isEqualTo("STREAMING")

        harness.close()
    }

    @Test
    fun real_session_transcript_resolution_change() {
        val senderPolicy = SessionPolicy(Role.SENDER)
        val receiverPolicy = SessionPolicy(Role.RECEIVER)

        val hello = ControlMessage.Hello(
            v = 1, gen = 1L, deviceId = "d", deviceName = "p", pin = "0",
            video = VideoParams(1920, 1080, 420, 60, 8_000_000),
            audio = AudioParams(enabled = false)
        )
        senderPolicy.onEvent(SessionEvent.HelloSent(hello))
        receiverPolicy.onEvent(SessionEvent.HelloReceived(hello))
        senderPolicy.onEvent(SessionEvent.HelloOkReceived)
        senderPolicy.onEvent(SessionEvent.VideoConfigSent)
        receiverPolicy.onEvent(SessionEvent.VideoConfigReceived)
        senderPolicy.onEvent(SessionEvent.KeyframeSent)
        receiverPolicy.onEvent(SessionEvent.KeyframeReceived)

        // Resolution changed
        val sTrans = senderPolicy.onEvent(SessionEvent.ResolutionChanged(1280, 720, 320))
        val rTrans = receiverPolicy.onEvent(SessionEvent.ResolutionChangeReceived(1280, 720, 320))

        assertThat(sTrans.requirements.map { it.name }).contains("AWAIT_KEYFRAME")
        assertThat(rTrans.requirements.map { it.name }).contains("AWAIT_KEYFRAME")

        // Non-keyframe is now droppable on receiver
        val tDelta = receiverPolicy.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, keyframe = false))
        assertThat(tDelta.isDroppable).isTrue()

        // Keyframe restores streaming and is non-droppable
        val tKey = receiverPolicy.onEvent(SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, keyframe = true))
        assertThat(tKey.isDroppable).isFalse()
    }

    @Test
    fun real_session_transcript_audio_disabled() {
        val sender = SessionPolicy(Role.SENDER)
        val hello = ControlMessage.Hello(
            v = 1, gen = 1L, deviceId = "d", deviceName = "p", pin = "0",
            video = VideoParams(1920, 1080, 420, 60, 8_000_000),
            audio = AudioParams(enabled = false)
        )
        sender.onEvent(SessionEvent.HelloSent(hello))
        sender.onEvent(SessionEvent.HelloOkReceived)
        val trans = sender.onEvent(SessionEvent.VideoConfigSent)
        // With audio disabled, no SEND_AUDIO_CONFIG requirement
        assertThat(trans.requirements.map { it.name }).containsExactly("SEND_KEYFRAME_REQUEST")
    }

    @Test
    fun real_session_transcript_reconnect_resend() {
        val sender = SessionPolicy(Role.SENDER, gen = 3L)
        val hello = ControlMessage.Hello(
            v = 1, gen = 3L, deviceId = "d", deviceName = "p", pin = "0",
            video = VideoParams(1920, 1080, 420, 60, 8_000_000),
            audio = AudioParams(enabled = false)
        )
        sender.onEvent(SessionEvent.HelloSent(hello))
        sender.onEvent(SessionEvent.HelloOkReceived)
        sender.onEvent(SessionEvent.VideoConfigSent)
        sender.onEvent(SessionEvent.KeyframeSent)

        // Drop
        sender.onEvent(SessionEvent.ConnectionLost)
        assertThat(sender.gen).isEqualTo(3L)

        // Reconnect
        sender.onEvent(SessionEvent.Reconnected)
        sender.onEvent(SessionEvent.HelloSent(hello))
        val tOk = sender.onEvent(SessionEvent.HelloOkReceived)
        assertThat(tOk.requirements.map { it.name }).containsExactly("SEND_VIDEO_CONFIG")
    }

    @Test
    fun piped_vs_real_socket_identical_behavior() {
        val piped = SocketPairHarness.createPipedPair()
        val real = SocketPairHarness.createRealSocketPair()

        val frame = Frame(FrameKind.VIDEO_FRAME, byteArrayOf(0x10, 0x20, 0x30, 0x40))

        FrameWriter(piped.clientOutput).writeFrame(frame)
        FrameWriter(real.clientOutput).writeFrame(frame)

        val pipedRead = FrameReader(piped.serverInput).readFrame()
        val realRead = FrameReader(real.serverInput).readFrame()

        assertThat(pipedRead).isEqualTo(realRead)
        assertThat(pipedRead).isEqualTo(frame)

        piped.close()
        real.close()
    }

    @Test
    fun corruption_injection_flipped_byte_in_length_detected() {
        val frame = Frame(FrameKind.VIDEO_FRAME, byteArrayOf(1, 2, 3, 4))
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).writeFrame(frame)

        val bytes = bos.toByteArray()
        // Corrupt length byte 0
        bytes[0] = 0x7F // makes length huge > 8 MiB

        val reader = FrameReader(ByteArrayInputStream(bytes))
        assertThrows(FrameTooLargeException::class.java) {
            reader.readFrame()
        }
    }

    @Test
    fun corruption_injection_flipped_byte_in_kind_detected() {
        val frame = Frame(FrameKind.VIDEO_FRAME, byteArrayOf(1, 2, 3, 4))
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).writeFrame(frame)

        val bytes = bos.toByteArray()
        // Corrupt kind byte (index 4)
        bytes[4] = 0xFE.toByte()

        val reader = FrameReader(ByteArrayInputStream(bytes))
        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex).hasMessageThat().contains("Unknown frame kind id")
    }

    @Test
    fun video_frame_body_annex_b_nal_real_socket_stream() {
        val harness = SocketPairHarness.createRealSocketPair()
        val writer = FrameWriter(harness.clientOutput)
        val reader = FrameReader(harness.serverInput)

        val spsPpsNal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F)
        val videoBody = VideoFrameBody(ptsUs = 12345678L, keyframe = true, nal = spsPpsNal)
        writer.writeFrame(Frame(FrameKind.VIDEO_FRAME, videoBody.encode()))

        val readFrame = reader.readFrame()!!
        val decoded = VideoFrameBody.decode(readFrame.body)

        assertThat(decoded.ptsUs).isEqualTo(12345678L)
        assertThat(decoded.keyframe).isTrue()
        assertThat(decoded.nal).isEqualTo(spsPpsNal)

        harness.close()
    }
}