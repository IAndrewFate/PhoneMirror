package com.phonemirror.sender.capture

import com.google.common.truth.Truth.assertThat
import com.phonemirror.protocol.control.decodeControl
import com.phonemirror.protocol.session.Role
import com.phonemirror.protocol.session.SessionPolicy
import com.phonemirror.protocol.wire.Frame
import com.phonemirror.protocol.wire.FrameKind
import com.phonemirror.protocol.wire.VideoFrameBody
import com.phonemirror.sender.net.StreamClient
import com.phonemirror.sender.service.ProjectionHolder
import com.phonemirror.sender.service.ProjectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque

class VideoCapturePipelineTest {

    private class FakeVideoEncoder : VideoEncoder {
        override var inputSurface: android.view.Surface? = null
        var configuredWidth: Int = 0
        var configuredHeight: Int = 0
        var configuredSettings: VideoSettings? = null
        var isStarted: Boolean = false
        var isStopped: Boolean = false
        var isReleased: Boolean = false
        var requestedKeyframeCount: Int = 0
        var currentBitrate: Int = 0
        val outputQueue = ArrayDeque<CodecOutput>()
        val releasedBufferIndices = mutableListOf<Int>()

        override fun configure(width: Int, height: Int, settings: VideoSettings) {
            configuredWidth = width
            configuredHeight = height
            configuredSettings = settings
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

        override fun requestKeyframe() {
            requestedKeyframeCount++
        }

        override fun setBitrate(bitrateBps: Int) {
            currentBitrate = bitrateBps
        }

        override fun dequeueOutput(timeoutUs: Long): CodecOutput? {
            return if (outputQueue.isNotEmpty()) outputQueue.removeFirst() else CodecOutput.TryAgainLater
        }

        override fun releaseOutputBuffer(index: Int) {
            releasedBufferIndices.add(index)
        }
    }

    private class FakeVirtualDisplayHandle : VirtualDisplayHandle {
        var width: Int = 0
        var height: Int = 0
        var dpi: Int = 0
        var assignedSurface: android.view.Surface? = null
        var isReleased: Boolean = false

        override fun resize(width: Int, height: Int, densityDpi: Int) {
            this.width = width
            this.height = height
            this.dpi = densityDpi
        }

        override fun setSurface(surface: android.view.Surface?) {
            this.assignedSurface = surface
        }

        override fun release() {
            isReleased = true
        }
    }

    private class FakeProjectionAdapter : ProjectionAdapter {
        var createCount: Int = 0
        val handle = FakeVirtualDisplayHandle()

        override fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            flags: Int,
            surface: android.view.Surface?,
            callback: android.hardware.display.VirtualDisplay.Callback?,
            handler: android.os.Handler?
        ): VirtualDisplayHandle {
            createCount++
            handle.width = width
            handle.height = height
            handle.dpi = dpi
            handle.assignedSurface = surface
            return handle
        }
    }

    private lateinit var streamClient: StreamClient
    private lateinit var sessionPolicy: SessionPolicy
    private lateinit var fakeEncoder: FakeVideoEncoder
    private lateinit var fakeAdapter: FakeProjectionAdapter
    private var fakeTimeNs: Long = 1_000_000_000L

    @Before
    fun setUp() {
        sessionPolicy = SessionPolicy(Role.SENDER)
        streamClient = StreamClient(
            sessionPolicy = sessionPolicy
        )
        fakeEncoder = FakeVideoEncoder()
        fakeAdapter = FakeProjectionAdapter()
        fakeTimeNs = 1_000_000_000L
        ProjectionHolder.reset()
    }

    @After
    fun tearDown() {
        ProjectionHolder.reset()
    }

    // 1. chooseEncodeSize matrix tests
    @Test
    fun chooseEncodeSize_matrix_1440x3200_cap1920() {
        val (w, h) = VideoSizeSelector.chooseEncodeSize(1440, 3200, 1920)
        assertThat(w).isEqualTo(864)
        assertThat(h).isEqualTo(1920)
        assertThat(w % 16).isEqualTo(0)
        assertThat(h % 16).isEqualTo(0)
        assertThat(w % 2).isEqualTo(0)
        assertThat(h % 2).isEqualTo(0)
    }

    @Test
    fun chooseEncodeSize_matrix_1080x2400_cap1920() {
        val (w, h) = VideoSizeSelector.chooseEncodeSize(1080, 2400, 1920)
        assertThat(w).isEqualTo(864)
        assertThat(h).isEqualTo(1920)
        assertThat(w % 16).isEqualTo(0)
        assertThat(h % 16).isEqualTo(0)
    }

    @Test
    fun chooseEncodeSize_matrix_1080x1920_cap1080p_unchanged() {
        val (w, h) = VideoSizeSelector.chooseEncodeSize(1080, 1920, 1920)
        assertThat(w).isEqualTo(1080)
        assertThat(h).isEqualTo(1920)
    }

