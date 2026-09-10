package com.phonemirror.protocol.wire

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class WireFramingTest {

    @Test
    fun roundtrip_all_frame_kinds() {
        for (kind in FrameKind.entries) {
            val payload = "test-payload-for-${kind.name}".toByteArray(Charsets.UTF_8)
            val original = Frame(kind, payload)

            val bos = ByteArrayOutputStream()
            val writer = FrameWriter(bos)
            writer.writeFrame(original)

            val reader = FrameReader(ByteArrayInputStream(bos.toByteArray()))
            val read = reader.readFrame()

            assertThat(read).isNotNull()
            assertThat(read!!.kind).isEqualTo(kind)
            assertThat(read.body).isEqualTo(payload)
            assertThat(read).isEqualTo(original)
        }
    }

    @Test
    fun split_stream_reads_single_byte() {
        val original = Frame(FrameKind.VIDEO_FRAME, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).writeFrame(original)

        val rawBytes = bos.toByteArray()
        val oneByteStream = object : InputStream() {
            private var index = 0
            override fun read(): Int {
                return if (index < rawBytes.size) {
                    rawBytes[index++].toInt() and 0xFF
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= rawBytes.size) return -1
                val byte = rawBytes[index++].toInt() and 0xFF
                b[off] = byte.toByte()
                return 1 // Force single-byte read
            }
        }

        val reader = FrameReader(oneByteStream)
        val read = reader.readFrame()
        assertThat(read).isEqualTo(original)
    }

    @Test
    fun two_frames_concatenated() {
        val f1 = Frame(FrameKind.CONTROL, "hello".toByteArray(Charsets.UTF_8))
        val f2 = Frame(FrameKind.VIDEO_FRAME, byteArrayOf(10, 20, 30))

        val bos = ByteArrayOutputStream()
        val writer = FrameWriter(bos)
        writer.writeFrame(f1)
        writer.writeFrame(f2)

        val reader = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        assertThat(reader.readFrame()).isEqualTo(f1)
        assertThat(reader.readFrame()).isEqualTo(f2)
        assertThat(reader.readFrame()).isNull()
    }

    @Test
    fun clean_eof_returns_null() {
        val reader = FrameReader(ByteArrayInputStream(ByteArray(0)))
        assertThat(reader.readFrame()).isNull()
    }

    @Test
    fun empty_body_roundtrip() {
        val original = Frame(FrameKind.VIDEO_CONFIG, ByteArray(0))
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).writeFrame(original)

        val reader = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        val read = reader.readFrame()
        assertThat(read).isEqualTo(original)
        assertThat(read!!.body).isEmpty()
    }

    @Test
    fun max_frame_body_allowed() {
        val payload = ByteArray(1024) { (it % 128).toByte() }
        val frame = Frame(FrameKind.VIDEO_FRAME, payload)
        val bos = ByteArrayOutputStream()
        FrameWriter(bos).writeFrame(frame)

        val reader = FrameReader(ByteArrayInputStream(bos.toByteArray()))
        assertThat(reader.readFrame()).isEqualTo(frame)
    }

    @Test
    fun max_frame_body_exceeded_writer_throws() {
        val oversized = ByteArray(WireConstants.MAX_FRAME_BODY_SIZE + 1)
        val frame = Frame(FrameKind.VIDEO_FRAME, oversized)
        val writer = FrameWriter(ByteArrayOutputStream())

        val ex = assertThrows(FrameTooLargeException::class.java) {
            writer.writeFrame(frame)
        }
        assertThat(ex.message).contains("exceeds maximum allowed size")
    }

    @Test
    fun max_frame_body_exceeded_reader_throws() {
        val totalLength = 1 + WireConstants.MAX_FRAME_BODY_SIZE + 1
        val buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(totalLength)
        buffer.put(FrameKind.VIDEO_FRAME.id.toByte())

        val reader = FrameReader(ByteArrayInputStream(buffer.array()))
        val ex = assertThrows(FrameTooLargeException::class.java) {
            reader.readFrame()
        }
        assertThat(ex.message).contains("exceeds maximum allowed")
    }

    @Test
    fun unknown_kind_byte_throws() {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(2) // 1 kind byte + 1 body byte
        buffer.put(0x99.toByte()) // unknown kind
        buffer.put(0x01.toByte())

        val reader = FrameReader(ByteArrayInputStream(buffer.array()))
        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex.message).contains("Unknown frame kind id: 153")
    }

    @Test
    fun truncated_length_header_throws() {
        val truncatedHeader = byteArrayOf(0x00, 0x00) // only 2 bytes instead of 4
        val reader = FrameReader(ByteArrayInputStream(truncatedHeader))
        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex.message).contains("Truncated frame length header")
    }

    @Test
    fun truncated_kind_byte_throws() {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(5) // length says 5 bytes follow, but 0 follow

        val reader = FrameReader(ByteArrayInputStream(buffer.array()))
        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex.message).contains("Truncated frame: missing kind byte")
    }

    @Test
    fun truncated_body_throws() {
        val buffer = ByteBuffer.allocate(5 + 2).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(5) // 1 kind byte + 4 body bytes expected
        buffer.put(FrameKind.CONTROL.id.toByte())
        buffer.put(byteArrayOf(1, 2)) // only 2 body bytes provided

        val reader = FrameReader(ByteArrayInputStream(buffer.array()))
        val ex = assertThrows(ProtocolException::class.java) {
            reader.readFrame()
        }
        assertThat(ex.message).contains("Truncated frame body")
    }

    @Test
    fun video_frame_body_roundtrip_and_pts_edges() {
        val ptsCases = listOf(0L, 1L, 1_000_000L, Long.MAX_VALUE)
        for (pts in ptsCases) {
            for (keyframe in listOf(true, false)) {
                val nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42)
                val body = VideoFrameBody(pts, keyframe, nal)
                val encoded = body.encode()
                val decoded = VideoFrameBody.decode(encoded)

                assertThat(decoded).isEqualTo(body)
                assertThat(decoded.ptsUs).isEqualTo(pts)
                assertThat(decoded.keyframe).isEqualTo(keyframe)
                assertThat(decoded.nal).isEqualTo(nal)
            }
        }
    }

    @Test
    fun video_frame_body_empty_nal_throws() {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(100L)
        buffer.putInt(1)

        val ex = assertThrows(ProtocolException::class.java) {
            VideoFrameBody.decode(buffer.array())
        }
        assertThat(ex.message).contains("too short")
    }

    @Test
    fun audio_frame_body_roundtrip_and_pts_edges() {
        val ptsCases = listOf(0L, 500L, Long.MAX_VALUE)
        for (pts in ptsCases) {
            val packet = byteArrayOf(0x78, 0x56, 0x34, 0x12)
            val body = AudioFrameBody(pts, packet)
            val encoded = body.encode()
            val decoded = AudioFrameBody.decode(encoded)

            assertThat(decoded).isEqualTo(body)
            assertThat(decoded.ptsUs).isEqualTo(pts)
            assertThat(decoded.packet).isEqualTo(packet)
        }
    }

    @Test
    fun audio_frame_body_empty_packet_throws() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(100L)

        val ex = assertThrows(ProtocolException::class.java) {
            AudioFrameBody.decode(buffer.array())
        }
        assertThat(ex.message).contains("too short")
    }

    @Test(timeout = 30000)
    fun fuzz_10k_random_frames() {
        val rng = Random(42)
        val kinds = FrameKind.entries
        val bos = ByteArrayOutputStream()
        val writer = FrameWriter(bos)

        val frames = ArrayList<Frame>(10_000)
        for (i in 0 until 10_000) {
            val kind = kinds[rng.nextInt(kinds.size)]
            val size = when {
                i % 100 == 0 -> rng.nextInt(10_000, 50_000) // occasional larger frame
                i % 10 == 0 -> 0 // occasional empty body
                else -> rng.nextInt(1, 512) // normal frames
            }
            val body = ByteArray(size)
            rng.nextBytes(body)
            val frame = Frame(kind, body)
            frames.add(frame)
            writer.writeFrame(frame)
        }

        val bis = ByteArrayInputStream(bos.toByteArray())
        val reader = FrameReader(bis)

        for (i in 0 until 10_000) {
            val read = reader.readFrame()
            val expected = frames[i]
            assertThat(read).isNotNull()
            assertThat(read!!.kind).isEqualTo(expected.kind)
            assertThat(read.body).isEqualTo(expected.body)
        }

        assertThat(reader.readFrame()).isNull()
    }
}