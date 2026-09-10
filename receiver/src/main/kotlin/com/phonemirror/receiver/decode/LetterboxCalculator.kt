package com.phonemirror.receiver.decode

data class LetterboxRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

object LetterboxCalculator {

    /**
     * Calculates aspect-preserving dimensions centered inside the screen (letterbox/pillarbox).
     */
    fun letterboxRect(frameW: Int, frameH: Int, screenW: Int, screenH: Int): LetterboxRect {
        require(frameW > 0 && frameH > 0 && screenW > 0 && screenH > 0) {
            "Dimensions must be positive"
        }

        val frameAspect = frameW.toDouble() / frameH.toDouble()
        val screenAspect = screenW.toDouble() / screenH.toDouble()

        return if (frameAspect > screenAspect) {
            // Frame is wider than screen -> letterbox (black bars top & bottom)
            val targetW = screenW
            val targetH = (screenW / frameAspect).toInt()
            val y = (screenH - targetH) / 2
            LetterboxRect(x = 0, y = y, width = targetW, height = targetH)
        } else {
            // Frame is taller than screen -> pillarbox (black bars left & right)
            val targetH = screenH
            val targetW = (screenH * frameAspect).toInt()
            val x = (screenW - targetW) / 2
            LetterboxRect(x = x, y = 0, width = targetW, height = targetH)
        }
    }
}