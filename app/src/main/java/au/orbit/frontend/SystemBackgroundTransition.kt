package au.orbit.frontend

/** Background-only motion: list labels and console images stay in place. */
internal object SystemBackgroundTransition {
    const val STYLE_KEY = "systemBackgroundStyle"
    const val SPEED_KEY = "systemBackgroundSpeed"
    const val DIRECTION_KEY = "systemBackgroundDirection"
    const val WAIT_KEY = "systemBackgroundWaitForSelection"
    fun waitForSelection() = TransitionSettings.flag(WAIT_KEY, TransitionSettings.View.SYSTEMS)
    enum class Style(val id: Int, val label: String) {
        INSTANT(0, "Instant"), CROSSFADE(1, "Crossfade"), FADE(2, "Fade through black"),
        SLIDE(3, "Slide"), ZOOM(4, "Zoom");
        companion object { fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: CROSSFADE }
    }
    enum class Speed(val millis: Long, val label: String) {
        FAST(100L, "Fast"), NORMAL(180L, "Normal"), SLOW(300L, "Slow"), VERY_SLOW(500L, "Very slow")
    }
    enum class Direction(val x: Float, val y: Float, val label: String) {
        LEFT(-1f, 0f, "Left"), RIGHT(1f, 0f, "Right"), UP(0f, -1f, "Up"), DOWN(0f, 1f, "Down")
    }
    fun style() = Style.fromId(TransitionSettings.int(STYLE_KEY, Style.CROSSFADE.id, TransitionSettings.View.SYSTEMS))
    fun speed() = Speed.entries.getOrElse(TransitionSettings.int(SPEED_KEY, Speed.NORMAL.ordinal, TransitionSettings.View.SYSTEMS)) { Speed.NORMAL }
    fun direction() = Direction.entries.getOrElse(TransitionSettings.int(DIRECTION_KEY, 0, TransitionSettings.View.SYSTEMS)) { Direction.LEFT }
    fun duration(style: Style, speed: Speed): Long = when (style) {
        Style.INSTANT -> 0L
        else -> speed.millis
    }

    data class Frame(val oldAlpha: Float = 0f, val newAlpha: Float = 1f,
        val oldX: Float = 0f, val oldY: Float = 0f, val newX: Float = 0f, val newY: Float = 0f,
        val newScale: Float = 1f)
    fun frame(style: Style, progress: Float, direction: Direction): Frame {
        val t = progress.coerceIn(0f, 1f)
        return when (style) {
            Style.INSTANT -> Frame()
            Style.CROSSFADE -> Frame(oldAlpha = 1f - t)
            Style.FADE -> Frame(oldAlpha = (1f - 2f * t).coerceAtLeast(0f), newAlpha = (2f * t - 1f).coerceAtLeast(0f))
            Style.SLIDE -> Frame(oldAlpha = 1f, oldX = direction.x * t, oldY = direction.y * t,
                newX = -direction.x * (1f - t), newY = -direction.y * (1f - t))
            Style.ZOOM -> Frame(oldAlpha = 1f - t, newScale = 1f + 0.08f * (1f - t))
        }
    }
}
