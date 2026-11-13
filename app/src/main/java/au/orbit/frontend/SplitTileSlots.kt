package au.orbit.frontend

import android.app.Activity
import android.content.ComponentName
import android.graphics.drawable.Drawable
import au.orbit.frontend.SplitTileLayout.Tile
import org.json.JSONObject

/** Owns the saved tile positions and resolves Android shortcuts for one grid. */
internal class SplitTileSlots(
    private val activity: Activity,
    private val page: String,
    private val libraryKeys: List<String>
) {
    companion object {
        private const val SECTION = "splitTileLayout"
        private const val LEGACY_SECTION = "splitAppShortcuts"
        const val ADD_LABEL = "Add Android app or game"
    }

    data class App(val label: String, val icon: Drawable?)

    private var layout = SplitTileLayout(libraryKeys, load())
    private val apps = mutableMapOf<ComponentName, App>()
    val slotCount get() = layout.slotCount
    fun libraryIndex(slot: Int) = layout.libraryIndex(slot)
    fun librarySlot(index: Int) = layout.librarySlot(index)
    fun isEmpty(slot: Int) = layout.tile(slot) == Tile.Empty

    private fun load(): Map<Int, Tile> {
        val saved = UserDataStore.cachedString(SECTION, page)
        val legacy = saved == null
        val source = saved ?: UserDataStore.cachedString(LEGACY_SECTION, page) ?: return emptyMap()
        val json = runCatching { JSONObject(source) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (key in json.keys()) {
                val slot = key.toIntOrNull()?.takeIf { it in 0 until Int.MAX_VALUE / 2 } ?: continue
                val value = json.optString(key)
                if (legacy) {
                    if (ComponentName.unflattenFromString(value) != null) {
                        put(libraryKeys.size + slot, Tile.App(value))
                    }
                    continue
                }
                when {
                    value.startsWith("library:") -> put(slot, Tile.Library(value.removePrefix("library:")))
                    value.startsWith("app:") -> put(slot, Tile.App(value.removePrefix("app:")))
                    value == "empty" -> put(slot, Tile.Empty)
                }
            }
        }
    }

    private fun component(slot: Int) = (layout.tile(slot) as? Tile.App)?.component?.let(ComponentName::unflattenFromString)

    fun app(slot: Int): App? = component(slot)?.let { component ->
        apps.getOrPut(component) {
            val pm = activity.packageManager
            runCatching {
                val info = pm.getActivityInfo(component, 0)
                App(info.loadLabel(pm).toString(), info.loadIcon(pm))
            }.getOrDefault(App("App unavailable", null))
        }
    }

    fun browse(slot: Int): CompanionBrowse {
        val component = component(slot)
        val app = app(slot)
        return CompanionBrowse(
            "shortcut/$page/$slot/${component?.flattenToString()}",
            app?.label ?: ADD_LABEL,
            icon = app?.icon,
            iconResource = if (component == null) android.R.drawable.ic_input_add else android.R.drawable.sym_def_app_icon
        )
    }

    fun openApp(slot: Int, screen: String? = null) {
        component(slot)?.let { AppDock.launchApp(activity, it, screen) }
    }

    fun choose(slot: Int, changed: () -> Unit) {
        BottomAppDrawer.show(activity, "Choose an Android app or game", component(slot) != null) { component ->
            val tile = component?.let { Tile.App(it.flattenToString()) } ?: Tile.Empty
            save(layout.assign(slot, tile))
            if (component != null) apps.remove(component)
            changed()
        }
    }

    fun remove(slot: Int) = save(layout.assign(slot, Tile.Empty))

    fun move(from: Int, to: Int) = save(layout.move(from, to))

    private fun save(next: Map<Int, Tile>) {
        val saved = JSONObject()
        next.forEach { (slot, tile) ->
            val value = when (tile) {
                is Tile.Library -> "library:${tile.key}"
                is Tile.App -> "app:${tile.component}"
                Tile.Empty -> "empty"
            }
            saved.put(slot.toString(), value)
        }
        UserDataStore.put(activity, SECTION, page, saved.toString())
        layout = SplitTileLayout(libraryKeys, next)
    }
}
