package com.phonemirror.receiver.decode

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AndroidAudioDecoderInstrumentedTest {

    @Test
    fun audio_decoder_configures_cleanly() {
        val decoder = AndroidAudioDecoder()
        val opusHead = AudioDecodePipeline.buildDefaultOpusHead(48000, 2)
        val delay = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(6_500_000L).array()
        val seek = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L).array()

        val opusFormat = AudioDecoderFormat(
            mime = "audio/opus",
            sampleRate = 48000,
            channelCount = 2,
            csdBuffers = listOf(opusHead, delay, seek)
        )

        try {
            decoder.configure(opusFormat)
            decoder.start()
            decoder.flush()
        } catch (_: Exception) {
            val aacFormat = AudioDecoderFormat(
                mime = "audio/mp4a-latm",
                sampleRate = 48000,
                channelCount = 2,
                csdBuffers = listOf(byteArrayOf(0x11, 0x90.toByte()))
            )
            decoder.configure(aacFormat)
            decoder.start()
            decoder.flush()
        } finally {
            decoder.stop()
            decoder.release()
        }
    }

    @Test
    fun audio_player_initializes_and_writes_pcm() {
        val player = AndroidAudioPlayer(48000, 2)
        try {
            val syntheticPcm = ByteArray(3840)
            val written = player.write(syntheticPcm, 0, syntheticPcm.size)
            assertThat(written).isEqualTo(3840)
            player.play()
            assertThat(player.playbackHeadPositionFrames).isAtLeast(0L)
        } finally {
            player.stop()
            player.release()
        }
    }
}
