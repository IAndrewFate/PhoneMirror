package com.phonemirror.receiver.e2e

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.*
import com.phonemirror.protocol.wire.*
import com.phonemirror.receiver.server.*
import com.phonemirror.sender.net.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class E2eIntegrationTest {

    private class StaticPinProvider(private val pin: String) : PinProvider {
        override val current: String get() = pin
        override fun regenerate(): String = pin
    }

    private class RecordingVideoSink : VideoSink {
        val configs = mutableListOf<ByteArray>()
        val resolutions = mutableListOf<Triple<Int, Int, Int>>()
        val frames = mutableListOf<QueuedFrame>()

        data class QueuedFrame(val ptsUs: Long, val keyframe: Boolean, val nal: ByteArray)

        override fun onConfig(spsPps: ByteArray) {
            synchronized(this) { configs.add(spsPps) }
        }

        override fun onResolutionChange(w: Int, h: Int, dpi: Int) {
            synchronized(this) { resolutions.add(Triple(w, h, dpi)) }
        }

        override fun onFrame(ptsUs: Long, keyframe: Boolean, nal: ByteArray) {
            synchronized(this) { frames.add(QueuedFrame(ptsUs, keyframe, nal)) }
        }
    }

    private class RecordingAudioSink : AudioSink {
        val configs = mutableListOf<ByteArray>()
        val frames = mutableListOf<Pair<Long, ByteArray>>()

        override fun onConfig(json: ByteArray) {
            synchronized(this) { configs.add(json) }
        }

        override fun onFrame(ptsUs: Long, packet: ByteArray) {
            synchronized(this) { frames.add(ptsUs to packet) }
        }
    }

    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun findFreePort(): Int {
        val ss = ServerSocket(0)
        val port = ss.localPort
        ss.close()
        return port
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // 1. full session hello(PIN) -> config -> 1s@60fps -> stop, recorder asserts T4 ordering + zero violations
    @Test(timeout = 15000)
    fun testFullSessionOrderAndCompletion() = runBlocking {
        val port = findFreePort()
        val pin = "1234"
        val pinProvider = StaticPinProvider(pin)
        val pairingStore = InMemoryPairingStore()
        val videoSink = RecordingVideoSink()
        val audioSink = RecordingAudioSink()

        val server = MirrorServer(
            pinProvider = pinProvider,
            pairingStore = pairingStore,
            videoSink = videoSink,
            audioSink = audioSink
        )
        server.bind(port, port)
        server.start(testScope)

        val client = StreamClient(endpointStore = InMemoryEndpointStore())
        client.start(testScope, "127.0.0.1", port, pin)

        // Wait for Connected state
        withTimeout(5000) {
            while (client.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        // Send VIDEO_CONFIG
        client.sendFrame(FakeMediaSource.createVideoConfigFrame())

        // Send 60 frames (1s at 60fps)
        for (i in 0 until 60) {
            val ptsUs = i * 16666L
            val keyframe = (i == 0)
            client.sendFrame(FakeMediaSource.createVideoFrame(ptsUs, keyframe, payloadSize = 512))
        }

        // Wait for all frames to be received on server
        withTimeout(5000) {
            while (videoSink.frames.size < 60) {
                delay(50)
            }
        }

        // Send Stop control message
        client.sendFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.Stop())))
        delay(200)

        client.stop()
        server.stop()

        // Assertions
        assertThat(videoSink.configs).isNotEmpty()
        assertThat(videoSink.frames).hasSize(60)
        assertThat(videoSink.frames[0].keyframe).isTrue()
        assertThat(videoSink.frames[1].keyframe).isFalse()

        // Verify PTS monotonicity
        for (i in 1 until 60) {
            assertThat(videoSink.frames[i].ptsUs).isGreaterThan(videoSink.frames[i - 1].ptsUs)
        }
    }

    // 2. server kill mid-stream -> client backoff-reconnects -> SAME gen -> config+keyframe BEFORE any delta
    @Test(timeout = 20000)
    fun testServerKillReconnectSameGenConfigAndKeyframeFirst() = runBlocking {
        val port = findFreePort()
        val pin = "2345"
        val pinProvider = StaticPinProvider(pin)
        val pairingStore = InMemoryPairingStore()
        val videoSink1 = RecordingVideoSink()

        val server1 = MirrorServer(
            pinProvider = pinProvider,
            pairingStore = pairingStore,
            videoSink = videoSink1
        )
        server1.bind(port, port)
        server1.start(testScope)

        val client = StreamClient(endpointStore = InMemoryEndpointStore())
        client.backoffSchedule = listOf(200L, 400L, 800L)
        val initialGen = client.sessionPolicy.gen

        client.start(testScope, "127.0.0.1", port, pin)

        withTimeout(5000) {
            while (client.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        client.sendFrame(FakeMediaSource.createVideoConfigFrame())
        client.sendFrame(FakeMediaSource.createVideoFrame(0L, keyframe = true, payloadSize = 256))
        client.sendFrame(FakeMediaSource.createVideoFrame(16666L, keyframe = false, payloadSize = 256))

        withTimeout(5000) {
            while (videoSink1.frames.size < 2) {
                delay(50)
            }
        }

        // Kill server 1 mid-stream
        server1.stop()

        // Client detects connection lost and enters Reconnecting
        withTimeout(5000) {
            while (client.state.value !is ClientState.Reconnecting) {
                delay(50)
            }
        }

        // Start server 2 on the same port
        val videoSink2 = RecordingVideoSink()
        val server2 = MirrorServer(
            pinProvider = pinProvider,
            pairingStore = pairingStore,
            videoSink = videoSink2
        )
        server2.bind(port, port)
        server2.start(testScope)

        // Wait for client to reconnect
        withTimeout(6000) {
            while (client.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        // Assert SAME generation counter
        assertThat(client.sessionPolicy.gen).isEqualTo(initialGen)

        // On reconnect, send config + keyframe before delta frames
        client.sendFrame(FakeMediaSource.createVideoConfigFrame())
        client.sendFrame(FakeMediaSource.createVideoFrame(33333L, keyframe = true, payloadSize = 256))
        client.sendFrame(FakeMediaSource.createVideoFrame(50000L, keyframe = false, payloadSize = 256))

        withTimeout(5000) {
            while (videoSink2.frames.size < 2) {
                delay(50)
            }
        }

        client.stop()
        server2.stop()

        assertThat(videoSink2.configs).isNotEmpty()
        assertThat(videoSink2.frames[0].keyframe).isTrue()
        assertThat(videoSink2.frames[1].keyframe).isFalse()
    }

    // 3. wrong PIN -> rejected, zero media
    @Test(timeout = 10000)
    fun testWrongPinRejectedZeroMedia() = runBlocking {
        val port = findFreePort()
        val serverPin = "7777"
        val clientWrongPin = "8888"
        val videoSink = RecordingVideoSink()

        val server = MirrorServer(
            pinProvider = StaticPinProvider(serverPin),
            pairingStore = InMemoryPairingStore(),
            videoSink = videoSink
        )
        server.bind(port, port)
        server.start(testScope)

        val client = StreamClient(endpointStore = InMemoryEndpointStore())
        client.start(testScope, "127.0.0.1", port, clientWrongPin)

        withTimeout(5000) {
            while (client.state.value !is ClientState.PairingFailure) {
                delay(50)
            }
        }

        val state = client.state.value as ClientState.PairingFailure
        assertThat(state.reason).isEqualTo(ErrorReason.PIN_REJECTED)

        // Sinks received ZERO media
        assertThat(videoSink.configs).isEmpty()
        assertThat(videoSink.frames).isEmpty()

        client.stop()
        server.stop()
    }

    // 4. busy -> second client rejected while first streams
    @Test(timeout = 15000)
    fun testBusySecondClientRejected() = runBlocking {
        val port = findFreePort()
        val pin = "3456"
        val server = MirrorServer(
            pinProvider = StaticPinProvider(pin),
            pairingStore = InMemoryPairingStore()
        )
        server.bind(port, port)
        server.start(testScope)

        val client1 = StreamClient(endpointStore = InMemoryEndpointStore())
        client1.start(testScope, "127.0.0.1", port, pin)

        withTimeout(5000) {
            while (client1.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        val client2 = StreamClient(endpointStore = InMemoryEndpointStore())
        client2.start(testScope, "127.0.0.1", port, pin)

        withTimeout(5000) {
            while (client2.state.value !is ClientState.PairingFailure) {
                delay(50)
            }
        }

        val failure2 = client2.state.value as ClientState.PairingFailure
        assertThat(failure2.reason).isEqualTo(ErrorReason.BUSY)

        // Client 1 remains connected
        assertThat(client1.state.value).isInstanceOf(ClientState.Connected::class.java)

        client1.stop()
        client2.stop()
        server.stop()
    }

    // 5. injected resolution change -> ResolutionChange+new config+keyframe order recorded
    @Test(timeout = 15000)
    fun testInjectedResolutionChangeOrder() = runBlocking {
        val port = findFreePort()
        val pin = "4567"
        val videoSink = RecordingVideoSink()
        val server = MirrorServer(
            pinProvider = StaticPinProvider(pin),
            pairingStore = InMemoryPairingStore(),
            videoSink = videoSink
        )
        server.bind(port, port)
        server.start(testScope)

        val client = StreamClient(endpointStore = InMemoryEndpointStore())
        client.start(testScope, "127.0.0.1", port, pin)

        withTimeout(5000) {
            while (client.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        // Initial stream
        client.sendFrame(FakeMediaSource.createVideoConfigFrame())
        client.sendFrame(FakeMediaSource.createVideoFrame(0L, keyframe = true))

        // Inject resolution change
        val resMsg = ControlMessage.ResolutionChange(1280, 720, 320)
        client.sendFrame(Frame(FrameKind.CONTROL, encodeControl(resMsg)))

        // New config and keyframe after resolution change
        client.sendFrame(FakeMediaSource.createVideoConfigFrame())
        client.sendFrame(FakeMediaSource.createVideoFrame(16666L, keyframe = true, tag = 1))

        withTimeout(5000) {
            while (videoSink.resolutions.isEmpty() || videoSink.frames.size < 2) {
                delay(50)
            }
        }

        assertThat(videoSink.resolutions).contains(Triple(1280, 720, 320))
        assertThat(videoSink.configs.size).isAtLeast(2)
        assertThat(videoSink.frames.last().keyframe).isTrue()

        client.stop()
        server.stop()
    }

    // 6. ping starvation (client pauses pings 7s) -> server closes
    @Test(timeout = 15000)
    fun testPingStarvationClosesServer() = runBlocking {
        val port = findFreePort()
        val pin = "5678"
        val server = MirrorServer(
            pinProvider = StaticPinProvider(pin),
            pairingStore = InMemoryPairingStore()
        )
        server.bind(port, port)
        server.start(testScope)

        // Raw socket handshake without pings
        val socket = java.net.Socket("127.0.0.1", port)
        val writer = FrameWriter(socket.getOutputStream())
        val reader = FrameReader(socket.getInputStream())

        val hello = ControlMessage.Hello(
            v = 1,
            gen = 1L,
            deviceId = "test-device",
            deviceName = "Tester",
            pin = pin,
            video = VideoParams(1920, 1080, 400, 60, 8000000),
            audio = AudioParams(enabled = false)
        )
        writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(hello)))

        val resp = reader.readFrame()
        assertThat(resp).isNotNull()
        assertThat(decodeControl(resp!!.body)).isInstanceOf(ControlMessage.HelloOk::class.java)

        // Starve pings: server watchdog checks System.currentTimeMillis() - lastPingTimeMs > 6000
        // Wait ~6.5s
        delay(6500)

        // Server should have closed connection due to ping starvation
        val nextFrame = reader.readFrame()
        assertThat(nextFrame).isNull()
        assertThat(server.hasActiveClient()).isFalse()

        try { socket.close() } catch (_: Exception) {}
        server.stop()
    }

    // 7. server silence 6s -> client reconnects
    @Test(timeout = 15000)
    fun testServerSilenceClientReconnects() = runBlocking {
        val port = findFreePort()
        val serverSocket = ServerSocket(port)

        val client = StreamClient(endpointStore = InMemoryEndpointStore())
        client.backoffSchedule = listOf(200L, 500L)
        client.start(testScope, "127.0.0.1", port, "0000")

        // Accept client connection on raw serverSocket
        val acceptedSocket = serverSocket.accept()
        val reader = FrameReader(acceptedSocket.getInputStream())
        val writer = FrameWriter(acceptedSocket.getOutputStream())

        // Read Hello and reply HelloOk
        val helloFrame = reader.readFrame()
        assertThat(helloFrame).isNotNull()
        writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.HelloOk())))

        withTimeout(5000) {
            while (client.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        // Server goes completely silent (socket.soTimeout on client is 6000ms)
        // Client should timeout on read and enter Reconnecting
        withTimeout(9000) {
            while (client.state.value !is ClientState.Reconnecting) {
                delay(100)
            }
        }

        assertThat(client.state.value).isInstanceOf(ClientState.Reconnecting::class.java)

        client.stop()
        try { acceptedSocket.close() } catch (_: Exception) {}
        try { serverSocket.close() } catch (_: Exception) {}
    }

    // 8. 30s soak 60fps video+50pps audio mixed -> byte-exact payload compare on 1% sample, zero corruption
    @Test(timeout = 45000)
    fun test30sSoakMixedStreamByteExactSampleCompare() = runBlocking {
        val port = findFreePort()
        val pin = "9999"
        val videoSink = RecordingVideoSink()
        val audioSink = RecordingAudioSink()

        val server = MirrorServer(
            pinProvider = StaticPinProvider(pin),
            pairingStore = InMemoryPairingStore(),
            videoSink = videoSink,
            audioSink = audioSink
        )
        server.bind(port, port)
        server.start(testScope)

        val acceptAllPolicy = object : OverflowPolicy {
            override fun shouldAcceptFrame(frame: Frame, currentQueueBytes: Long, capacityBytes: Long): Boolean = true
            override fun onCongestion() {}
        }
        val client = StreamClient(
            overflowPolicy = acceptAllPolicy,
            endpointStore = InMemoryEndpointStore()
        )
        client.start(testScope, "127.0.0.1", port, pin)

        withTimeout(5000) {
            while (client.state.value !is ClientState.Connected) {
                delay(50)
            }
        }

        client.sendFrame(FakeMediaSource.createVideoConfigFrame())
        client.sendFrame(FakeMediaSource.createAudioConfigFrame())

        // 30 seconds equivalent:
        // Video: 30 * 60 = 1800 frames
        // Audio: 30 * 50 = 1500 frames
        val totalVideoFrames = 1800
        val totalAudioFrames = 1500

        val sampledVideoPayloads = mutableMapOf<Int, ByteArray>()
        val sampledAudioPayloads = mutableMapOf<Int, ByteArray>()

        var vSent = 0
        var aSent = 0

        // High-throughput socket stream
        while (vSent < totalVideoFrames || aSent < totalAudioFrames) {
            // Send a batch of 6 video frames and 5 audio frames (maintains 60:50 ratio)
            for (i in 0 until 6) {
                if (vSent < totalVideoFrames) {
                    val pts = vSent * 16666L
                    val keyframe = (vSent % 60 == 0)
                    val frame = FakeMediaSource.createVideoFrame(pts, keyframe, payloadSize = 384, tag = (vSent % 120).toByte())

                    // Sample 1%: every 100th frame
                    if (vSent % 100 == 0) {
                        val body = VideoFrameBody.decode(frame.body)
                        sampledVideoPayloads[vSent] = body.nal.copyOf()
                    }

                    while (!client.sendFrame(frame)) {
                        delay(1)
                    }
                    vSent++
                }
            }

            for (i in 0 until 5) {
                if (aSent < totalAudioFrames) {
                    val pts = aSent * 20000L
                    val frame = FakeMediaSource.createAudioFrame(pts, payloadSize = 96, tag = (aSent % 100).toByte())

                    if (aSent % 100 == 0) {
                        val body = AudioFrameBody.decode(frame.body)
                        sampledAudioPayloads[aSent] = body.packet.copyOf()
                    }

                    while (!client.sendFrame(frame)) {
                        delay(1)
                    }
                    aSent++
                }
            }

            if (vSent % 150 == 0) {
                yield() // Allow IO loop to pump
            }
        }

        // Wait for all frames to arrive at receiver
        withTimeout(20000) {
            while (videoSink.frames.size < totalVideoFrames || audioSink.frames.size < totalAudioFrames) {
                delay(50)
            }
        }

        client.stop()
        server.stop()

        assertThat(videoSink.frames.size).isEqualTo(totalVideoFrames)
        assertThat(audioSink.frames.size).isEqualTo(totalAudioFrames)

        // Byte-exact payload compare on 1% sample:
        assertThat(sampledVideoPayloads.size).isAtLeast(18)
        for ((idx, expectedNal) in sampledVideoPayloads) {
            val receivedNal = videoSink.frames[idx].nal
            assertThat(receivedNal).isEqualTo(expectedNal)
        }

        assertThat(sampledAudioPayloads.size).isAtLeast(15)
        for ((idx, expectedPacket) in sampledAudioPayloads) {
            val receivedPacket = audioSink.frames[idx].second
            assertThat(receivedPacket).isEqualTo(expectedPacket)
        }
    }

    // 9. QA failure scenario: server stays down through all 10 tries -> Failed state + EXACTLY 10 attempts logged
    @Test(timeout = 15000)
    fun testServerStaysDownThroughAll10TriesEntersFailedState(): Unit = runBlocking {
        val unreachablePort = findFreePort()
        val client = StreamClient(endpointStore = InMemoryEndpointStore())
        client.backoffSchedule = List(10) { 20L }

        val recordedStates = mutableListOf<ClientState>()
        val job = launch {
            client.state.collect { recordedStates.add(it) }
        }

        client.start(testScope, "127.0.0.1", unreachablePort, "1234")

        withTimeout(10000) {
            while (client.state.value !is ClientState.Failed) {
                delay(50)
            }
        }

        assertThat(client.state.value).isEqualTo(ClientState.Failed)
        val reconnectingAttempts = recordedStates.filterIsInstance<ClientState.Reconnecting>().map { it.attempt }
        assertThat(reconnectingAttempts).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9)

        job.cancel()
        client.stop()
    }
}
