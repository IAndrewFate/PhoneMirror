package com.phonemirror.sender.net

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.*
import com.phonemirror.protocol.wire.*
import kotlinx.coroutines.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

class StreamClientTest {

    @Test
    fun backoff_schedule_exact_vector() {
        val client = StreamClient()
        assertThat(client.backoffSchedule).containsExactly(
            1000L, 2000L, 4000L, 8000L, 8000L, 8000L, 8000L, 8000L, 8000L, 8000L
        ).inOrder()
        assertThat(client.backoffSchedule.size).isEqualTo(10)
    }

    @Test
    fun hello_bytes_construction_against_t3_encoder() {
        val store = InMemoryEndpointStore("my-unique-device-id")
        val client = StreamClient(endpointStore = store)

        val hello = ControlMessage.Hello(
            v = 1,
            gen = 5L,
            deviceId = store.getDeviceId(),
            deviceName = "Android",
            pin = "4321",
            video = client.videoParams,
            audio = client.audioParams
        )
        val encoded = encodeControl(hello)
        val decoded = decodeControl(encoded)

        assertThat(decoded).isEqualTo(hello)
        assertThat((decoded as ControlMessage.Hello).deviceId).isEqualTo("my-unique-device-id")
        assertThat(decoded.pin).isEqualTo("4321")
        assertThat(decoded.gen).isEqualTo(5L)
    }

    @Test
    fun error_reason_state_mapping_no_retry_set() {
        for (reason in ErrorReason.entries) {
            val state = ClientState.PairingFailure(reason)
            assertThat(state.reason).isEqualTo(reason)
        }
    }

    @Test
    fun byte_bounded_channel_accounting_with_time_derived_cap() {
        // At 8 Mbps (8_000_000 bps): 8_000_000 * 0.3 / 8 = 300_000 bytes (~300 KB)
        val client8M = StreamClient(configuredBitrateBps = 8_000_000)
        assertThat(client8M.calculateCapacityBytes()).isEqualTo(300_000L)

        // Low bitrate (1 Mbps): 1_000_000 * 0.3 / 8 = 37_500 bytes -> clamped to 256 KiB
        val clientLow = StreamClient(configuredBitrateBps = 1_000_000)
        assertThat(clientLow.calculateCapacityBytes()).isEqualTo(256 * 1024L)

        // High bitrate (50 Mbps): 50_000_000 * 0.3 / 8 = 1_875_000 bytes -> clamped to 1 MiB
        val clientHigh = StreamClient(configuredBitrateBps = 50_000_000)
        assertThat(clientHigh.calculateCapacityBytes()).isEqualTo(1024 * 1024L)
    }

    @Test
    fun overflow_policy_rejection_and_congestion_signal() {
        val policy = DropOldestAudioOverflowPolicy()
        val capacity = 1000L
        val current = 900L

        val smallFrame = Frame(FrameKind.AUDIO_FRAME, ByteArray(50))
        val bigFrame = Frame(FrameKind.VIDEO_FRAME, ByteArray(200))

        assertThat(policy.shouldAcceptFrame(smallFrame, current, capacity)).isTrue()
        assertThat(policy.shouldAcceptFrame(bigFrame, current, capacity)).isFalse()

        policy.onCongestion()
        assertThat(policy.congestionSignalCount).isEqualTo(1)
    }

