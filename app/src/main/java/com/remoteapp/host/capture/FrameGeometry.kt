package com.remoteapp.host.capture

import kotlin.math.roundToInt

/** Source pixels describe the display; encoded pixels describe the JPEG, never the gesture space. */
data class FrameGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val revision: Long,
) {
    fun point(x: Float, y: Float): Pair<Int, Int> =
        (x * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1) to
            (y * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)

    companion object {
        fun encodedSize(width: Int, height: Int, maxEdge: Int = 1920): Pair<Int, Int> {
            require(width > 0 && height > 0 && maxEdge > 0)
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
            return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
        }
    }
}
