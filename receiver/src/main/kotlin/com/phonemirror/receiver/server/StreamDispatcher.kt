package com.phonemirror.receiver.server

import com.phonemirror.protocol.control.ControlMessage
import com.phonemirror.protocol.control.decodeControl
import com.phonemirror.protocol.control.encodeControl
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionEvent
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class StreamDispatcher(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val videoSink: VideoSink = NoOpVideoSink(),
    private val audioSink: AudioSink = NoOpAudioSink(),
    private val onSessionEnded: () -> Unit = {}
) {
    private val frameReader = FrameReader(inputStream)
    private val frameWriter = FrameWriter(outputStream)
    val sessionPolicy = SessionPolicy(Role.RECEIVER)

    private val isRunning = AtomicBoolean(true)
    private val sendChannel = Channel<ControlMessage>(Channel.BUFFERED)

    @Volatile
    var lastPingTimeMs: Long = System.currentTimeMillis()
        private set

    fun start(scope: CoroutineScope): Job {
        // Single writer coroutine
        val writerJob = scope.launch(Dispatchers.IO) {
            try {
                for (msg in sendChannel) {
                    if (!isRunning.get()) break
                    val bytes = encodeControl(msg)
                    frameWriter.writeFrame(Frame(FrameKind.CONTROL, bytes))
                }
            } catch (_: Exception) {
            }
        }

        // Keepalive watchdog
        val watchdogJob = scope.launch(Dispatchers.IO) {
            while (isRunning.get()) {
                delay(1000)
                if (System.currentTimeMillis() - lastPingTimeMs > 6000) {
                    stop()
                    break
                }
            }
        }

        // Reader job
        val readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isRunning.get()) {
                    val frame = frameReader.readFrame() ?: break
                    when (frame.kind) {
                        FrameKind.CONTROL -> {
                            val msg = decodeControl(frame.body)
                            when (msg) {
                                is ControlMessage.Ping -> {
                                    lastPingTimeMs = System.currentTimeMillis()
                                    sendControl(ControlMessage.Pong(msg.t))
                                }
                                is ControlMessage.Stop -> {
                                    sessionPolicy.onEvent(SessionEvent.StopRequested)
                                    stop()
                                    break
                                }
                                is ControlMessage.ResolutionChange -> {
                                    sessionPolicy.onEvent(
                                        SessionEvent.ResolutionChangeReceived(msg.width, msg.height, msg.dpi)
                                    )
                                    videoSink.onResolutionChange(msg.width, msg.height, msg.dpi)
                                }
                                else -> {}
                            }
                        }
                        FrameKind.VIDEO_CONFIG -> {
                            sessionPolicy.onEvent(SessionEvent.VideoConfigReceived)
                            videoSink.onConfig(frame.body)
                        }
                        FrameKind.VIDEO_FRAME -> {
                            val body = VideoFrameBody.decode(frame.body)
                            val transition = sessionPolicy.onEvent(
                                SessionEvent.FrameReceived(FrameKind.VIDEO_FRAME, body.keyframe)
                            )
                            if (!transition.isDroppable) {
                                videoSink.onFrame(body.ptsUs, body.keyframe, body.nal)
                            }
                        }
                        FrameKind.AUDIO_CONFIG -> {
                            sessionPolicy.onEvent(SessionEvent.AudioConfigReceived)
                            try {
                                audioSink.onConfig(frame.body)
                            } catch (_: Throwable) {}
                        }
                        FrameKind.AUDIO_FRAME -> {
                            try {
                                val body = AudioFrameBody.decode(frame.body)
                                sessionPolicy.onEvent(
                                    SessionEvent.FrameReceived(FrameKind.AUDIO_FRAME, false)
                                )
                                audioSink.onFrame(body.ptsUs, body.packet)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                stop()
            }
        }

        return scope.launch {
            try {
                readerJob.join()
            } finally {
                watchdogJob.cancel()
                writerJob.cancel()
                onSessionEnded()
            }
        }
    }

    fun sendControl(msg: ControlMessage) {
        sendChannel.trySend(msg)
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            sendChannel.close()
            try {
                inputStream.close()
            } catch (_: Exception) {}
            try {
                outputStream.close()
            } catch (_: Exception) {}
        }
    }
}