package com.phonemirror.sender.capture

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.AudioFrameBody
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.ProjectionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.Base64

class AudioCapturePipelineTest {

    private class FakeAudioSource : AudioCaptureSource {
        var isStarted = false
        var isStopped = false
        var isReleased = false
        var simulatedTimestampNs: Long? = null
        val pcmQueue = ArrayDeque<ByteArray>()

        override fun start() {
            isStarted = true
        }

        override fun stop() {
            isStopped = true
        }

        override fun release() {
            isReleased = true
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            if (pcmQueue.isEmpty()) return 0
            val chunk = pcmQueue.removeFirst()
            val toCopy = minOf(size, chunk.size)
            System.arraycopy(chunk, 0, buffer, offset, toCopy)
            return toCopy
        }

        override fun getTimestampNs(): Long? = simulatedTimestampNs
    }

    private class FakeAudioEncoder(
        override val codecName: String = "opus",
        val shouldFailConfigure: Boolean = false
    ) : AudioEncoder {
        var isStarted = false
        var isStopped = false
        var isReleased = false
        val inputQueue = ArrayDeque<Pair<ByteArray, Long>>()
        val outputQueue = ArrayDeque<AudioCodecOutput>()

        override fun configure(sampleRate: Int, channels: Int, bitrate: Int) {
            if (shouldFailConfigure) {
                throw IllegalStateException("Encoder initialization failed for ")
            }
        }

        override fun start() {
            isStarted = true
        }

        override fun stop() {
            isStopped = true
        }

        override fun release() {
            isReleased = true
        }

        override fun queueInput(data: ByteArray, offset: Int, size: Int, ptsUs: Long) {
            val chunk = ByteArray(size)
            System.arraycopy(data, offset, chunk, 0, size)
            inputQueue.add(Pair(chunk, ptsUs))
        }

        override fun dequeueOutput(timeoutUs: Long): AudioCodecOutput? {
            return if (outputQueue.isNotEmpty()) outputQueue.removeFirst() else AudioCodecOutput.TryAgainLater
        }
    }

    private lateinit var streamClient: StreamClient
    private lateinit var sessionPolicy: SessionPolicy
    private lateinit var fakeSource: FakeAudioSource
    private lateinit var fakeEncoder: FakeAudioEncoder
    private var fakeTimeNs: Long = 1_000_000_000L

    @Before
    fun setUp() {
        sessionPolicy = SessionPolicy(Role.SENDER)
        streamClient = StreamClient(sessionPolicy = sessionPolicy)
        fakeSource = FakeAudioSource()
        fakeEncoder = FakeAudioEncoder()
        fakeTimeNs = 1_000_000_000L
        ProjectionHolder.reset()
    }

    @After
    fun tearDown() {
        ProjectionHolder.reset()
    }

    // 1. SDK Gate test
    @Test
    fun sdk_gate_disables_audio_below_api_29() {
        val pipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 28, // Below Android 10
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { fakeEncoder }
        )

