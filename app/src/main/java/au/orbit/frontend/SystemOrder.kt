package au.orbit.frontend

import android.content.Context
import org.json.JSONArray

/** The order of the Systems screen's systems and tiles, set by holding Y and moving them. */
internal object SystemOrder {
    private const val SECTION = "systemOrder"
    private const val KEY = "ids"

    fun saved(): List<String> = runCatching {
        val data = JSONArray(UserDataStore.cachedString(SECTION, KEY) ?: return emptyList())
        (0 until data.length()).map { data.getString(it) }
    }.getOrDefault(emptyList())

    fun save(context: Context, ids: List<String>) = UserDataStore.put(context, SECTION, KEY, JSONArray(ids).toString())

    /**
     * Puts [entries] in the saved order. Anything not in it yet (a new system or tile) goes straight after the
     * entry it follows in the default order, so it appears where it would have without a custom order.
     */
    fun <T : Any> apply(entries: List<T>, id: (T) -> String, saved: List<String> = saved()): List<T> {
        if (saved.isEmpty()) return entries
        val byId = entries.associateBy(id)
        val result = saved.mapNotNull(byId::get).toMutableList()
        val placed = result.map(id).toMutableSet()
        entries.forEachIndexed { index, entry ->
            if (id(entry) in placed) return@forEachIndexed
            val before = entries.subList(0, index).lastOrNull { id(it) in placed }
            val at = before?.let { b -> result.indexOfFirst { id(it) == id(b) } + 1 } ?: 0
            result.add(at, entry)
            placed += id(entry)
        }
        return result
    }
}
