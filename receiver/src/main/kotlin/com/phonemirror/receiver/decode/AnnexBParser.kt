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
    val pps: ByteArray?,
    val width: Int = 0,
    val height: Int = 0
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

        var spsWidth = 0
        var spsHeight = 0
        sps?.let {
            parseSpsDimensions(it)?.let { (w, h) ->
                spsWidth = w
                spsHeight = h
            }
        }

        return SpsPpsConfig(sps = sps, pps = pps, width = spsWidth, height = spsHeight)
    }

    /**
     * Parses width and height from an H.264 SPS NAL unit (accounting for macroblocks and crop).
     */
    fun parseSpsDimensions(spsBytes: ByteArray): Pair<Int, Int>? {
        try {
            var offset = 0
            if (spsBytes.size >= 4 && spsBytes[0] == 0.toByte() && spsBytes[1] == 0.toByte() && spsBytes[2] == 0.toByte() && spsBytes[3] == 1.toByte()) {
                offset = 4
            } else if (spsBytes.size >= 3 && spsBytes[0] == 0.toByte() && spsBytes[1] == 0.toByte() && spsBytes[2] == 1.toByte()) {
                offset = 3
            }

            val rbsp = java.io.ByteArrayOutputStream()
            var i = offset
            while (i < spsBytes.size) {
                if (i + 2 < spsBytes.size && spsBytes[i] == 0.toByte() && spsBytes[i + 1] == 0.toByte() && spsBytes[i + 2] == 3.toByte()) {
                    rbsp.write(0)
                    rbsp.write(0)
                    i += 3
                } else {
                    rbsp.write(spsBytes[i].toInt() and 0xFF)
                    i++
                }
            }
            val data = rbsp.toByteArray()
            if (data.isEmpty()) return null

            var bitPos = 8 // Skip NAL header byte

            fun readBit(): Int {
                val byteIdx = bitPos / 8
                val bitIdx = 7 - (bitPos % 8)
                bitPos++
                if (byteIdx < data.size) {
                    return ((data[byteIdx].toInt() and 0xFF) shr bitIdx) and 1
                }
                return 0
            }

            fun readBits(n: Int): Int {
                var v = 0
                for (b in 0 until n) {
                    v = (v shl 1) or readBit()
                }
                return v
            }

            fun readUe(): Int {
                var zeros = 0
                while (readBit() == 0 && bitPos / 8 < data.size) {
                    zeros++
                }
                if (zeros == 0) return 0
                val v = readBits(zeros)
                return ((1 shl zeros) - 1) + v
            }

            fun readSe(): Int {
                val ue = readUe()
                val sign = if (ue % 2 == 0) -1 else 1
                return sign * ((ue + 1) / 2)
            }

            val profileIdc = readBits(8)
            readBits(8) // constraint flags
            readBits(8) // level_idc
            readUe() // seq_parameter_set_id

            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                val chromaFormatIdc = readUe()
                if (chromaFormatIdc == 3) {
                    readBit()
                }
                readUe()
                readUe()
                readBit()
                val seqScalingMatrixPresent = readBit()
                if (seqScalingMatrixPresent == 1) {
                    val count = if (chromaFormatIdc == 3) 12 else 8
                    for (c in 0 until count) {
                        val seqScalingListPresent = readBit()
                        if (seqScalingListPresent == 1) {
                            val sizeOfScalingList = if (c < 6) 16 else 64
                            var lastScale = 8
                            var nextScale = 8
                            for (j in 0 until sizeOfScalingList) {
                                if (nextScale != 0) {
                                    val deltaScale = readSe()
                                    nextScale = (lastScale + deltaScale + 256) % 256
                                }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            readUe() // log2_max_frame_num_minus4
            val picOrderCntType = readUe()
            if (picOrderCntType == 0) {
                readUe()
            } else if (picOrderCntType == 1) {
                readBit()
                readSe()
                readSe()
                val numRef = readUe()
                for (c in 0 until numRef) {
                    readSe()
                }
            }

            readUe() // max_num_ref_frames
            readBit() // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = readUe()
            val picHeightInMapUnitsMinus1 = readUe()
            val frameMbsOnlyFlag = readBit()
            if (frameMbsOnlyFlag == 0) {
                readBit()
            }
            readBit() // direct_8x8_inference_flag

            val frameCroppingFlag = readBit()
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (frameCroppingFlag == 1) {
                cropLeft = readUe()
                cropRight = readUe()
                cropTop = readUe()
                cropBottom = readUe()
            }

            val rawWidth = (picWidthInMbsMinus1 + 1) * 16
            val rawHeight = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16
            val width = rawWidth - (cropLeft + cropRight) * 2
            val height = rawHeight - (cropTop + cropBottom) * 2

            if (width > 0 && height > 0) {
                return Pair(width, height)
            }
            return null
        } catch (_: Throwable) {
            return null
        }
    }
}