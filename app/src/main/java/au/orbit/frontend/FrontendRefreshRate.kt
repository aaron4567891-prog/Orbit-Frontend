package au.orbit.frontend

import android.app.Activity
import kotlin.math.abs

/** Applies the user's saved frontend refresh rate to Orbit's window. */
object FrontendRefreshRate {
    private const val KEY = "frontendRefreshRate"
    fun selected(activity: Activity) = UserDataStore.int(activity, KEY, 120).let { if (it == 60) 60 else 120 }
    fun save(activity: Activity, rate: Int) = UserDataStore.put(activity, "settings", KEY, rate)
    fun supported(activity: Activity, rate: Int): Boolean {
        val display = activity.windowManager.defaultDisplay
        val current = display.mode
        return display.supportedModes.any {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && abs(it.refreshRate - rate) < 1f
        }
    }
    fun apply(activity: Activity) {
        val display = activity.windowManager.defaultDisplay
        val current = display.mode
        val requested = selected(activity)
        val matching = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val target = matching.firstOrNull { abs(it.refreshRate - requested) < 1f }
            ?: matching.firstOrNull { abs(it.refreshRate - 60f) < 1f } ?: current
        activity.window.attributes = activity.window.attributes.apply {
            preferredDisplayModeId = target.modeId
            preferredRefreshRate = target.refreshRate
        }
    }
}
