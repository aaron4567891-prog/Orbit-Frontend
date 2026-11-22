package au.orbit.frontend

internal object SystemLogoAnimation {
    const val KEY = "systemLogoSelectionZoom"
    fun enabled() = TransitionSettings.int(KEY, 1, TransitionSettings.View.SYSTEMS) == 1
    // Image logos and text names share the same amount and scrolling curve.
    fun scale(selection: Float) = if (enabled()) 1f + 0.25f * selection.coerceIn(0f, 1f) else 1f
    fun scrollSelection(position: Float): Float {
        val amount = (1f - kotlin.math.abs(position)).coerceIn(0f, 1f)
        return amount * amount * (3f - 2f * amount)
    }
}
