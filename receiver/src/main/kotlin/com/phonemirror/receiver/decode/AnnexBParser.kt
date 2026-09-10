package com.phonemirror.receiver.decode

data class NalUnit(
    val type: Int,
    val rawNal: ByteArray,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NalUnit) return false
        return type == other.type && rawNal.contentEquals(other.rawNal)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + rawNal.contentHashCode()
        return result
    }
}

data class SpsPpsConfig(
    val sps: ByteArray?,
    val pps: ByteArray?
)

object AnnexBParser {

    const val NAL_TYPE_IDR = 5
    const val NAL_TYPE_SPS = 7
    const val NAL_TYPE_PPS = 8

    /**
     * Splits an Annex-B stream into individual NAL units.
     * Handles both 4-byte (00 00 00 01) and 3-byte (00 00 01) start codes,
     * and correctly ignores 00 00 03 emulation-prevention sequences.
     */
    fun splitNals(data: ByteArray): List<NalUnit> {
        if (data.isEmpty()) return emptyList()

        val startCodes = mutableListOf<Pair<Int, Int>>() // Pair(startOffset, prefixLen)
        var i = 0
        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 3.toByte()) {
                    // Skip past emulation prevention byte (0x00, 0x00, 0x03)
                    i += 3
                    continue
                } else if (data[i + 2] == 1.toByte()) {
                    // Check if preceded by 0x00 for 4-byte start code
                    if (i > 0 && data[i - 1] == 0.toByte()) {
                        // Avoid duplicates if previous was already matched
                        if (startCodes.isEmpty() || startCodes.last().first != i - 1) {
                            startCodes.add(Pair(i - 1, 4))
                        }
                    } else {
                        startCodes.add(Pair(i, 3))
                    }
                    i += 3
                    continue
                }
            }
            i++
        }

        if (startCodes.isEmpty()) return emptyList()

        val result = mutableListOf<NalUnit>()
        for (k in 0 until startCodes.size) {
            val (startOffset, prefixLen) = startCodes[k]
            val payloadStart = startOffset + prefixLen
            val nalEnd = if (k + 1 < startCodes.size) startCodes[k + 1].first else data.size

            if (payloadStart < nalEnd) {
                val payload = data.copyOfRange(payloadStart, nalEnd)
                val nalType = payload[0].toInt() and 0x1F

                // Standardize rawNal with 4-byte start code
                val rawNal = if (prefixLen == 4) {
                    data.copyOfRange(startOffset, nalEnd)
                } else {
                    val normalized = ByteArray(1 + (nalEnd - startOffset))
                    normalized[0] = 0
                    System.arraycopy(data, startOffset, normalized, 1, nalEnd - startOffset)
                    normalized
                }

                result.add(NalUnit(type = nalType, rawNal = rawNal, payload = payload))
            }
        }

        return result
    }

    /**
     * Extracts SPS (csd-0) and PPS (csd-1) from config bytes.
     */
    fun parseSpsPps(data: ByteArray): SpsPpsConfig {
        val nals = splitNals(data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (nal in nals) {
            if (nal.type == NAL_TYPE_SPS && sps == null) {
                sps = nal.rawNal
            } else if (nal.type == NAL_TYPE_PPS && pps == null) {
                pps = nal.rawNal
            }
        }

        return SpsPpsConfig(sps = sps, pps = pps)
    }
}