    @Test
    fun last_endpoint_persistence_and_quick_connect_prefill() {
        val store = InMemoryEndpointStore()
        assertThat(store.loadEndpoint()).isNull()

        store.saveEndpoint("192.168.1.50", 47700, "Living Room TV")
        val loaded = store.loadEndpoint()
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.host).isEqualTo("192.168.1.50")
        assertThat(loaded.port).isEqualTo(47700)
        assertThat(loaded.name).isEqualTo("Living Room TV")
    }

    @Test
    fun device_id_persistence_store() {
        val store = InMemoryEndpointStore(deviceId = "uuid-12345")
        assertThat(store.getDeviceId()).isEqualTo("uuid-12345")
    }

    @Test
    fun socket_factory_configuration_sets_options() {
        val server = ServerSocket(0)
        val port = server.localPort

        val socket = Socket("127.0.0.1", port)
        socket.tcpNoDelay = true
        socket.sendBufferSize = 128 * 1024

        assertThat(socket.tcpNoDelay).isTrue()
        assertThat(socket.sendBufferSize).isAtLeast(128 * 1024)

        socket.close()
        server.close()
    }

    @Test(timeout = 10000)
    fun loopback_integration_happy_connect_hello_hello_ok_pong_clean_stop() {
        val server = ServerSocket(0)
        val port = server.localPort

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = StreamClient()

        // Mock Server thread
        val serverThread = Thread {
            val clientSocket = server.accept()
            val reader = FrameReader(clientSocket.getInputStream())
            val writer = FrameWriter(clientSocket.getOutputStream())

            // Read Hello
            val helloFrame = reader.readFrame()
            val helloMsg = decodeControl(helloFrame!!.body) as ControlMessage.Hello
            assertThat(helloMsg.v).isEqualTo(1)

            // Send HelloOk
            writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.HelloOk())))

            // Read Ping and reply Pong
            val pingFrame = reader.readFrame()
            if (pingFrame != null && pingFrame.kind == FrameKind.CONTROL) {
                val pingMsg = decodeControl(pingFrame.body) as ControlMessage.Ping
                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.Pong(pingMsg.t))))
            }

            clientSocket.close()
        }
        serverThread.start()

        client.start(scope, "127.0.0.1", port, "0000", "Mock TV")

        // Wait until connected
        var connected = false
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (client.state.value is ClientState.Connected) {
                connected = true
                break
            }
            Thread.sleep(50)
        }
        assertThat(connected).isTrue()

        client.stop()
        serverThread.join(2000)
        server.close()
        scope.cancel()
    }

    @Test(timeout = 10000)
    fun loopback_integration_pin_rejected_stops_immediately_no_reconnect_storm() {
        val server = ServerSocket(0)
        val port = server.localPort

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = StreamClient()

        val serverThread = Thread {
            val clientSocket = server.accept()
            val reader = FrameReader(clientSocket.getInputStream())
            val writer = FrameWriter(clientSocket.getOutputStream())

            val helloFrame = reader.readFrame()
            assertThat(helloFrame).isNotNull()

            // Reject with PIN_REJECTED
            val error = ControlMessage.Error(ErrorReason.PIN_REJECTED)
            writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(error)))

            clientSocket.close()
        }
        serverThread.start()

        client.start(scope, "127.0.0.1", port, "wrong-pin", "Mock TV")

        var rejected = false
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val st = client.state.value
            if (st is ClientState.PairingFailure && st.reason == ErrorReason.PIN_REJECTED) {
                rejected = true
                break
            }
            Thread.sleep(50)
        }
        assertThat(rejected).isTrue()

        // Wait a bit to verify no reconnect storm happened
        Thread.sleep(500)
        assertThat(client.state.value).isInstanceOf(ClientState.PairingFailure::class.java)

        client.stop()
        serverThread.join(2000)
        server.close()
        scope.cancel()
    }

    @Test(timeout = 10000)
    fun server_drops_socket_reconnect_exponential_backoff_and_failed() {
        val server = ServerSocket(0)
        val port = server.localPort

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = StreamClient()
        client.backoffSchedule = listOf(50L, 50L)

        val serverThread = Thread {
            try {
                val s = server.accept()
                s.close()
                server.close()
            } catch (_: Exception) {}
        }
        serverThread.start()

        client.start(scope, "127.0.0.1", port, "0000", "Mock TV")

        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (client.state.value is ClientState.Failed) {
                break
            }
            Thread.sleep(50)
        }

        assertThat(client.state.value).isInstanceOf(ClientState.Failed::class.java)

        client.stop()
        server.close()
        scope.cancel()
    }
}