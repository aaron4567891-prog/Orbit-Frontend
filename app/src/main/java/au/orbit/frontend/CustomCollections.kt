package au.orbit.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object CustomCollections {
    private const val SECTION = "library"
    private const val KEY = "customCollections"

    data class Entry(val systemId: String, val uri: String, val title: String) {
        constructor(system: SystemSpec, game: Game) : this(system.id, game.uri.toString(), game.title)

        fun matches(other: Entry): Boolean = systemId == other.systemId && uri == other.uri
    }

    data class Collection(val id: String, val name: String, val games: List<Entry>) {
        fun contains(entry: Entry): Boolean = games.any { it.matches(entry) }
    }

    fun list(context: Context): List<Collection> {
        val saved = UserDataStore.string(context, SECTION, KEY) ?: return emptyList()
        // A damaged or hand-edited file must not crash Settings.
        return runCatching { decode(JSONArray(saved)) }.getOrDefault(emptyList())
    }

    /** Skips any collection or game entry that is malformed, keeping the rest. */
    internal fun decode(data: JSONArray): List<Collection> = (0 until data.length()).mapNotNull { index ->
        runCatching {
            val row = data.getJSONObject(index)
            val games = row.optJSONArray("games") ?: JSONArray()
            Collection(row.getString("id"), row.getString("name"), (0 until games.length()).mapNotNull { position ->
                runCatching {
                    val game = games.getJSONObject(position)
                    Entry(game.getString("system"), game.getString("uri"), game.getString("title"))
                }.getOrNull()
            })
        }.getOrNull()
    }

    internal fun encode(collections: List<Collection>): JSONArray = JSONArray().apply {
        collections.forEach { collection ->
            put(JSONObject().put("id", collection.id).put("name", collection.name)
                .put("games", JSONArray().apply {
                    collection.games.forEach { game ->
                        put(JSONObject().put("system", game.systemId).put("uri", game.uri).put("title", game.title))
                    }
                }))
        }
    }

    fun create(context: Context, name: String, game: Entry? = null) = update(context) { collections ->
        collections + Collection(UUID.randomUUID().toString(), newName(name, collections), listOfNotNull(game))
    }

    fun rename(context: Context, id: String, name: String) = update(context) { collections ->
        val label = newName(name, collections.filterNot { it.id == id })
        collections.map { if (it.id == id) it.copy(name = label) else it }
    }

    fun delete(context: Context, id: String) = update(context) { collections ->
        collections.filterNot { it.id == id }
    }

    fun setMember(context: Context, id: String, game: Entry, included: Boolean) = update(context) { collections ->
        collections.map {
            if (it.id != id || it.contains(game) == included) it
            else it.copy(games = if (included) it.games + game else it.games.filterNot(game::matches))
        }
    }

    private fun newName(name: String, collections: List<Collection>): String {
        val label = name.trim()
        require(label.isNotEmpty()) { "Enter a collection name." }
        require(collections.none { it.name.equals(label, ignoreCase = true) }) { "A collection already uses that name." }
        return label
    }

    @Synchronized
    private fun update(context: Context, change: (List<Collection>) -> List<Collection>) {
        val previous = list(context)
        val next = change(previous)
        if (next != previous) UserDataStore.put(context, SECTION, KEY, encode(next))
    }
}
