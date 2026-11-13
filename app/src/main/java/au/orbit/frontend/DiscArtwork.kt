package au.orbit.frontend

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Recognises circular cutouts in physical media and locates their centre in the image. */
internal object DiscArtwork {
    data class Center(val x: Float, val y: Float)

    fun center(width: Int, height: Int, alphaAt: (Int, Int) -> Int): Center? {
        val samples = 40
        val opaque = BooleanArray(samples * samples)
        var left = samples
        var top = samples
        var right = -1
        var bottom = -1
        for (y in 0 until samples) for (x in 0 until samples) {
            if (alphaAt((2 * x + 1) * width / (2 * samples), (2 * y + 1) * height / (2 * samples)) <= 64) continue
            opaque[y * samples + x] = true
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        val spanX = right - left + 1
        val spanY = bottom - top + 1
        if (spanX < 8 || spanY < 8) return null
        val aspect = spanX.toFloat() * width / (spanY * height)
        if (aspect !in 0.88f..1.14f) return null
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        fun opaqueAt(x: Int, y: Int) = x in 0 until samples && y in 0 until samples && opaque[y * samples + x]
        var filledRim = 0
        var clearOutside = 0
        for (index in 0 until 16) {
            val angle = index * PI / 8
            val dx = cos(angle) * spanX / 2
            val dy = sin(angle) * spanY / 2
            if (opaqueAt((cx + 0.80 * dx).roundToInt(), (cy + 0.80 * dy).roundToInt())) filledRim++
            if (!opaqueAt((cx + 1.20 * dx).roundToInt(), (cy + 1.20 * dy).roundToInt())) clearOutside++
        }
        if (filledRim < 14 || clearOutside < 15) return null
        return Center((cx + 0.5f) / samples, (cy + 0.5f) / samples)
    }
}
