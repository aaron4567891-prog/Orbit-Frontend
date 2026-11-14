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
