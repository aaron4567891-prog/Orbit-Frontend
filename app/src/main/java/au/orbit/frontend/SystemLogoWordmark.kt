package au.orbit.frontend

import android.graphics.Bitmap

/** Removes separated stacked badges, while preserving horizontal/side-emblem logo layouts. */
internal object SystemLogoWordmark {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val aspect get() = width.toFloat() / height
    }

    fun prepare(bitmap: Bitmap, horizontal: () -> Bitmap? = { null }): Bitmap {
        if (!bitmap.hasAlpha()) return if (bitmap.width.toFloat() / bitmap.height < 2.5f) horizontal() ?: bitmap else bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bounds = bounds(pixels, bitmap.width, bitmap.height) ?: return bitmap
        if (bounds.aspect < 2.5f) horizontal()?.let { return it }
        return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
    }

    internal fun bounds(pixels: IntArray, width: Int, height: Int): Bounds? {
        require(width > 0 && height > 0 && pixels.size.toLong() >= width.toLong() * height)
        fun ink(x: Int, y: Int) = pixels[y * width + x] ushr 24 > 12
        fun content(top: Int, bottom: Int): Bounds? {
            var left = width; var right = -1; var first = bottom; var last = -1
            for (y in top until bottom) for (x in 0 until width) if (ink(x, y)) {
                left = minOf(left, x); right = maxOf(right, x)
                first = minOf(first, y); last = maxOf(last, y)
            }
            return if (right < left) null else Bounds(left, first, right + 1, last + 1)
        }
        val full = content(0, height) ?: return null
        // A wide name with an emblem beside it already has the desired layout.
        if (full.aspect >= 2.5f) return full
        fun name(candidate: Bounds) = candidate.aspect >= 2.5f && candidate.width >= full.width * 0.65f
        fun badge(candidate: Bounds, name: Bounds) = candidate.aspect <= 2.2f && candidate.height >= name.height * 0.6f
        var best: Bounds? = null
        var y = full.top
        while (y < full.bottom) {
            if ((full.left until full.right).any { ink(it, y) }) { y++; continue }
            val gapStart = y
            while (y < full.bottom && (full.left until full.right).none { ink(it, y) }) y++
            if (y - gapStart < maxOf(1, full.height / 100)) continue
            val above = content(full.top, gapStart) ?: continue
            val below = content(y, full.bottom) ?: continue
            val candidate = when {
                name(below) && badge(above, below) -> below
                name(above) && badge(below, above) -> above
                else -> null
            }
            if (candidate != null && candidate.width * candidate.height > (best?.let { it.width * it.height } ?: 0)) best = candidate
        }
        return best ?: full
    }
}
