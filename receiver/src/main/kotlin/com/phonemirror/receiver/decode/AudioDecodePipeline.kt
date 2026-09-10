package com.phonemirror.receiver.decode

import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.receiver.server.AudioSink
import com.phonemirror.receiver.sync.AvSyncEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

sealed class AudioDecodeState {
    data object Idle : AudioDecodeState()
    data class Running(val codec: String, val sampleRate: Int, val channels: Int) : AudioDecodeState()
    data class Error(val message: String) : AudioDecodeState()
}

class AudioDecodePipeline(
    val sessionPolicy: SessionPolicy,
    val avSync: AvSyncEngine,
    val decoderFactory: () -> AudioDecoder = { AndroidAudioDecoder() },
    val playerFactory: () -> AudioPlayer = { AndroidAudioPlayer() },
    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : AudioSink {

    private val _state = MutableStateFlow<AudioDecodeState>(AudioDecodeState.Idle)
    val state: StateFlow<AudioDecodeState> = _state.asStateFlow()

    private var decoder: AudioDecoder? = null
    private var player: AudioPlayer? = null
    private var isPlaying = false
    private val isRunning = AtomicBoolean(false)
    private var pumpJob: Job? = null

    var decodedFrameCount: Long = 0L
        private set
    var audioUnderruns: Long = 0L
        private set
    var configuredCodec: String? = null
        private set

    override fun onConfig(json: ByteArray) {
        try {
            val jsonStr = json.toString(Charsets.UTF_8)
            val payload = Json.decodeFromString(AudioConfigPayload.serializer(), jsonStr)
            configuredCodec = payload.codec

            val mime = when (payload.codec.lowercase()) {
                "opus", "audio/opus" -> "audio/opus"
                "mp4a-latm", "audio/mp4a-latm", "aac" -> "audio/mp4a-latm"
                else -> "audio/opus"
            }

            val csdBuffers = mutableListOf<ByteArray>()
            if (mime == "audio/opus") {
                if (payload.csd.isNotEmpty()) {
                    payload.csd.forEach { base64 ->
                        csdBuffers.add(Base64.getDecoder().decode(base64))
                    }
                }
                if (csdBuffers.isEmpty()) {
                    csdBuffers.add(buildDefaultOpusHead(payload.sampleRate, payload.channels))
                }
                if (csdBuffers.size < 2) {
                    val delayBuf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(6_500_000L).array()
                    csdBuffers.add(delayBuf)
                }
                if (csdBuffers.size < 3) {
                    val seekBuf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).array()
                    csdBuffers.add(seekBuf)
                }
            } else {
                if (payload.csd.isNotEmpty()) {
                    payload.csd.forEach { base64 ->
                        csdBuffers.add(Base64.getDecoder().decode(base64))
                    }
                } else {
                    csdBuffers.add(byteArrayOf(0x11, 0x90.toByte()))
                }
            }

            val format = AudioDecoderFormat(
                mime = mime,
                sampleRate = payload.sampleRate,
                channelCount = payload.channels,
                csdBuffers = csdBuffers
            )

            val dec = decoder ?: decoderFactory().also { decoder = it }
            dec.stop()
            dec.configure(format)
            dec.start()

            if (player == null) {
                player = playerFactory()
            }

            avSync.notifyAudioConfig(payload.sampleRate)
            _state.value = AudioDecodeState.Running(payload.codec, payload.sampleRate, payload.channels)
            startPump()
        } catch (e: Exception) {
            val msg = "Audio decode configure failed: " + (e.message ?: "unknown")
            _state.value = AudioDecodeState.Error(msg)
            avSync.onAudioError(msg)
        }
    }

    override fun onFrame(ptsUs: Long, packet: ByteArray) {
        avSync.onAudioFrame(ptsUs)
        val dec = decoder ?: return
        dec.queueInput(packet, ptsUs)
    }

    fun pumpOnce(): Boolean {
        val dec = decoder ?: return false
        val output = dec.dequeueOutput(0L) ?: return false
        return when (output) {
            is AudioDecoderOutput.Pcm -> {
                val p = player ?: playerFactory().also { player = it }
                if (!isPlaying) {
                    p.play()
                    isPlaying = true
                }
                if (output.pcm.isNotEmpty()) {
                    p.write(output.pcm, 0, output.pcm.size)
                }
                dec.releaseOutputBuffer(output.bufferIndex)
                decodedFrameCount++
                val underruns = p.underrunCount.toLong()
                if (underruns > audioUnderruns) {
                    audioUnderruns = underruns
                    avSync.updateAudioUnderruns(audioUnderruns)
                }
                true
            }
            is AudioDecoderOutput.TryAgainLater -> false
            is AudioDecoderOutput.FormatChanged -> true
            is AudioDecoderOutput.Error -> {
                _state.value = AudioDecodeState.Error(output.message)
                avSync.onAudioError(output.message)
                false
            }
        }
    }

    private fun startPump() {
        if (isRunning.compareAndSet(false, true)) {
            pumpJob = scope.launch {
                while (isActive && isRunning.get()) {
                    val didWork = pumpOnce()
                    if (!didWork) {
                        delay(5)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        pumpJob?.cancel()
        pumpJob = null
        try { player?.pause() } catch (_: Exception) {}
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        isPlaying = false
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        _state.value = AudioDecodeState.Idle
    }

    fun flush() {
        try { player?.pause() } catch (_: Exception) {}
        try { player?.flush() } catch (_: Exception) {}
        try { decoder?.flush() } catch (_: Exception) {}
        isPlaying = false
        avSync.flush()
    }

    companion object {
        fun buildDefaultOpusHead(sampleRate: Int = 48000, channels: Int = 2): ByteArray {
            val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("OpusHead".toByteArray(Charsets.US_ASCII))
            buf.put(1)
            buf.put(channels.toByte())
            buf.putShort(312.toShort())
            buf.putInt(sampleRate)
            buf.putShort(0)
            buf.put(0)
            return buf.array()
        }
    }
}
