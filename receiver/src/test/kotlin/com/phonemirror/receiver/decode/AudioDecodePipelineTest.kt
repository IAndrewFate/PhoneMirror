package com.phonemirror.receiver.decode

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.receiver.sync.AvSyncEngine
import com.phonemirror.receiver.sync.SyncMode
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.Base64

class AudioDecodePipelineTest {

    private class FakeAudioDecoder : AudioDecoder {
        var configuredFormat: AudioDecoderFormat? = null
        var isStarted = false
        var isStopped = false
        var isFlushed = false
        var isReleased = false
        val queuedInputs = mutableListOf<Pair<ByteArray, Long>>()
        val outputQueue = ArrayDeque<AudioDecoderOutput>()
        val releasedIndices = mutableListOf<Int>()

        override fun configure(format: AudioDecoderFormat) {
            configuredFormat = format
        }

        override fun start() {
            isStarted = true
        }

        override fun stop() {
            isStopped = true
        }

        override fun flush() {
            isFlushed = true
        }

        override fun release() {
            isReleased = true
        }

        override fun queueInput(data: ByteArray, ptsUs: Long): Boolean {
            queuedInputs.add(data to ptsUs)
            return true
        }

        override fun dequeueOutput(timeoutUs: Long): AudioDecoderOutput? {
            return if (outputQueue.isNotEmpty()) outputQueue.removeFirst() else AudioDecoderOutput.TryAgainLater
        }

        override fun releaseOutputBuffer(index: Int) {
            releasedIndices.add(index)
        }
    }

    private class FakeAudioPlayer : AudioPlayer {
        var isPlayed = false
        var isPaused = false
        var isFlushed = false
        var isStopped = false
        var isReleased = false
        val writtenBytes = mutableListOf<ByteArray>()
        override var playbackHeadPositionFrames: Long = 0L
        override var underrunCount: Int = 0

        override fun play() {
            isPlayed = true
        }

        override fun pause() {
            isPaused = true
        }

        override fun flush() {
            isFlushed = true
        }

        override fun stop() {
            isStopped = true
        }

        override fun release() {
            isReleased = true
        }

        override fun write(pcm: ByteArray, offset: Int, size: Int): Int {
            writtenBytes.add(pcm.copyOfRange(offset, offset + size))
            return size
        }
    }

    private lateinit var sessionPolicy: SessionPolicy
    private lateinit var avSync: AvSyncEngine
    private lateinit var fakeDecoder: FakeAudioDecoder
    private lateinit var fakePlayer: FakeAudioPlayer
    private lateinit var pipeline: AudioDecodePipeline

    @Before
    fun setUp() {
        sessionPolicy = SessionPolicy(Role.RECEIVER)
        avSync = AvSyncEngine(audioEnabled = true)
        fakeDecoder = FakeAudioDecoder()
        fakePlayer = FakeAudioPlayer()
        pipeline = AudioDecodePipeline(
            sessionPolicy = sessionPolicy,
            avSync = avSync,
            decoderFactory = { fakeDecoder },
            playerFactory = { fakePlayer }
        )
    }

    // 1. Opus onConfig builds 3 CSD buffers (RFC 7845 OpusHead, delay, seek-pre-roll)
    @Test
    fun onConfig_with_opus_creates_format_with_three_csd_buffers() {
        val payload = AudioConfigPayload(
            codec = "opus",
            sampleRate = 48000,
            channels = 2,
            frameMs = 20,
            bitrate = 128_000,
            csd = emptyList()
        )
        val jsonBytes = Json.encodeToString(AudioConfigPayload.serializer(), payload).toByteArray(Charsets.UTF_8)

        pipeline.onConfig(jsonBytes)

        assertThat(pipeline.configuredCodec).isEqualTo("opus")
        assertThat(pipeline.state.value).isInstanceOf(AudioDecodeState.Running::class.java)

        val format = fakeDecoder.configuredFormat
        assertThat(format).isNotNull()
        assertThat(format!!.mime).isEqualTo("audio/opus")
        assertThat(format.sampleRate).isEqualTo(48000)
        assertThat(format.channelCount).isEqualTo(2)
        assertThat(format.csdBuffers).hasSize(3)

        val opusHeadMagic = String(format.csdBuffers[0].copyOfRange(0, 8), Charsets.US_ASCII)
        assertThat(opusHeadMagic).isEqualTo("OpusHead")
    }

