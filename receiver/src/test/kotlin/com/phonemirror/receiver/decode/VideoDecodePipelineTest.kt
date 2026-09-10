package com.phonemirror.receiver.decode

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionPolicy
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque

class VideoDecodePipelineTest {

    private class FakeVideoDecoder : VideoDecoder {
        var configuredFormat: VideoDecoderFormat? = null
        var isStarted = false
        var isStopped = false
        var isFlushed = false
        var isReleased = false
        val queuedInputs = mutableListOf<Triple<ByteArray, Long, Boolean>>()
        val outputQueue = ArrayDeque<DecoderOutput>()
        val releaseCalls = mutableListOf<Triple<Int, Boolean, Long?>>()

        override fun configure(format: VideoDecoderFormat, surface: android.view.Surface?) {
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

        override fun queueInput(data: ByteArray, ptsUs: Long, isKeyframe: Boolean): Boolean {
            queuedInputs.add(Triple(data, ptsUs, isKeyframe))
            return true
        }

        override fun dequeueOutput(timeoutUs: Long): DecoderOutput? {
            return if (outputQueue.isNotEmpty()) outputQueue.removeFirst() else DecoderOutput.TryAgainLater
        }

        override fun releaseOutputBuffer(index: Int, render: Boolean, renderTimestampNs: Long?) {
            releaseCalls.add(Triple(index, render, renderTimestampNs))
        }
    }

    private lateinit var sessionPolicy: SessionPolicy
    private lateinit var fakeDecoder: FakeVideoDecoder

    @Before
    fun setUp() {
        sessionPolicy = SessionPolicy(Role.RECEIVER)
        fakeDecoder = FakeVideoDecoder()
    }

    // 1. SPS/PPS split (4-byte start-codes fixture)
    @Test
    fun sps_pps_split_standard_4_byte_start_codes() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x2A, 0x95.toByte())
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

        val fixture = byteArrayOf(0, 0, 0, 1) + spsPayload + byteArrayOf(0, 0, 0, 1) + ppsPayload
        val config = AnnexBParser.parseSpsPps(fixture)

        assertThat(config.sps).isNotNull()
        assertThat(config.pps).isNotNull()

        val spsNals = AnnexBParser.splitNals(config.sps!!)
        assertThat(spsNals).hasSize(1)
        assertThat(spsNals[0].type).isEqualTo(AnnexBParser.NAL_TYPE_SPS)
        assertThat(spsNals[0].payload).isEqualTo(spsPayload)

