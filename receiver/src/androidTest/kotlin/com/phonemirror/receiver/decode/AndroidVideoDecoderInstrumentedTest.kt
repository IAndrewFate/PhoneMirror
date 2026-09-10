package com.phonemirror.receiver.decode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVideoDecoderInstrumentedTest {

    @Test
    fun decoder_configures_with_valid_sps_pps_csd() {
        val decoder = AndroidVideoDecoder()
        val sps = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x2A, 0x95.toByte(), 0xA0.toByte(),
            0x05, 0x00, 0x5B, 0x90.toByte()
        )
        val pps = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()
        )
        val format = VideoDecoderFormat(
            width = 1280,
            height = 720,
            sps = sps,
            pps = pps
        )

        try {
            // Null surface for headless testing on emulator/device
            decoder.configure(format, null)
            decoder.start()
            // Clean run
            decoder.flush()
        } finally {
            decoder.stop()
            decoder.release()
        }
    }
}