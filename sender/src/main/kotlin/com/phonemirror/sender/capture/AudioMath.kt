package com.phonemirror.sender.capture

object AudioMath {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2 // 16-bit PCM
    const val FRAME_MS = 20

    // Number of frames per 20ms chunk: 48000 * 20 / 1000 = 960
    const val SAMPLES_PER_CHUNK = SAMPLE_RATE * FRAME_MS / 1000 // 960 frames
    // Bytes per chunk: 960 * 2 channels * 2 bytes = 3840 bytes
    const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * CHANNELS * BYTES_PER_SAMPLE // 3840 bytes

    const val CHUNK_DURATION_NS = FRAME_MS * 1_000_000L // 20_000_000 ns = 20 ms
    const val CHUNK_DURATION_US = FRAME_MS * 1_000L // 20_000 us

    /**
     * PTS BASE CONTRACT:
     * Audio PTS is calculated in the System.nanoTime monotonic domain (converted to microseconds),
     * matching the video surface input PTS domain.
     *
     * If AudioTimestamp is available from AudioRecord:
     *   ptsUs = (nanoTime - framePosition * 1_000_000_000L / sampleRate) / 1000
     * Fallback:
     *   (readEndNanoTime - CHUNK_DURATION_NS) / 1000
     */
    fun computePtsUs(
        readEndNanoTime: Long,
        chunkDurationNs: Long = CHUNK_DURATION_NS
    ): Long {
        return (readEndNanoTime - chunkDurationNs) / 1000L
    }
}