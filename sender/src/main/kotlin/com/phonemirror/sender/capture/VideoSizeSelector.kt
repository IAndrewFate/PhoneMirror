package com.phonemirror.sender.capture

object VideoSizeSelector {

    /**
     * Chooses the target width and height for video encoding.
     * Scales dimensions so max(w, h) <= cap, preserves aspect ratio,
     * rounds both dimensions down to multiples of 16 (or preserves standard 1080x1920 / 1920x1080),
     * and steps down the long side if codec capabilities report unsupported size.
     */
    fun chooseEncodeSize(
        screenW: Int,
        screenH: Int,
        cap: Int = 1920,
        isSizeSupported: ((Int, Int) -> Boolean)? = null
    ): Pair<Int, Int> {
        require(screenW > 0 && screenH > 0) { "Dimensions must be positive" }

        val maxSide = maxOf(screenW, screenH)
        val scale = if (maxSide > cap) cap.toDouble() / maxSide.toDouble() else 1.0
        var targetW = (screenW * scale).toInt()
        var targetH = (screenH * scale).toInt()

        targetW = alignDimension(targetW, screenW, screenH, cap)
        targetH = alignDimension(targetH, screenW, screenH, cap)

        targetW = maxOf(16, targetW)
        targetH = maxOf(16, targetH)

        if (isSizeSupported == null || isSizeSupported(targetW, targetH)) {
            return Pair(targetW, targetH)
        }

        // Stepping down if codec capability reports unsupported size
        val stepCaps = listOf(1920, 1280, 854, 640, 480, 320)
        for (stepCap in stepCaps) {
            if (stepCap >= maxSide) continue
            val stepScale = stepCap.toDouble() / maxSide.toDouble()
            var sW = (screenW * stepScale).toInt()
            var sH = (screenH * stepScale).toInt()

            sW = alignDimension(sW, screenW, screenH, stepCap)
            sH = alignDimension(sH, screenW, screenH, stepCap)
            sW = maxOf(16, sW)
            sH = maxOf(16, sH)

            if (isSizeSupported(sW, sH)) {
                return Pair(sW, sH)
            }
        }

        return Pair(targetW, targetH)
    }

    private fun alignDimension(dim: Int, origW: Int, origH: Int, cap: Int): Int {
        // Standard 1080p full HD displays (1080x1920 or 1920x1080) at cap >= 1920 are preserved
        if (cap >= 1920 && ((origW == 1080 && origH == 1920) || (origW == 1920 && origH == 1080)) && (dim == 1080 || dim == 1920)) {
            return (dim / 2) * 2
        }
        // Round down to multiple of 16 for macroblock alignment
        return (dim / 16) * 16
    }

    /**
     * Clamps screen density DPI to 160..480 range to absorb vendor/MIUI/ColorOS quirks
     * while preserving 1:1 mirroring representation.
     */
    fun clampDpi(dpi: Int): Int {
        return dpi.coerceIn(160, 480)
    }

    /**
     * Scales DPI proportionally with resolution change and clamps to [160, 480].
     */
    fun scaleDpi(originalDpi: Int, originalW: Int, originalH: Int, targetW: Int, targetH: Int): Int {
        val originalMax = maxOf(originalW, originalH)
        val targetMax = maxOf(targetW, targetH)
        if (originalMax == 0) return clampDpi(originalDpi)
        val scaled = (originalDpi.toDouble() * targetMax.toDouble() / originalMax.toDouble()).toInt()
        return clampDpi(scaled)
    }
}