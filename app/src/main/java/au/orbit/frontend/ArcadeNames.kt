package au.orbit.frontend

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** Offline ROM-set identifiers; filenames and launch URIs are never altered. */
object ArcadeNames {
    private val arcadeSystems = setOf("arcade", "atomiswave", "consolearcade", "cps", "cps1", "cps2", "cps3", "daphne", "fba", "fbneo", "mame", "mame-advmame", "model2", "model3", "naomi", "naomi2", "naomigd", "neogeo", "pcarcade", "hikaru", "segastv")
    @Volatile private var names: Map<String, String>? = null
    @Synchronized private fun load(context: Context): Map<String, String> {
        names?.let { return it }
        val json = JSONObject(context.assets.open("arcade-names.json").bufferedReader().use { it.readText() })
        return json.keys().asSequence().associateWith { json.getString(it) }.also { names = it }
    }
    internal fun resolve(system: String, game: RomRecord, lookup: Map<String, String>): RomRecord {
        if (system !in arcadeSystems) return game
        val stem = game.fileName.substringBeforeLast('.', game.fileName)
        // Preserve a scraped/custom title instead of replacing it with a database title.
        if (game.title != stem) return game
        val title = lookup[stem.lowercase(Locale.ROOT)] ?: return game
        return game.copy(title = title)
    }
    fun apply(context: Context, system: SystemSpec, report: RomScanResult): RomScanResult {
        if (system.id !in arcadeSystems) return report
        val lookup = load(context)
        return report.copy(games = report.games.map { resolve(system.id, it, lookup) }
            .sortedWith(compareBy<RomRecord> { it.title.lowercase(Locale.ROOT) }.thenBy { it.uri }))
    }
}
