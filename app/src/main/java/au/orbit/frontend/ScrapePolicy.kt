package au.orbit.frontend

import android.content.Context
import org.json.JSONObject

object ScrapePolicy {
    internal const val MAX_MANUAL_GAMES = 6
    fun requestedConcurrency(context:Context)=context.getSharedPreferences("scraper_credentials",0).getInt("scrape.gamesAtOnce",0)
    fun parallelism(allowed:Int,requested:Int=0)=if(requested<=0)allowed.coerceAtLeast(1) else minOf(allowed.coerceAtLeast(1),requested.coerceIn(1, MAX_MANUAL_GAMES))
    fun systemsForSelection(catalogue:List<SystemSpec>,selected:Set<String>):List<SystemSpec> {
        val chosen=catalogue.filter { it.id in selected }
        return if(chosen.isEmpty())catalogue else chosen
    }
    fun mode(context:Context)=context.getSharedPreferences("scraper_credentials",0).getString("scrape.mode","all") ?: "all"
    fun region(context:Context)=context.getSharedPreferences("scraper_credentials",0).getString("scrape.region","us") ?: "us"
    fun language(context:Context)=context.getSharedPreferences("scraper_credentials",0).getString("scrape.language","en") ?: "en"
    private const val AVAILABILITY_RECHECK_MS = 7 * 24 * 60 * 60 * 1000L

    internal fun unavailableMedia(record: JSONObject?, now: Long): Map<String, Long> {
        val checks = record?.optJSONObject("unavailableMedia") ?: return emptyMap()
        return checks.keys().asSequence().mapNotNull { folder ->
            val checkedAt = checks.optLong(folder)
            if (checkedAt > 0 && now - checkedAt in 0 until AVAILABILITY_RECHECK_MS) folder to checkedAt else null
        }.toMap()
    }

    internal fun updateAvailability(
        previous: JSONObject?, requested: Set<String>, offered: Set<String>, now: Long
    ): JSONObject {
        val unavailable = unavailableMedia(previous, now).toMutableMap()
        offered.forEach(unavailable::remove)
        (requested - offered).forEach { unavailable[it] = now }
        return JSONObject(unavailable)
    }
    internal data class MediaPlan(val folders: Set<String>, val existingTypes: Set<String>, val skip: Boolean)

    internal fun mediaPlan(context: Context, system: SystemSpec, game: Game, provider: String): MediaPlan =
        mediaPlanner(context, mode(context), provider)(system, game)

    internal fun mediaPlanner(context: Context, mode: String, provider: String): (SystemSpec, Game) -> MediaPlan {
        val enabled = context.getSharedPreferences("scraper_credentials", 0)
            .getStringSet("media.categories", ScraperMediaTypes.categories.map { it.folder }.toSet()).orEmpty()
        if (mode == "all" || (mode == "missing" && enabled.isEmpty())) {
            val plan = mediaPlan(mode, enabled, emptySet())
            return { _, _ -> plan }
        }
        val checkedAt = System.currentTimeMillis()
        return { system, game ->
            val group = GameCustomArtwork.group(system, game)
            val existingTypes = mutableSetOf<String>()
            val unavailable = mutableSetOf<String>()
            for (bytes in UserDataStore.readScrapedRecords(context, group)) {
                val record = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
                if (mode == "missing" && !ScrapedMediaPlatform.matches(record, system.id)) continue
                if (mode == "missing" && record.optString("provider") == provider &&
                    record.optString("romUri") == game.uri.toString()) {
                    unavailable.addAll(unavailableMedia(record, checkedAt).keys)
                }
                for (file in ScraperClient.objects(record.opt("files"))) {
                    val exists = runCatching { UserDataStore.scrapedUri(context, group, file.optString("file")) != null }
                    if (exists.getOrDefault(false)) existingTypes.add(file.optString("type"))
                }
            }
            val savedFolders = ScraperMediaTypes.categories.filter { it.types.any(existingTypes::contains) }
                .mapTo(mutableSetOf()) { it.folder }
            GameCustomArtwork.restoreScrapedMedia(context, group, savedFolders)
            mediaPlan(mode, enabled, existingTypes, unavailable)
        }
    }

    internal fun mediaPlan(
        mode: String, enabled: Set<String>, existingTypes: Set<String>, unavailable: Set<String> = emptySet()
    ): MediaPlan {
        val folders = ScraperMediaTypes.categories.filter {
            it.folder in enabled && (mode == "all" || it.types.none(existingTypes::contains)) &&
                (mode != "missing" || it.folder !in unavailable)
        }.mapTo(linkedSetOf()) { it.folder }
        val skip = when (mode) {
            "all" -> false
            "new" -> existingTypes.isNotEmpty()
            else -> folders.isEmpty()
        }
        return MediaPlan(folders, if (mode == "all") emptySet() else existingTypes, skip)
    }
}
