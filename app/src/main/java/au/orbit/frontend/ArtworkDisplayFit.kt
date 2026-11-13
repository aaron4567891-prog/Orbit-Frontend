package au.orbit.frontend

import android.app.Activity
import android.graphics.Point
import android.os.Build
import android.view.Display

internal data class ArtworkDimensions(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }

    val label: String get() = "$width × $height"
    private val aspect: Double get() = width.toDouble() / height

    fun fitFor(screens: List<ArtworkDimensions>): Double = screens.minOf { screen ->
        val shapeFit = minOf(aspect / screen.aspect, screen.aspect / aspect)
        val pixelFit = minOf(1.0, width.toDouble() / screen.width, height.toDouble() / screen.height)
        shapeFit * (0.7 + 0.3 * pixelFit)
    }

    companion object {
        fun from(width: Int, height: Int): ArtworkDimensions? =
            if (width > 0 && height > 0) ArtworkDimensions(width, height) else null
    }
}

/** Resolve the same physical screens that Orbit's artwork settings target. */
internal object ArtworkDisplayFit {
    @Suppress("DEPRECATION")
    fun targets(activity: Activity, screens: Set<String>): List<ArtworkDimensions> {
        val current = activity.windowManager.defaultDisplay
        val displays = DualScreenManager(activity).displays()
        val secondary = displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val companionId = DualScreenCoordinator.companionDisplay()
        val top = displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
        val bottom = secondary.firstOrNull { it.displayId == current.displayId }
            ?: secondary.firstOrNull { it.displayId == companionId }
            ?: secondary.firstOrNull { it.state != Display.STATE_OFF }
            ?: secondary.firstOrNull()

        val selected = listOfNotNull(
            top.takeIf { OrbitThemeArtwork.artworkScreen(activity,"top") in screens },
            bottom.takeIf { OrbitThemeArtwork.artworkScreen(activity,"bottom") in screens }
        )
        return selected.ifEmpty { listOf(current) }.mapNotNull { display ->
            if (display.displayId == current.displayId && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                ArtworkDimensions.from(bounds.width(), bounds.height())
            } else {
                val size = Point().also(display::getRealSize)
                ArtworkDimensions.from(size.x, size.y)
            }
        }.distinct()
    }
}
