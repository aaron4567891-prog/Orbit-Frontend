package au.orbit.frontend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF

/**
 * Keeps system logos readable over hero artwork: each logo sits on a solid grey rounded panel with a
 * solid light border. Dark single-colour logos (black or grey wordmarks) are drawn in white so
 * they show on the panel; coloured and multi-coloured logos keep their own colours.
 */
internal object LogoHalo {
    private val darkMonochrome = java.util.WeakHashMap<Bitmap, Boolean>()
    private val whiteFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val panel = RectF()

    /** Relative luminance (0–1) of a logo's visible pixels, weighted by their opacity. */
    internal fun luminance(pixels: IntArray): Float {
        var total = 0.0; var weight = 0.0
        for (pixel in pixels) {
            val alpha = Color.alpha(pixel) / 255.0
            if (alpha < 0.1) continue
            val value = (0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)) / 255.0
            total += value * alpha; weight += alpha
        }
        return if (weight == 0.0) 1f else (total / weight).toFloat()
    }

    /** Average saturation (0–1) of a logo's visible pixels; low for grey, black and white logos. */
    internal fun saturation(pixels: IntArray): Float {
        var total = 0f; var visible = 0
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) continue
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            total += (maxOf(r, g, b) - minOf(r, g, b)) / 255f; visible++
        }
        return if (visible == 0) 0f else total / visible
    }

    /** Standard deviation of luminance across a logo's visible pixels: high for black-and-white logos. */
    internal fun luminanceSpread(pixels: IntArray): Float {
        val values = pixels.filter { Color.alpha(it) >= 128 }
            .map { (0.2126f * Color.red(it) + 0.7152f * Color.green(it) + 0.0722f * Color.blue(it)) / 255f }
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return kotlin.math.sqrt(values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size)
    }

    /**
     * Single-tone black or grey logos (such as PSP), which disappear on the grey panel; recolouring them
     * white keeps their shape. Two-tone black-and-white logos (Sega CD, Pokémon mini) keep their colours.
     */
    internal fun isDarkMonochrome(saturation: Float, luminance: Float, spread: Float = 0f) =
        saturation < 0.15f && luminance < 0.8f && spread < 0.18f

    private fun darkMonochrome(logo: Bitmap): Boolean = synchronized(darkMonochrome) {
        darkMonochrome.getOrPut(logo) {
            runCatching {
                // Large enough that thin outline logos (such as PSP) keep solid strokes.
                val sample = Bitmap.createScaledBitmap(logo, 160, (160f * logo.height / logo.width).toInt().coerceIn(1, 320), true)
                val pixels = IntArray(sample.width * sample.height).also { sample.getPixels(it, 0, sample.width, 0, 0, sample.width, sample.height) }
                if (sample !== logo) sample.recycle()
                isDarkMonochrome(saturation(pixels), luminance(pixels), luminanceSpread(pixels))
            }.getOrDefault(false)
        }
    }

    /** Solid grey panel with a solid light border, used behind logos and around the systems wheel. */
    internal const val WINDOW_FILL = 0xFF5F646B.toInt()
    internal const val WINDOW_BORDER = 0xFFC9CCD1.toInt()
    fun drawWindow(canvas: Canvas, bounds: RectF, radius: Float, alpha: Int = 255) {
        fill.color = WINDOW_FILL
        fill.alpha = alpha
        canvas.drawRoundRect(bounds, radius, radius, fill)
        border.color = WINDOW_BORDER
        border.alpha = alpha
        border.strokeWidth = (bounds.height() * 0.035f).coerceIn(1.5f, 3f)
        canvas.drawRoundRect(bounds, radius, radius, border)
    }

    /** A frame in the theme accent colour marking the selected position. */
    fun drawSelection(canvas: Canvas, bounds: RectF, radius: Float, accent: Int, tinted: Boolean = false) {
        // A chosen selection colour also tints the frame's background; the default keeps a faint light fill.
        fill.color = if (tinted) accent else Color.WHITE
        fill.alpha = if (tinted) 70 else 22
        canvas.drawRoundRect(bounds, radius, radius, fill)
        border.color = accent
        border.alpha = 220
        border.strokeWidth = (bounds.height() * 0.03f).coerceIn(2f, 4f)
        canvas.drawRoundRect(bounds, radius, radius, border)
    }

    /** Draws [logo] into [bounds], on its own bordered panel when [panel] is set, for display over hero artwork. */
    /** A colour chosen for one system in the Y menu, or null when it follows the Systems panel setting. */
    fun systemFilter(systemId: String?): android.graphics.ColorFilter? {
        val colour = systemId?.let(SystemsPanel::systemLogoColour) ?: return null
        return when (colour) {
            SystemsPanel.SystemLogoColour.DEFAULT, SystemsPanel.SystemLogoColour.ORIGINAL -> null
            SystemsPanel.SystemLogoColour.ACCENT -> PorterDuffColorFilter(OrbitThemes.current().accent, PorterDuff.Mode.SRC_IN)
            else -> PorterDuffColorFilter(colour.rgb!!, PorterDuff.Mode.SRC_IN)
        }
    }

    fun drawLogo(canvas: Canvas, logo: Bitmap, bounds: RectF, alpha: Int, paint: Paint, panel: Boolean = true, systemId: String? = null) {
        if (panel) {
            val padY = bounds.height() * 0.28f
            val padX = padY * 1.4f
            this.panel.set(bounds.left - padX, bounds.top - padY, bounds.right + padX, bounds.bottom + padY)
            drawWindow(canvas, this.panel, this.panel.height() * 0.28f, alpha)
        }
        val previous = paint.colorFilter
        val perSystem = systemId?.let(SystemsPanel::systemLogoColour) ?: SystemsPanel.SystemLogoColour.DEFAULT
        paint.colorFilter = if (perSystem == SystemsPanel.SystemLogoColour.ORIGINAL) previous
        else systemFilter(systemId) ?: when (val colour = SystemsPanel.logoColour()) {
            SystemsPanel.LogoColour.AUTO -> if (darkMonochrome(logo)) whiteFilter else previous
            SystemsPanel.LogoColour.ORIGINAL -> previous
            SystemsPanel.LogoColour.ACCENT -> PorterDuffColorFilter(OrbitThemes.current().accent, PorterDuff.Mode.SRC_IN)
            else -> PorterDuffColorFilter(colour.rgb!!, PorterDuff.Mode.SRC_IN)
        }
        paint.alpha = alpha
        canvas.drawBitmap(logo, null, bounds, paint)
        paint.colorFilter = previous
    }
}
