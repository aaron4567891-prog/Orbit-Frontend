package au.orbit.frontend

import android.content.Context

/**
 * Each library view has its own animation settings, shared by all themes or remembered per theme.
 * Older installations use the previous common settings until a value is set for the view.
 */
internal object TransitionSettings {
    /** 0 = all themes share one set of settings, 1 = each theme remembers its own. */
    const val SCOPE_KEY = "pageTransitionScope"

    enum class View(val id: String) { SYSTEMS("systems"), GAMES("games") }

    enum class ArtworkAnimation(val id: Int, val label: String) {
        DEFAULT(0, "Default"),
        INSTANT(1, "Instant"),
        FADE(2, "Fade"),
        SLIDE(3, "Slide up"),
        ZOOM(4, "Zoom in");

        companion object {
            const val KEY = "artworkAnimation"
            fun selected(): ArtworkAnimation {
                val id = int(KEY, DEFAULT.id, View.GAMES)
                return entries.firstOrNull { it.id == id } ?: DEFAULT
            }
        }
    }

    enum class LaunchAnimation(val id: Int, val label: String, val enter: Int, val exit: Int) {
        DEFAULT(0, "Android default", 0, 0),
        INSTANT(1, "Instant", R.anim.screen_swap_hold, R.anim.screen_swap_hold),
        FADE(2, "Fade", R.anim.game_launch_fade, R.anim.game_launch_hold),
        SLIDE(3, "Slide up", R.anim.game_launch_slide, R.anim.game_launch_hold),
        ZOOM(4, "Zoom in", R.anim.game_launch_zoom, R.anim.game_launch_hold);

        companion object {
            const val KEY = "launchAnimation"
            fun selected(): LaunchAnimation {
                val id = int(KEY, DEFAULT.id, View.GAMES)
                return entries.firstOrNull { it.id == id } ?: DEFAULT
            }
        }
    }

    private fun scoped(key: String, view: View) = "animation.${view.id}.$key"
    private fun themed(key: String) = "$key.${OrbitThemes.current().id}"

    fun perTheme(view: View): Boolean {
        val legacyScope = UserDataStore.cachedInt(SCOPE_KEY, 0)
        return UserDataStore.cachedInt(scoped(SCOPE_KEY, view), legacyScope) == 1
    }

    fun setPerTheme(context: Context, view: View, enabled: Boolean) =
        UserDataStore.put(context, "settings", scoped(SCOPE_KEY, view), if (enabled) 1 else 0)

    fun int(key: String, default: Int, view: View): Int {
        val shared = UserDataStore.cachedInt(key, default)
        val viewKey = scoped(key, view)
        val viewValue = UserDataStore.cachedInt(viewKey, shared)
        if (!perTheme(view)) return viewValue
        val legacyThemeValue = UserDataStore.cachedInt(themed(key), shared)
        return UserDataStore.cachedInt(themed(viewKey), UserDataStore.cachedInt(viewKey, legacyThemeValue))
    }

    fun flag(key: String, view: View) = int(key, 0, view) == 1

    fun put(context: Context, key: String, value: Int, view: View) {
        val viewKey = scoped(key, view)
        UserDataStore.put(context, "settings", if (perTheme(view)) themed(viewKey) else viewKey, value)
    }
}
