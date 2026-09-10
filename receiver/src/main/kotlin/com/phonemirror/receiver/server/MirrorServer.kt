package com.phonemirror.receiver.server

import com.phonemirror.protocol.control.*
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.protocol.wire.FrameReader
import com.phonemirror.protocol.wire.FrameWriter
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

sealed class HandshakeResult {
    data class Success(val hello: ControlMessage.Hello) : HandshakeResult()
    data class Rejected(val reason: ErrorReason) : HandshakeResult()
    data object ProtocolError : HandshakeResult()
}

class MirrorServer(
    val pinProvider: PinProvider = DefaultPinProvider(),
    val pairingStore: PairingStore = InMemoryPairingStore(),
    var videoSink: VideoSink = NoOpVideoSink(),
    var audioSink: AudioSink = NoOpAudioSink(),
    val onStreamingChanged: (Boolean, String?) -> Unit = { _, _ -> }
) {
    var actualBoundPort: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val hasActiveClient = AtomicBoolean(false)
    private var activeDispatcher: StreamDispatcher? = null

    fun bind(startPort: Int = Defaults.BASE_PORT, maxPort: Int = Defaults.MAX_PORT): Int {
        for (port in startPort..maxPort) {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                actualBoundPort = port
                return port
            } catch (_: Exception) {
                // Try next port
            }
        }
        throw IllegalStateException("All ports in range $startPort..$maxPort are busy")
    }

    fun start(scope: CoroutineScope): Job {
        val ss = serverSocket ?: throw IllegalStateException("Server not bound, call bind() first")
        isRunning.set(true)

        return scope.launch(Dispatchers.IO) {
            while (isRunning.get() && !ss.isClosed) {
                try {
                    val socket = ss.accept()
                    if (hasActiveClient.get()) {
                        // Busy: send Error(BUSY) and close
                        launch(Dispatchers.IO) {
                            try {
                                val writer = FrameWriter(socket.getOutputStream())
                                val busyMsg = ControlMessage.Error(ErrorReason.BUSY)
                                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(busyMsg)))
                            } catch (_: Exception) {}
                            finally {
                                try { socket.close() } catch (_: Exception) {}
                            }
                        }
                        continue
                    }

                    launch(Dispatchers.IO) {
                        handleClientConnection(socket, scope)
                    }
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket, scope: CoroutineScope) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()
        val reader = FrameReader(inputStream)
        val writer = FrameWriter(outputStream)

        val handshakeResult = performHandshake(reader, writer, pinProvider, pairingStore)
        when (handshakeResult) {
            is HandshakeResult.Success -> {
                if (hasActiveClient.compareAndSet(false, true)) {
                    val hello = handshakeResult.hello
                    onStreamingChanged(true, hello.deviceName)
                    val dispatcher = StreamDispatcher(
                        inputStream = inputStream,
                        outputStream = outputStream,
                        videoSink = videoSink,
                        audioSink = audioSink,
                        onSessionEnded = {
                            hasActiveClient.set(false)
                            activeDispatcher = null
                            onStreamingChanged(false, null)
                            try { socket.close() } catch (_: Exception) {}
                        }
                    )
                    activeDispatcher = dispatcher
                    dispatcher.start(scope)
                } else {
                    try {
                        val busyMsg = ControlMessage.Error(ErrorReason.BUSY)
                        writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(busyMsg)))
                    } catch (_: Exception) {}
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            else -> {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        activeDispatcher?.stop()
        activeDispatcher = null
        hasActiveClient.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun performHandshake(
            reader: FrameReader,
            writer: FrameWriter,
            pinProvider: PinProvider,
            pairingStore: PairingStore
        ): HandshakeResult {
            val firstFrame = try {
                reader.readFrame()
            } catch (_: Exception) {
                return HandshakeResult.ProtocolError
            } ?: return HandshakeResult.ProtocolError

            if (firstFrame.kind != FrameKind.CONTROL) {
                return HandshakeResult.ProtocolError
            }

            val msg = try {
                decodeControl(firstFrame.body)
            } catch (_: Exception) {
                return HandshakeResult.ProtocolError
            }

            if (msg !is ControlMessage.Hello) {
                return HandshakeResult.ProtocolError
            }

            if (msg.v != 1) {
                val error = ControlMessage.Error(ErrorReason.PROTO_MISMATCH)
                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(error)))
                return HandshakeResult.Rejected(ErrorReason.PROTO_MISMATCH)
            }

            val isTrusted = pairingStore.isTrusted(msg.deviceId)
            if (isTrusted) {
                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.HelloOk())))
                return HandshakeResult.Success(msg)
            }

            if (msg.pin == pinProvider.current) {
                pairingStore.trust(msg.deviceId)
                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(ControlMessage.HelloOk())))
                return HandshakeResult.Success(msg)
            } else {
                val error = ControlMessage.Error(ErrorReason.PIN_REJECTED)
                writer.writeFrame(Frame(FrameKind.CONTROL, encodeControl(error)))
                return HandshakeResult.Rejected(ErrorReason.PIN_REJECTED)
            }
        }
    }
}