    // 2. AAC onConfig creates format with ASC buffer
    @Test
    fun onConfig_with_aac_creates_format_with_asc_csd_buffer() {
        val payload = AudioConfigPayload(
            codec = "mp4a-latm",
            sampleRate = 48000,
            channels = 2,
            frameMs = 20,
            bitrate = 128_000,
            csd = listOf(Base64.getEncoder().encodeToString(byteArrayOf(0x11, 0x90.toByte())))
        )
        val jsonBytes = Json.encodeToString(AudioConfigPayload.serializer(), payload).toByteArray(Charsets.UTF_8)

        pipeline.onConfig(jsonBytes)

        assertThat(pipeline.configuredCodec).isEqualTo("mp4a-latm")
        val format = fakeDecoder.configuredFormat
        assertThat(format).isNotNull()
        assertThat(format!!.mime).isEqualTo("audio/mp4a-latm")
        assertThat(format.csdBuffers).hasSize(1)
        assertThat(format.csdBuffers[0]).isEqualTo(byteArrayOf(0x11, 0x90.toByte()))
    }

    // 3. onFrame queues packet and pumpOnce writes PCM to player
    @Test
    fun onFrame_queues_packet_and_pumps_pcm_to_player() {
        val payload = AudioConfigPayload(codec = "opus")
        pipeline.onConfig(Json.encodeToString(AudioConfigPayload.serializer(), payload).toByteArray(Charsets.UTF_8))

        val audioPacket = byteArrayOf(0x01, 0x02, 0x03)
        pipeline.onFrame(10_000_000L, audioPacket)

        assertThat(fakeDecoder.queuedInputs).hasSize(1)
        assertThat(fakeDecoder.queuedInputs[0].first).isEqualTo(audioPacket)
        assertThat(fakeDecoder.queuedInputs[0].second).isEqualTo(10_000_000L)

        val pcm = ByteArray(3840) { 0x42 }
        fakeDecoder.outputQueue.add(AudioDecoderOutput.Pcm(bufferIndex = 0, pcm = pcm, ptsUs = 10_000_000L))

        val didPump = pipeline.pumpOnce()
        assertThat(didPump).isTrue()
        assertThat(fakePlayer.isPlayed).isTrue()
        assertThat(fakePlayer.writtenBytes).hasSize(1)
        assertThat(fakePlayer.writtenBytes[0]).isEqualTo(pcm)
        assertThat(fakeDecoder.releasedIndices).contains(0)
        assertThat(pipeline.decodedFrameCount).isEqualTo(1)
    }

    // 4. Underrun reporting to AvSync
    @Test
    fun underrun_reporting_to_avSync() {
        val payload = AudioConfigPayload(codec = "opus")
        pipeline.onConfig(Json.encodeToString(AudioConfigPayload.serializer(), payload).toByteArray(Charsets.UTF_8))

        fakePlayer.underrunCount = 5
        fakeDecoder.outputQueue.add(AudioDecoderOutput.Pcm(bufferIndex = 0, pcm = ByteArray(10), ptsUs = 10_000_000L))

        pipeline.pumpOnce()

        assertThat(pipeline.audioUnderruns).isEqualTo(5)
        assertThat(avSync.audioUnderruns).isEqualTo(5)
    }

    // 5. Decoder error transitions to Error state and notifies AvSync
    @Test
    fun decoder_error_transitions_to_error_state_and_notifies_avSync() {
        val payload = AudioConfigPayload(codec = "opus")
        pipeline.onConfig(Json.encodeToString(AudioConfigPayload.serializer(), payload).toByteArray(Charsets.UTF_8))

        fakeDecoder.outputQueue.add(AudioDecoderOutput.Error("Opus native decode error"))
        val didPump = pipeline.pumpOnce()

        assertThat(didPump).isFalse()
        assertThat(pipeline.state.value).isInstanceOf(AudioDecodeState.Error::class.java)
        val errorState = pipeline.state.value as AudioDecodeState.Error
        assertThat(errorState.message).contains("Opus native decode error")

        assertThat(avSync.syncMode).isEqualTo(SyncMode.VIDEO_IMMEDIATE)
    }

    // 6. Flush and stop lifecycle
    @Test
    fun flush_and_stop_lifecycle() {
        val payload = AudioConfigPayload(codec = "opus")
        pipeline.onConfig(Json.encodeToString(AudioConfigPayload.serializer(), payload).toByteArray(Charsets.UTF_8))

        pipeline.flush()
        assertThat(fakePlayer.isFlushed).isTrue()
        assertThat(fakeDecoder.isFlushed).isTrue()

        pipeline.stop()
        assertThat(fakePlayer.isStopped).isTrue()
        assertThat(fakePlayer.isReleased).isTrue()
        assertThat(fakeDecoder.isStopped).isTrue()
        assertThat(fakeDecoder.isReleased).isTrue()
        assertThat(pipeline.state.value).isEqualTo(AudioDecodeState.Idle)
    }
}
