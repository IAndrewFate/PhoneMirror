package com.phonemirror.receiver.server

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.*
import com.phonemirror.protocol.wire.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorServerTest {

    private val testPinProvider = object : PinProvider {
        var pin = "5555"
        override val current: String get() = pin
        override fun regenerate(): String {
            pin = "7777"
            return pin
        }
    }

    private fun createSampleHello(
        v: Int = 1,
        pin: String = "5555",
        deviceId: String = "test-device-uuid"
    ): ControlMessage.Hello {
        return ControlMessage.Hello(
            v = v,
            gen = 1L,
            deviceId = deviceId,
            deviceName = "Test Phone",
            pin = pin,
            video = VideoParams(1920, 1080, 400, 60, 8_000_000),
            audio = AudioParams(enabled = false)
        )
    }

    @Test
    fun hello_to_hello_ok_trusted_path() {
        val store = InMemoryPairingStore()
        store.trust("test-device-uuid")

        val hello = createSampleHello(pin = "wrong-pin") // PIN should be ignored for trusted device
        val inBos = ByteArrayOutputStream()
        FrameWriter(inBos).writeFrame(Frame(FrameKind.CONTROL, encodeControl(hello)))

        val outBos = ByteArrayOutputStream()
        val reader = FrameReader(ByteArrayInputStream(inBos.toByteArray()))
        val writer = FrameWriter(outBos)

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isInstanceOf(HandshakeResult.Success::class.java)

        val responseFrame = FrameReader(ByteArrayInputStream(outBos.toByteArray())).readFrame()
        assertThat(responseFrame?.kind).isEqualTo(FrameKind.CONTROL)
        val responseMsg = decodeControl(responseFrame!!.body)
        assertThat(responseMsg).isInstanceOf(ControlMessage.HelloOk::class.java)
    }

    @Test
    fun untrusted_right_pin_hello_ok_and_persisted() {
        val store = InMemoryPairingStore()
        assertThat(store.isTrusted("new-device")).isFalse()

        val hello = createSampleHello(pin = "5555", deviceId = "new-device")
        val inBos = ByteArrayOutputStream()
        FrameWriter(inBos).writeFrame(Frame(FrameKind.CONTROL, encodeControl(hello)))

        val outBos = ByteArrayOutputStream()
        val reader = FrameReader(ByteArrayInputStream(inBos.toByteArray()))
        val writer = FrameWriter(outBos)

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isInstanceOf(HandshakeResult.Success::class.java)
        assertThat(store.isTrusted("new-device")).isTrue()

        val responseFrame = FrameReader(ByteArrayInputStream(outBos.toByteArray())).readFrame()
        assertThat(decodeControl(responseFrame!!.body)).isInstanceOf(ControlMessage.HelloOk::class.java)
    }

    @Test
    fun wrong_pin_pin_rejected_and_close() {
        val store = InMemoryPairingStore()
        val hello = createSampleHello(pin = "9999", deviceId = "untrusted-dev")
        val inBos = ByteArrayOutputStream()
        FrameWriter(inBos).writeFrame(Frame(FrameKind.CONTROL, encodeControl(hello)))

        val outBos = ByteArrayOutputStream()
        val reader = FrameReader(ByteArrayInputStream(inBos.toByteArray()))
        val writer = FrameWriter(outBos)

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isEqualTo(HandshakeResult.Rejected(ErrorReason.PIN_REJECTED))
        assertThat(store.isTrusted("untrusted-dev")).isFalse()

        val responseFrame = FrameReader(ByteArrayInputStream(outBos.toByteArray())).readFrame()
        val responseMsg = decodeControl(responseFrame!!.body)
        assertThat(responseMsg).isEqualTo(ControlMessage.Error(ErrorReason.PIN_REJECTED))
    }

    @Test
    fun proto_mismatch_v2() {
        val store = InMemoryPairingStore()
        val hello = createSampleHello(v = 2)
        val inBos = ByteArrayOutputStream()
        FrameWriter(inBos).writeFrame(Frame(FrameKind.CONTROL, encodeControl(hello)))

        val outBos = ByteArrayOutputStream()
        val reader = FrameReader(ByteArrayInputStream(inBos.toByteArray()))
        val writer = FrameWriter(outBos)

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isEqualTo(HandshakeResult.Rejected(ErrorReason.PROTO_MISMATCH))

        val responseFrame = FrameReader(ByteArrayInputStream(outBos.toByteArray())).readFrame()
        val responseMsg = decodeControl(responseFrame!!.body)
        assertThat(responseMsg).isEqualTo(ControlMessage.Error(ErrorReason.PROTO_MISMATCH))
    }

    @Test
    fun non_hello_first_frame() {
        val store = InMemoryPairingStore()
        val ping = ControlMessage.Ping(12345L)
        val inBos = ByteArrayOutputStream()
        FrameWriter(inBos).writeFrame(Frame(FrameKind.CONTROL, encodeControl(ping)))

        val outBos = ByteArrayOutputStream()
        val reader = FrameReader(ByteArrayInputStream(inBos.toByteArray()))
        val writer = FrameWriter(outBos)

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isEqualTo(HandshakeResult.ProtocolError)
    }

    @Test
    fun garbage_first_frame_or_eof() {
        val store = InMemoryPairingStore()
        val reader = FrameReader(ByteArrayInputStream(ByteArray(0))) // clean EOF
        val writer = FrameWriter(ByteArrayOutputStream())

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isEqualTo(HandshakeResult.ProtocolError)
    }

    @Test
    fun non_control_first_frame() {
        val store = InMemoryPairingStore()
        val inBos = ByteArrayOutputStream()
        FrameWriter(inBos).writeFrame(Frame(FrameKind.VIDEO_FRAME, byteArrayOf(1, 2, 3)))

        val reader = FrameReader(ByteArrayInputStream(inBos.toByteArray()))
        val writer = FrameWriter(ByteArrayOutputStream())

        val result = MirrorServer.performHandshake(reader, writer, testPinProvider, store)
        assertThat(result).isEqualTo(HandshakeResult.ProtocolError)
    }

    @Test
    fun sha256_stability_vector() {
        val hash = hashDeviceId("test-device")
        val expected = "2fe90d9c33ad85a19cd001498db13fb2235742686b47cf8cf6ed6b6014305588"
        assertThat(hash).isEqualTo(expected)
    }

    @Test
    fun pin_provider_regenerates_4_digit_pin() {
        val provider = DefaultPinProvider()
        val pin1 = provider.current
        assertThat(pin1.length).isEqualTo(4)
        assertThat(pin1.toIntOrNull()).isNotNull()
        assertThat(pin1.toInt()).isIn(1000..9999)

        val pin2 = provider.regenerate()
        assertThat(pin2.length).isEqualTo(4)
        assertThat(pin2.toInt()).isIn(1000..9999)
    }

    @Test
    fun in_memory_pairing_store_trust_and_clear() {
        val store = InMemoryPairingStore()
        assertThat(store.pairedCount).isEqualTo(0)

        store.trust("dev-1")
        store.trust("dev-2")
        assertThat(store.isTrusted("dev-1")).isTrue()
        assertThat(store.isTrusted("dev-2")).isTrue()
        assertThat(store.isTrusted("dev-3")).isFalse()
        assertThat(store.pairedCount).isEqualTo(2)

        store.clearTrusted()
        assertThat(store.pairedCount).isEqualTo(0)
        assertThat(store.isTrusted("dev-1")).isFalse()
    }

    @Test
    fun port_fallback_bind_logic() {
        val dummySocket = ServerSocket(Defaults.BASE_PORT)
        try {
            val server = MirrorServer()
            val boundPort = server.bind(Defaults.BASE_PORT, Defaults.MAX_PORT)
            assertThat(boundPort).isGreaterThan(Defaults.BASE_PORT)
            assertThat(server.actualBoundPort).isEqualTo(boundPort)
            server.stop()
        } finally {
            dummySocket.close()
        }
    }

    @Test(timeout = 5000)
    fun send_control_roundtrip_ping_pong() {
        val clientToServerOut = PipedOutputStream()
        val clientToServerIn = PipedInputStream(clientToServerOut)

        val serverToClientOut = PipedOutputStream()
        val serverToClientIn = PipedInputStream(serverToClientOut)

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val dispatcher = StreamDispatcher(
            inputStream = clientToServerIn,
            outputStream = serverToClientOut
        )
        val job = dispatcher.start(scope)

        try {
            val clientWriter = FrameWriter(clientToServerOut)
            clientWriter.writeFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.Ping(9999L))))

            val clientReader = FrameReader(serverToClientIn)
            val pongFrame = clientReader.readFrame()
            assertThat(pongFrame).isNotNull()
            assertThat(pongFrame!!.kind).isEqualTo(FrameKind.CONTROL)

            val pongMsg = decodeControl(pongFrame.body)
            assertThat(pongMsg).isInstanceOf(ControlMessage.Pong::class.java)
            assertThat((pongMsg as ControlMessage.Pong).t).isEqualTo(9999L)
        } finally {
            dispatcher.stop()
            job.cancel()
            scope.cancel()
        }
    }
}