package com.phonemirror.sender.net

import com.phonemirror.protocol.control.*
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionEvent
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.protocol.wire.FrameReader
import com.phonemirror.protocol.wire.FrameWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface SocketFactory {
    fun createSocket(): Socket
}

object DefaultSocketFactory : SocketFactory {
    override fun createSocket(): Socket = Socket()
}

class StreamClient(
    val sessionPolicy: SessionPolicy = SessionPolicy(Role.SENDER),
    val overflowPolicy: OverflowPolicy = RealOverflowPolicy(),
    val endpointStore: EndpointStore = InMemoryEndpointStore(),
    private val socketFactory: SocketFactory = DefaultSocketFactory,
    var configuredBitrateBps: Int = 8_000_000,
    var videoParams: VideoParams = VideoParams(1920, 1080, 400, 60, 8_000_000),
    var audioParams: AudioParams = AudioParams(enabled = true),
    val onRequestKeyframe: () -> Unit = {}
) {
    private val _state = MutableStateFlow<ClientState>(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private val _rttMs = MutableStateFlow<Long>(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private val isRunning = AtomicBoolean(false)
    private var clientJob: Job? = null

    // Send queue: bounded by bytes
    val sendQueue = java.util.ArrayDeque<Frame>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private val currentQueueBytes = AtomicLong(0)

    init {
        if (overflowPolicy is RealOverflowPolicy) {
            overflowPolicy.onRequestKeyframe = { onRequestKeyframe() }
            overflowPolicy.onBitrateChanged = { newBitrate ->
                configuredBitrateBps = newBitrate
                videoParams = videoParams.copy(bitrate = newBitrate)
            }
        }
    }

    var backoffSchedule: List<Long> = listOf(1000L, 2000L, 4000L, 8000L, 8000L, 8000L, 8000L, 8000L, 8000L, 8000L)

    fun calculateCapacityBytes(): Long {
        return (configuredBitrateBps * 0.3 / 8.0).toLong().coerceIn(256 * 1024, 1024 * 1024)
    }

    fun start(scope: CoroutineScope, host: String, port: Int, pin: String, tvName: String = "TV") {
        if (isRunning.compareAndSet(false, true)) {
            clientJob = scope.launch(Dispatchers.IO) {
                runClientLoop(host, port, pin, tvName)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        clientJob?.cancel()
        clientJob = null
        queueSignal.trySend(Unit)
        synchronized(sendQueue) {
            sendQueue.clear()
            currentQueueBytes.set(0)
        }
        _state.value = ClientState.Idle
    }

    fun sendFrame(frame: Frame): Boolean {
        val capacity = calculateCapacityBytes()
        val accepted = synchronized(sendQueue) {
            overflowPolicy.handleEnqueue(frame, sendQueue, currentQueueBytes, capacity)
        }
        if (accepted) {
            queueSignal.trySend(Unit)
        }
        return accepted
    }

    private suspend fun runClientLoop(host: String, port: Int, pin: String, tvName: String) {
        var attempt = 0
        while (isRunning.get() && attempt < backoffSchedule.size) {
            _state.value = if (attempt == 0) ClientState.Connecting(host, port) else ClientState.Reconnecting(attempt)

            val socket = socketFactory.createSocket()
            try {
                socket.tcpNoDelay = true
                socket.sendBufferSize = 128 * 1024
                socket.connect(InetSocketAddress(host, port), 3000)

                val inputStream = socket.getInputStream()
                val outputStream = socket.getOutputStream()
                val reader = FrameReader(inputStream)
                val writer = FrameWriter(outputStream)

                // Handshake
                val model = try { android.os.Build.MODEL } catch (_: Throwable) { null }
                val devName = if (!model.isNullOrBlank()) model else "Android"
                val hello = ControlMessage.Hello(
                    v = 1,
                    gen = sessionPolicy.gen,
                    deviceId = endpointStore.getDeviceId(),
                    deviceName = devName,
                    pin = pin,
                    video = videoParams,
                    audio = audioParams
                )
                sessionPolicy.onEvent(SessionEvent.HelloSent(hello))
                socket.soTimeout = 3000
                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(hello)))

                val respFrame = reader.readFrame()
                if (respFrame == null || respFrame.kind != FrameKind.CONTROL) {
                    throw IllegalStateException("Invalid initial response from server")
                }
                val respMsg = decodeControl(respFrame.body)
                if (respMsg is ControlMessage.Error) {
                    _state.value = ClientState.PairingFailure(respMsg.reason)
                    // Terminal pairing failure: NO retry
                    isRunning.set(false)
                    socket.close()
                    return
                }

                if (respMsg !is ControlMessage.HelloOk) {
                    throw IllegalStateException("Expected HelloOk but got $respMsg")
                }

                // Success!
                endpointStore.saveEndpoint(host, port, tvName)
                endpointStore.savePin(pin)
                sessionPolicy.onEvent(SessionEvent.HelloOkReceived)
                _state.value = ClientState.Connected(host, port)
                attempt = 0 // reset reconnect attempts on successful handshake

                coroutineScope {
                    // Writer coroutine
                    val writerJob = launch(Dispatchers.IO) {
                        drainSendQueue(outputStream, writer)
                    }

                    // Ping coroutine
                    val pingJob = launch(Dispatchers.IO) {
                        while (isActive && isRunning.get()) {
                            delay(2000)
                            val now = System.currentTimeMillis()
                            val pingMsg = ControlMessage.Ping(now)
                            writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(pingMsg)))
                        }
                    }

                    // Reader coroutine
                    try {
                        readLoop(reader, socket)
                    } finally {
                        writerJob.cancel()
                        pingJob.cancel()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                sessionPolicy.onEvent(SessionEvent.ConnectionLost)
                try { socket.close() } catch (_: Exception) {}

                if (!isRunning.get()) break

                if (attempt < backoffSchedule.size) {
                    val delayMs = backoffSchedule[attempt]
                    attempt++
                    if (attempt >= backoffSchedule.size) {
                        _state.value = ClientState.Failed
                        isRunning.set(false)
                        break
                    }
                    _state.value = ClientState.Reconnecting(attempt)
                    delay(delayMs)
                    sessionPolicy.onEvent(SessionEvent.Reconnected)
                }
            }
        }

        if (attempt >= backoffSchedule.size) {
            _state.value = ClientState.Failed
            isRunning.set(false)
        }
    }

    private suspend fun drainSendQueue(outputStream: OutputStream, writer: FrameWriter) {
        while (isRunning.get()) {
            val frame: Frame? = synchronized(sendQueue) {
                if (sendQueue.isNotEmpty()) sendQueue.removeFirst() else null
            }
            if (frame != null) {
                val startMs = System.currentTimeMillis()
                writer.writeFrame(frame)
                currentQueueBytes.addAndGet(-frame.body.size.toLong())

                val writeDuration = System.currentTimeMillis() - startMs
                if (writeDuration > 150) {
                    // Write stall past 150ms -> congestion signal
                    overflowPolicy.onCongestion()
                }
            } else {
                try {
                    queueSignal.receive()
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun readLoop(reader: FrameReader, socket: Socket) {
        var lastReceivedTime = System.currentTimeMillis()
        while (isRunning.get() && !socket.isClosed) {
            socket.soTimeout = 6000 // Keepalive timeout 6s
            val frame = reader.readFrame() ?: break
            lastReceivedTime = System.currentTimeMillis()

            if (frame.kind == FrameKind.CONTROL) {
                when (val msg = decodeControl(frame.body)) {
                    is ControlMessage.Pong -> {
                        val rtt = System.currentTimeMillis() - msg.t
                        _rttMs.value = rtt.coerceAtLeast(0)
                    }
                    is ControlMessage.RequestKeyframe -> {
                        onRequestKeyframe()
                    }
                    is ControlMessage.Stop -> {
                        sessionPolicy.onEvent(SessionEvent.StopRequested)
                        stop()
                        break
                    }
                    else -> {}
                }
            }
        }
    }

    fun notifyState(newState: ClientState) {
        _state.value = newState
    }
}