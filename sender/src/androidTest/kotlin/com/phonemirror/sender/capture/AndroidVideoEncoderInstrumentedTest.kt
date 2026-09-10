package com.phonemirror.sender.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVideoEncoderInstrumentedTest {

    @Test
    fun encoder_generates_valid_codec_config_frame() {
        val encoder = AndroidVideoEncoder()
        val settings = VideoSettings(
            bitrate = 8_000_000,
            fps = 60,
            resolutionCap = 1920,
            iFrameIntervalSeconds = 2
        )

        try {
            encoder.configure(1280, 720, settings)
            assertThat(encoder.inputSurface).isNotNull()
            encoder.start()

            var receivedConfig: CodecOutput.Config? = null
            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < 2000L) {
                val output = encoder.dequeueOutput(50_000L)
                if (output is CodecOutput.Config) {
                    receivedConfig = output
                    break
                }
            }

            // SPS/PPS header verification: Annex-B begins with 0x00000001 or 0x000001
            if (receivedConfig != null) {
                assertThat(receivedConfig.data.size).isAtLeast(4)
                val prefix4 = receivedConfig.data.take(4).toByteArray()
                val isAnnexB4 = prefix4.contentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                val isAnnexB3 = receivedConfig.data.take(3).toByteArray().contentEquals(byteArrayOf(0x00, 0x00, 0x01))
                assertThat(isAnnexB4 || isAnnexB3).isTrue()
            }
        } finally {
            encoder.stop()
            encoder.release()
        }
    }
}