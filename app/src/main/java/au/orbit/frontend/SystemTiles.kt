package au.orbit.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Placeholder tiles on the Systems screen (logo wheel and horizontal row), added from the system Y menu.
 * A tile sits after a particular system and is empty, or holds an app or a game shortcut.
 */
internal object SystemTiles {
    private const val SECTION = "systemTiles"
    private const val KEY = "tiles"
    private const val PREFIX = "orbit-tile-"
    const val EMPTY_LABEL = "＋"

    enum class Kind(val label: String) { EMPTY("Empty tile"), APP("App"), GAME("Game") }
    /** [after] is the id of the system the tile follows ("" for the start); [target] is a component or "system|uri". */
    data class Tile(val id: String, val after: String, val kind: Kind, val target: String?, val label: String)

    fun isTile(spec: SystemSpec?) = spec?.id?.startsWith(PREFIX) == true
    private fun idOf(spec: SystemSpec) = spec.id.removePrefix(PREFIX)

    fun list(): List<Tile> {
        val saved = UserDataStore.cachedString(SECTION, KEY) ?: return emptyList()
        return runCatching { decode(JSONArray(saved)) }.getOrDefault(emptyList())
    }

    internal fun decode(data: JSONArray): List<Tile> = (0 until data.length()).mapNotNull { index ->
        runCatching {
            val row = data.getJSONObject(index)
            Tile(row.getString("id"), row.optString("after"), Kind.valueOf(row.optString("kind", "EMPTY")),
                row.optString("target").takeIf { it.isNotBlank() }, row.optString("label"))
        }.getOrNull()
    }

    private fun save(context: Context, tiles: List<Tile>) {
        val data = JSONArray().apply {
            tiles.forEach { tile ->
                put(JSONObject().put("id", tile.id).put("after", tile.after).put("kind", tile.kind.name)
                    .put("target", tile.target ?: "").put("label", tile.label))
            }
        }
        UserDataStore.put(context, SECTION, KEY, if (tiles.isEmpty()) null else data.toString())
    }

    private fun asSystem(tile: Tile) = SystemSpec(PREFIX + tile.id,
        if (tile.kind == Kind.EMPTY) EMPTY_LABEL else tile.label, "", tile.kind.label, emptySet())

    /** The systems with tiles placed after the systems they follow; tiles whose system is gone go last. */
    fun merge(specs: List<SystemSpec>): List<SystemSpec> {
        val tiles = list()
        if (tiles.isEmpty()) return specs
        val byAnchor = tiles.groupBy { it.after }
        val placed = mutableSetOf<String>()
        val result = ArrayList<SystemSpec>(specs.size + tiles.size)
        byAnchor[""]?.forEach { result += asSystem(it); placed += it.id }
        for (spec in specs) {
            result += spec
            byAnchor[spec.id]?.forEach { result += asSystem(it); placed += it.id }
        }
        tiles.filter { it.id !in placed }.forEach { result += asSystem(it) }
        return result
    }

    fun tile(spec: SystemSpec): Tile? = if (isTile(spec)) list().firstOrNull { it.id == idOf(spec) } else null

    /** Adds an empty tile straight after [anchor] (a system, or another tile); returns its system entry id. */
    fun insertAfter(context: Context, anchor: SystemSpec): String {
        val tiles = list().toMutableList()
        val id = java.util.UUID.randomUUID().toString().take(12)
        val anchorTile = tile(anchor)
        if (anchorTile != null) tiles.add(tiles.indexOf(anchorTile) + 1, Tile(id, anchorTile.after, Kind.EMPTY, null, ""))
        else {
            val last = tiles.indexOfLast { it.after == anchor.id }
            val tile = Tile(id, anchor.id, Kind.EMPTY, null, "")
            if (last >= 0) tiles.add(last + 1, tile) else tiles.add(tile)
        }
        save(context, tiles)
        return PREFIX + id
    }

    private fun update(context: Context, spec: SystemSpec, change: (Tile) -> Tile?) {
        val id = idOf(spec)
        save(context, list().mapNotNull { if (it.id == id) change(it) else it })
    }

    fun assignApp(context: Context, spec: SystemSpec, component: String, label: String) =
        update(context, spec) { it.copy(kind = Kind.APP, target = component, label = label) }
    fun assignGame(context: Context, spec: SystemSpec, gameSystemId: String, game: Game) =
        update(context, spec) { it.copy(kind = Kind.GAME, target = "$gameSystemId|${game.uri}", label = game.title) }
    fun clear(context: Context, spec: SystemSpec) = update(context, spec) { it.copy(kind = Kind.EMPTY, target = null, label = "") }
    fun remove(context: Context, spec: SystemSpec) = update(context, spec) { null }
}