        assertThat(pipeline.isAudioSupported).isFalse()
        assertThat(pipeline.state.value).isInstanceOf(AudioCaptureState.Disabled::class.java)

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope)
        // Should no-op, source and encoder never started
        assertThat(fakeSource.isStarted).isFalse()
        assertThat(fakeEncoder.isStarted).isFalse()

        scope.cancel()
    }

    @Test
    fun sdk_gate_enables_audio_on_api_29_and_above() {
        ProjectionHolder.setActive(null)
        val pipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 29, // Android 10+
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { fakeEncoder }
        )

        assertThat(pipeline.isAudioSupported).isTrue()

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope)
        assertThat(fakeSource.isStarted).isTrue()
        assertThat(fakeEncoder.isStarted).isTrue()
        assertThat(pipeline.state.value).isInstanceOf(AudioCaptureState.Capturing::class.java)

        pipeline.stop()
        scope.cancel()
    }

    // 2. AUDIO_CONFIG JSON for Opus and fallback AAC
    @Test
    fun audio_config_json_for_opus_and_aac() {
        ProjectionHolder.setActive(null)

        // Test Opus config
        val opusEncoder = FakeAudioEncoder(codecName = "opus")
        val opusPipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 34,
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { opusEncoder },
            clock = { fakeTimeNs }
        )
        val scope = CoroutineScope(Dispatchers.Default)
        opusPipeline.start(scope)

        opusEncoder.outputQueue.add(AudioCodecOutput.Config(listOf(OpusCsdBuilder.buildOpusHead())))
        opusPipeline.pumpOnce()

        val opusConfig = opusPipeline.cachedAudioConfigPayload
        assertThat(opusConfig).isNotNull()
        assertThat(opusConfig!!.codec).isEqualTo("opus")
        assertThat(opusConfig.sampleRate).isEqualTo(48000)
        assertThat(opusConfig.channels).isEqualTo(2)
        assertThat(opusConfig.frameMs).isEqualTo(20)
        assertThat(opusConfig.bitrate).isEqualTo(128_000)
        assertThat(opusConfig.csd).isNotEmpty()

        opusPipeline.stop()

        // Test AAC (mp4a-latm) config
        val aacEncoder = FakeAudioEncoder(codecName = "mp4a-latm")
        val aacPipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 34,
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { aacEncoder },
            clock = { fakeTimeNs }
        )
        aacPipeline.start(scope)
        val aacAsc = byteArrayOf(0x11, 0x90.toByte()) // AAC-LC 48kHz stereo ASC
        aacEncoder.outputQueue.add(AudioCodecOutput.Config(listOf(aacAsc)))
        aacPipeline.pumpOnce()

        val aacConfig = aacPipeline.cachedAudioConfigPayload
        assertThat(aacConfig).isNotNull()
        assertThat(aacConfig!!.codec).isEqualTo("mp4a-latm")
        assertThat(aacConfig.csd).contains(Base64.getEncoder().encodeToString(aacAsc))

        aacPipeline.stop()
        scope.cancel()
    }

    // 3. PTS computation in monotonic nanoTime domain
    @Test
    fun pts_computation_matches_monotonic_nano_domain() {
        val nanoSample = 123_456_789_000L
        val ptsUs = AudioMath.computePtsUs(readEndNanoTime = nanoSample)
        // 20ms chunk duration = 20_000_000 ns
        val expectedPtsUs = (123_456_789_000L - 20_000_000L) / 1000L
        assertThat(ptsUs).isEqualTo(expectedPtsUs)
    }

    // 4. Chunk math verification
    @Test
    fun chunk_math_exact_960_frames_and_3840_bytes() {
        assertThat(AudioMath.SAMPLE_RATE).isEqualTo(48000)
        assertThat(AudioMath.CHANNELS).isEqualTo(2)
        assertThat(AudioMath.FRAME_MS).isEqualTo(20)
        assertThat(AudioMath.SAMPLES_PER_CHUNK).isEqualTo(960)
        assertThat(AudioMath.BYTES_PER_CHUNK).isEqualTo(3840)
    }

    // 5. Silence-tolerance timer (fake clock)
    @Test
    fun silence_tolerance_timer_tolerates_absence_of_audio_buffers() {
        ProjectionHolder.setActive(null)
        val pipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 34,
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { fakeEncoder },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope)

        // Advance fake clock by 4 seconds without any audio buffers (apps with ALLOW_CAPTURE_BY_NONE)
        fakeTimeNs += 4_000_000_000L
        assertThat(pipeline.isSilenceTolerated()).isTrue()
        // Pipeline state remains Capturing without crash
        assertThat(pipeline.state.value).isInstanceOf(AudioCaptureState.Capturing::class.java)

        pipeline.stop()
        scope.cancel()
    }

    // 6. Forced encoder init failure -> graceful degradation
    @Test
    fun forced_encoder_init_failure_transitions_to_error_without_crash() {
        ProjectionHolder.setActive(null)
        val failingEncoder = FakeAudioEncoder(codecName = "unsupported_audio", shouldFailConfigure = true)
        val pipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 34,
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { failingEncoder }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope)

        assertThat(pipeline.state.value).isInstanceOf(AudioCaptureState.Error::class.java)
        // Video capture remains unaffected
        pipeline.stop()
        scope.cancel()
    }

    // 7. RFC 7845 OpusHead structure verification
    @Test
    fun opus_head_builder_rfc7845_structure() {
        val opusHead = OpusCsdBuilder.buildOpusHead(sampleRate = 48000, channels = 2, preSkip = 312)
        assertThat(opusHead.size).isEqualTo(19)
        val magic = String(opusHead.copyOfRange(0, 8), Charsets.US_ASCII)
        assertThat(magic).isEqualTo("OpusHead")
        assertThat(opusHead[8].toInt()).isEqualTo(1) // version 1
        assertThat(opusHead[9].toInt()).isEqualTo(2) // 2 channels
    }

    // 8. Config-first ordering before audio frames
    @Test
    fun config_frame_emitted_before_first_audio_frame() {
        ProjectionHolder.setActive(null)
        val pipeline = AudioCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            sdkInt = 34,
            sourceFactory = { _ -> fakeSource },
            encoderFactory = { fakeEncoder },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope)

        fakeEncoder.outputQueue.add(AudioCodecOutput.Frame(byteArrayOf(0x01, 0x02, 0x03), 1000L))
        pipeline.pumpOnce()

        // Config was sent automatically on first frame
        assertThat(pipeline.isConfigSent).isTrue()

        pipeline.stop()
        scope.cancel()
    }
}