        val ppsNals = AnnexBParser.splitNals(config.pps!!)
        assertThat(ppsNals).hasSize(1)
        assertThat(ppsNals[0].type).isEqualTo(AnnexBParser.NAL_TYPE_PPS)
        assertThat(ppsNals[0].payload).isEqualTo(ppsPayload)
    }

    // 2. 3-byte start-code variant
    @Test
    fun sps_pps_split_3_byte_start_code_variant() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x1F)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte())

        val fixture = byteArrayOf(0, 0, 1) + spsPayload + byteArrayOf(0, 0, 1) + ppsPayload
        val config = AnnexBParser.parseSpsPps(fixture)

        assertThat(config.sps).isNotNull()
        assertThat(config.pps).isNotNull()

        val spsNals = AnnexBParser.splitNals(config.sps!!)
        assertThat(spsNals[0].type).isEqualTo(AnnexBParser.NAL_TYPE_SPS)
        assertThat(spsNals[0].payload).isEqualTo(spsPayload)

        val ppsNals = AnnexBParser.splitNals(config.pps!!)
        assertThat(ppsNals).hasSize(1)
        assertThat(ppsNals[0].type).isEqualTo(AnnexBParser.NAL_TYPE_PPS)
        assertThat(ppsNals[0].payload).isEqualTo(ppsPayload)
    }

    // 3. SPS+PPS concatenated in ONE config buffer with both start-code styles mixed
    @Test
    fun sps_pps_concatenated_mixed_start_code_styles() {
        val spsPayload = byteArrayOf(0x67, 0x64, 0x00, 0x28)
        val ppsPayload = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte())

        // 4-byte start code for SPS, 3-byte start code for PPS
        val fixture = byteArrayOf(0, 0, 0, 1) + spsPayload + byteArrayOf(0, 0, 1) + ppsPayload
        val config = AnnexBParser.parseSpsPps(fixture)

        assertThat(config.sps).isNotNull()
        assertThat(config.pps).isNotNull()
        val nals = AnnexBParser.splitNals(fixture)
        assertThat(nals).hasSize(2)
        assertThat(nals[0].type).isEqualTo(AnnexBParser.NAL_TYPE_SPS)
        assertThat(nals[1].type).isEqualTo(AnnexBParser.NAL_TYPE_PPS)
    }

    // 4. NAL payload containing 00 00 00 / 00 00 01 byte runs (emulation prevention 00 00 03 01)
    @Test
    fun nal_payload_with_emulation_prevention_does_not_split_internally() {
        // NAL containing 00 00 03 01 - must NOT be treated as start code 00 00 01
        val spsWithEmulation = byteArrayOf(
            0x67, 0x42, 0x00, 0x00, 0x03, 0x01, 0x55
        )
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte())
        val fixture = byteArrayOf(0, 0, 0, 1) + spsWithEmulation + byteArrayOf(0, 0, 0, 1) + ppsPayload

        val nals = AnnexBParser.splitNals(fixture)
        assertThat(nals).hasSize(2) // Exactly 2 NALs, NOT 3
        assertThat(nals[0].type).isEqualTo(AnnexBParser.NAL_TYPE_SPS)
        assertThat(nals[0].payload).isEqualTo(spsWithEmulation)
        assertThat(nals[1].type).isEqualTo(AnnexBParser.NAL_TYPE_PPS)
    }

    // 5. LetterboxRect matrix
    @Test
    fun letterbox_rect_matrix_calculations() {
        // 16:9 on 16:9 screen (exact match)
        val rectExact = LetterboxCalculator.letterboxRect(1920, 1080, 1920, 1080)
        assertThat(rectExact.x).isEqualTo(0)
        assertThat(rectExact.y).isEqualTo(0)
        assertThat(rectExact.width).isEqualTo(1920)
        assertThat(rectExact.height).isEqualTo(1080)

        // 16:9 frame on 4:3 screen (1920x1080 on 1440x1080 screen) -> letterbox (top/bottom bars)
        val rectLetterbox = LetterboxCalculator.letterboxRect(1920, 1080, 1440, 1080)
        assertThat(rectLetterbox.x).isEqualTo(0)
        assertThat(rectLetterbox.width).isEqualTo(1440)
        assertThat(rectLetterbox.height).isEqualTo(810)
        assertThat(rectLetterbox.y).isEqualTo((1080 - 810) / 2) // 135

        // Portrait frame on 16:9 landscape TV (1080x1920 on 1920x1080) -> pillarbox (left/right bars)
        val rectPillarbox = LetterboxCalculator.letterboxRect(1080, 1920, 1920, 1080)
        assertThat(rectPillarbox.y).isEqualTo(0)
        assertThat(rectPillarbox.height).isEqualTo(1080)
        val expectedWidth = (1080.0 * 1080.0 / 1920.0).toInt() // 607
        assertThat(rectPillarbox.width).isEqualTo(expectedWidth)
        assertThat(rectPillarbox.x).isEqualTo((1920 - expectedWidth) / 2)
    }

    // 6. Input queue drop policy: GOP-aligned overflow drop
    @Test
    fun input_queue_overflow_triggers_gop_aligned_drop_and_keyframe_request() {
        var keyframeRequested = false
        val pipeline = VideoDecodePipeline(
            sessionPolicy = sessionPolicy,
            decoderFactory = { fakeDecoder },
            onRequestKeyframe = { keyframeRequested = true }
        )

        val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte())
        pipeline.onConfig(config)
        assertThat(pipeline.awaitKeyframe).isTrue()

        // 1st keyframe clears awaitKeyframe
        pipeline.onFrame(1000L, true, byteArrayOf(0, 0, 0, 1, 0x65, 0x01))
        assertThat(pipeline.awaitKeyframe).isFalse()
        assertThat(pipeline.inputQueue).hasSize(1)

        // Add 2nd frame (queue at max capacity 2)
        pipeline.onFrame(2000L, false, byteArrayOf(0, 0, 0, 1, 0x41, 0x02))
        assertThat(pipeline.inputQueue).hasSize(2)

        // 3rd frame causes overflow: queue cleared, awaitKeyframe set, keyframe requested
        pipeline.onFrame(3000L, false, byteArrayOf(0, 0, 0, 1, 0x41, 0x03))
        assertThat(pipeline.inputQueue).isEmpty()
        assertThat(pipeline.awaitKeyframe).isTrue()
        assertThat(keyframeRequested).isTrue()

        // Incoming P-frame while awaiting keyframe is dropped
        pipeline.onFrame(4000L, false, byteArrayOf(0, 0, 0, 1, 0x41, 0x04))
        assertThat(pipeline.inputQueue).isEmpty()

        // Next keyframe arrives: accepted!
        pipeline.onFrame(5000L, true, byteArrayOf(0, 0, 0, 1, 0x65, 0x05))
        assertThat(pipeline.inputQueue).hasSize(1)
        assertThat(pipeline.awaitKeyframe).isFalse()
    }

    // 7. awaitKeyframe truth table
    @Test
    fun await_keyframe_truth_table() {
        val pipeline = VideoDecodePipeline(
            sessionPolicy = sessionPolicy,
            decoderFactory = { fakeDecoder }
        )

        pipeline.onConfig(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte()))
        assertThat(pipeline.awaitKeyframe).isTrue()

        // Non-keyframe while awaitKeyframe -> dropped
        pipeline.onFrame(1000L, false, byteArrayOf(0, 0, 0, 1, 0x41))
        assertThat(pipeline.inputQueue).isEmpty()
        assertThat(pipeline.droppedFrameCount).isEqualTo(1)

        // Keyframe while awaitKeyframe -> accepted, clears awaitKeyframe
        pipeline.onFrame(2000L, true, byteArrayOf(0, 0, 0, 1, 0x65))
        assertThat(pipeline.inputQueue).hasSize(1)
        assertThat(pipeline.awaitKeyframe).isFalse()

        // Non-keyframe after keyframe -> accepted
        pipeline.onFrame(3000L, false, byteArrayOf(0, 0, 0, 1, 0x41))
        assertThat(pipeline.inputQueue).hasSize(2)
    }

    // 8. RenderDecision passthrough with fake AvSync
    @Test
    fun render_decision_passthrough_now_at_and_drop() {
        var currentDecision: RenderDecision = RenderDecision.Now
        val fakeAvSync = object : AvSync {
            override fun scheduleRender(ptsUs: Long): RenderDecision = currentDecision
        }

        val pipeline = VideoDecodePipeline(
            sessionPolicy = sessionPolicy,
            avSync = fakeAvSync,
            decoderFactory = { fakeDecoder }
        )

        pipeline.onConfig(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte()))

        // Keyframe frame
        fakeDecoder.outputQueue.add(DecoderOutput.Frame(bufferIndex = 0, ptsUs = 1000L, isKeyframe = true))
        currentDecision = RenderDecision.Now
        pipeline.pumpOnce()
        assertThat(fakeDecoder.releaseCalls).contains(Triple(0, true, null))
        assertThat(pipeline.renderedFrameCount).isEqualTo(1)

        // Timed frame RenderDecision.At(ns)
        fakeDecoder.outputQueue.add(DecoderOutput.Frame(bufferIndex = 1, ptsUs = 2000L, isKeyframe = false))
        currentDecision = RenderDecision.At(50_000_000L)
        pipeline.pumpOnce()
        assertThat(fakeDecoder.releaseCalls).contains(Triple(1, true, 50_000_000L))
        assertThat(pipeline.renderedFrameCount).isEqualTo(2)

        // Dropped frame RenderDecision.Drop
        fakeDecoder.outputQueue.add(DecoderOutput.Frame(bufferIndex = 2, ptsUs = 3000L, isKeyframe = false))
        currentDecision = RenderDecision.Drop
        pipeline.pumpOnce()
        assertThat(fakeDecoder.releaseCalls).contains(Triple(2, false, null))
        assertThat(pipeline.droppedFrameCount).isEqualTo(1)
    }

    // 9. onDecoderError surfaces error state
    @Test
    fun onDecoderError_surfaces_error_state() {
        val pipeline = VideoDecodePipeline(
            sessionPolicy = sessionPolicy,
            decoderFactory = { fakeDecoder }
        )

        pipeline.onConfig(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, 0xCE.toByte()))

        fakeDecoder.outputQueue.add(DecoderOutput.Error("Hardware codec fatal failure"))
        pipeline.pumpOnce()

        assertThat(pipeline.state.value).isInstanceOf(VideoDecodeState.Error::class.java)
        val errorState = pipeline.state.value as VideoDecodeState.Error
        assertThat(errorState.message).contains("Hardware codec fatal failure")
    }
}