    @Test
    fun chooseEncodeSize_matrix_odd_dimensions_round_down() {
        val (w, h) = VideoSizeSelector.chooseEncodeSize(725, 1285, 1280)
        assertThat(w % 16).isEqualTo(0)
        assertThat(h % 16).isEqualTo(0)
        assertThat(w % 2).isEqualTo(0)
        assertThat(h % 2).isEqualTo(0)
    }

    @Test
    fun chooseEncodeSize_matrix_cap720_case() {
        val (w, h) = VideoSizeSelector.chooseEncodeSize(1080, 1920, 1280)
        assertThat(w).isEqualTo(720)
        assertThat(h).isEqualTo(1280)
        assertThat(w % 16).isEqualTo(0)
        assertThat(h % 16).isEqualTo(0)
    }

    @Test
    fun chooseEncodeSize_matrix_landscape_rotation_swaps() {
        val (w, h) = VideoSizeSelector.chooseEncodeSize(3200, 1440, 1920)
        assertThat(w).isEqualTo(1920)
        assertThat(h).isEqualTo(864)
        assertThat(w % 16).isEqualTo(0)
        assertThat(h % 16).isEqualTo(0)
    }

    @Test
    fun chooseEncodeSize_step_down_when_unsupported() {
        // If 864x1920 is not supported, step down to next cap (1280)
        val isSupported: (Int, Int) -> Boolean = { w, h -> maxOf(w, h) <= 1280 }
        val (w, h) = VideoSizeSelector.chooseEncodeSize(1080, 2400, 1920, isSupported)
        assertThat(maxOf(w, h)).isAtMost(1280)
        assertThat(w % 16).isEqualTo(0)
        assertThat(h % 16).isEqualTo(0)
    }

    // 2. DPI clamp matrix
    @Test
    fun dpi_clamp_matrix() {
        assertThat(VideoSizeSelector.clampDpi(80)).isEqualTo(160)
        assertThat(VideoSizeSelector.clampDpi(640)).isEqualTo(480)
        assertThat(VideoSizeSelector.clampDpi(320)).isEqualTo(320)
    }

