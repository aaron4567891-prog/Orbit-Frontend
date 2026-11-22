package au.orbit.frontend

/** Thor can keep a retained music track on a separate display volume route. */
internal object SystemsMusicVolume {
    private const val TRACK_GAIN = 0.614f

    fun gain(level: Int, maximum: Int, softwareVolume: Boolean, muted: Boolean): Float {
        if (muted || level <= 0) return 0f
        if (!softwareVolume) return TRACK_GAIN
        val limit = maximum.coerceAtLeast(1)
        val fraction = level.coerceIn(0, limit).toFloat() / limit
        // Android provides the increasing media-volume curve. Boost its quiet
        // steps towards unity gain, tapering back to the existing maximum gain.
        return TRACK_GAIN + (1f - TRACK_GAIN) * (1f - fraction)
    }
}
