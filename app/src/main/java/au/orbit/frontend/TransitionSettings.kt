package au.orbit.frontend

import android.content.Context

/**
 * Page transition settings, either shared by all themes or remembered per theme.
 *
 * In per-theme mode each value is stored under "key.themeId" for the current theme. A theme that
 * has not set a value yet uses the shared one.
 */
internal object TransitionSettings {
    /** 0 = all themes share one set of settings, 1 = each theme remembers its own. */
    const val SCOPE_KEY = "pageTransitionScope"

    fun perTheme() = UserDataStore.cachedInt(SCOPE_KEY, 0) == 1
    private fun themed(key: String) = "$key.${OrbitThemes.current().id}"

    fun int(key: String, default: Int): Int {
        val shared = UserDataStore.cachedInt(key, default)
        return if (perTheme()) UserDataStore.cachedInt(themed(key), shared) else shared
    }

    fun flag(key: String) = int(key, 0) == 1

    fun put(context: Context, key: String, value: Int) =
        UserDataStore.put(context, "settings", if (perTheme()) themed(key) else key, value)
}