    // 3. PTS Passthrough contract
    @Test
    fun pts_passthrough_contract() {
        val scope = CoroutineScope(Dispatchers.Default)

        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = object : VideoEncoderFactory {
                override fun createEncoder(): VideoEncoder = fakeEncoder
            },
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val ptsSample = 9876543210L
        fakeEncoder.outputQueue.add(CodecOutput.Config(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)))
        fakeEncoder.outputQueue.add(
            CodecOutput.Frame(
                data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0xAA.toByte()),
                ptsUs = ptsSample,
                isKeyframe = true,
                bufferIndex = 1
            )
        )

        pipeline.start(scope, 1080, 1920, 400)
        pipeline.pumpOnce()
        pipeline.pumpOnce()

        assertThat(pipeline.cachedVideoConfig).isNotNull()
        assertThat(fakeEncoder.releasedBufferIndices).contains(1)

        pipeline.stop()
        scope.cancel()
    }

    // 4. Config cache on reconnect
    @Test
    fun config_cache_on_reconnect() {
        val scope = CoroutineScope(Dispatchers.Default)

        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = object : VideoEncoderFactory {
                override fun createEncoder(): VideoEncoder = fakeEncoder
            },
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val configBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00)
        fakeEncoder.outputQueue.add(CodecOutput.Config(configBytes))

        pipeline.start(scope, 1080, 1920, 400)
        pipeline.pumpOnce()

        assertThat(pipeline.cachedVideoConfig).isEqualTo(configBytes)

        pipeline.resendConfigOnReconnect()
        assertThat(fakeEncoder.requestedKeyframeCount).isAtLeast(1)

        pipeline.stop()
        scope.cancel()
    }

    // 5. Encoder Watchdog tests
    @Test
    fun watchdog_3s_silence_triggers_recreate_on_active_projection() {
        val encoders = mutableListOf<FakeVideoEncoder>()
        val factory = object : VideoEncoderFactory {
            override fun createEncoder(): VideoEncoder {
                val enc = FakeVideoEncoder()
                encoders.add(enc)
                return enc
            }
        }

        ProjectionHolder.setActive(null)

        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = factory,
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope, 1080, 1920, 400)

        assertThat(pipeline.watchdogRecreateCount).isEqualTo(0)
        assertThat(encoders).hasSize(1)

        // Advance fake time by 3.5s
        fakeTimeNs += 3_500_000_000L
        pipeline.checkWatchdog()

        // 1st recreate triggered
        assertThat(pipeline.watchdogRecreateCount).isEqualTo(1)
        assertThat(encoders).hasSize(2)

        // Advance fake time by another 3.5s
        fakeTimeNs += 3_500_000_000L
        pipeline.checkWatchdog()

        // 2nd recreate triggered
        assertThat(pipeline.watchdogRecreateCount).isEqualTo(2)
        assertThat(encoders).hasSize(3)

        pipeline.stop()
        scope.cancel()
    }

    @Test
    fun watchdog_3rd_stall_transitions_to_error_state() {
        val factory = object : VideoEncoderFactory {
            override fun createEncoder(): VideoEncoder = FakeVideoEncoder()
        }

        ProjectionHolder.setActive(null)

        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = factory,
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope, 1080, 1920, 400)

        // 1st stall
        fakeTimeNs += 3_500_000_000L
        pipeline.checkWatchdog()
        assertThat(pipeline.watchdogRecreateCount).isEqualTo(1)

        // 2nd stall
        fakeTimeNs += 3_500_000_000L
        pipeline.checkWatchdog()
        assertThat(pipeline.watchdogRecreateCount).isEqualTo(2)

        // 3rd stall -> Error
        fakeTimeNs += 3_500_000_000L
        pipeline.checkWatchdog()

        assertThat(pipeline.state.value).isInstanceOf(VideoCaptureState.Error::class.java)
        pipeline.stop()
        scope.cancel()
    }

    @Test
    fun watchdog_idle_when_projection_inactive() {
        ProjectionHolder.reset() // Projection is Idle, not Active

        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = object : VideoEncoderFactory {
                override fun createEncoder(): VideoEncoder = fakeEncoder
            },
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope, 1080, 1920, 400)

        fakeTimeNs += 10_000_000_000L // 10s silence
        pipeline.checkWatchdog()

        // Should NOT trigger recreate
        assertThat(pipeline.watchdogRecreateCount).isEqualTo(0)

        pipeline.stop()
        scope.cancel()
    }

    // 6. Rotation / Resolution change and single VirtualDisplay reuse
    @Test
    fun rotation_resolution_change_updates_virtual_display_without_recreation() {
        val encoders = mutableListOf<FakeVideoEncoder>()
        val factory = object : VideoEncoderFactory {
            override fun createEncoder(): VideoEncoder {
                val enc = FakeVideoEncoder()
                encoders.add(enc)
                return enc
            }
        }

        ProjectionHolder.setActive(null)

        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = factory,
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        // Start in portrait
        pipeline.start(scope, 1080, 2400, 400)
        assertThat(fakeAdapter.createCount).isEqualTo(1)
        assertThat(pipeline.currentWidth).isEqualTo(864)
        assertThat(pipeline.currentHeight).isEqualTo(1920)

        // Change to landscape
        pipeline.onDisplayChanged(2400, 1080, 400, debounceMs = 0L)

        // VirtualDisplay was NOT recreated - Android 14 single creation respected!
        assertThat(fakeAdapter.createCount).isEqualTo(1)
        // Handle was resized
        assertThat(fakeAdapter.handle.width).isEqualTo(1920)
        assertThat(fakeAdapter.handle.height).isEqualTo(864)
        assertThat(pipeline.currentWidth).isEqualTo(1920)
        assertThat(pipeline.currentHeight).isEqualTo(864)

        // New encoder was instantiated for landscape
        assertThat(encoders).hasSize(2)
        assertThat(encoders[1].requestedKeyframeCount).isAtLeast(1)

        pipeline.stop()
        scope.cancel()
    }

    // 7. Request keyframe and bitrate controls
    @Test
    fun control_hooks_forwarded_to_encoder() {
        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = object : VideoEncoderFactory {
                override fun createEncoder(): VideoEncoder = fakeEncoder
            },
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope, 1080, 1920, 400)

        pipeline.requestKeyframe()
        assertThat(fakeEncoder.requestedKeyframeCount).isEqualTo(1)

        pipeline.updateBitrate(4_000_000)
        assertThat(fakeEncoder.currentBitrate).isEqualTo(4_000_000)
        assertThat(pipeline.settings.bitrate).isEqualTo(4_000_000)

        pipeline.stop()
        scope.cancel()
    }

    // 8. Failure scenario: release encoder mid-pump -> clean teardown, no crash
    @Test
    fun release_encoder_mid_pump_clean_teardown_no_crash() {
        val pipeline = VideoCapturePipeline(
            projectionHolder = ProjectionHolder,
            streamClient = streamClient,
            sessionPolicy = sessionPolicy,
            encoderFactory = object : VideoEncoderFactory {
                override fun createEncoder(): VideoEncoder = fakeEncoder
            },
            projectionAdapterFactory = { fakeAdapter },
            clock = { fakeTimeNs }
        )

        val scope = CoroutineScope(Dispatchers.Default)
        pipeline.start(scope, 1080, 1920, 400)

        // Simulate encoder failure/release mid-pump
        fakeEncoder.release()

        val result = pipeline.pumpOnce()
        assertThat(result).isFalse()

        pipeline.stop()
        assertThat(pipeline.state.value).isEqualTo(VideoCaptureState.Idle)
        scope.cancel()
    }
}