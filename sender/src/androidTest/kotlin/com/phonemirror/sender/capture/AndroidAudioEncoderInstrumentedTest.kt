package com.phonemirror.sender.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AndroidAudioEncoderInstrumentedTest {

    @Test
    fun audio_encoder_processes_synthetic_sine_pcm() {
        val encoder = AndroidAudioEncoder()
        encoder.configure(AudioMath.SAMPLE_RATE, AudioMath.CHANNELS, 128_000)
        encoder.start()

        try {
            // Generate 1s of 440 Hz sine wave PCM (50 chunks of 20ms)
            var packetCount = 0
            val sampleRate = AudioMath.SAMPLE_RATE.toDouble()
            val freq = 440.0
            val ptsList = mutableListOf<Long>()

            for (chunkIdx in 0 until 50) {
                val pcmChunk = ByteArray(AudioMath.BYTES_PER_CHUNK)
                for (frameIdx in 0 until AudioMath.SAMPLES_PER_CHUNK) {
                    val totalSampleIdx = chunkIdx * AudioMath.SAMPLES_PER_CHUNK + frameIdx
                    val angle = 2.0 * Math.PI * freq * totalSampleIdx / sampleRate
                    val sampleVal = (sin(angle) * Short.MAX_VALUE).toInt().toShort()

                    val byteOffset = frameIdx * 4
                    // Left channel (little endian)
                    pcmChunk[byteOffset] = (sampleVal.toInt() and 0xFF).toByte()
                    pcmChunk[byteOffset + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
                    // Right channel
                    pcmChunk[byteOffset + 2] = (sampleVal.toInt() and 0xFF).toByte()
                    pcmChunk[byteOffset + 3] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
                }

                val ptsUs = chunkIdx * 20_000L
                encoder.queueInput(pcmChunk, 0, pcmChunk.size, ptsUs)

                var output = encoder.dequeueOutput(50_000L)
                while (output != null && output !is AudioCodecOutput.TryAgainLater) {
                    if (output is AudioCodecOutput.Frame) {
                        packetCount++
                        ptsList.add(output.ptsUs)
                    }
                    output = encoder.dequeueOutput(10_000L)
                }
            }

            // Verify codec name is either opus or mp4a-latm (fallback)
            assertThat(encoder.codecName in listOf("opus", "mp4a-latm")).isTrue()
        } finally {
            encoder.stop()
            encoder.release()
        }
    }
}