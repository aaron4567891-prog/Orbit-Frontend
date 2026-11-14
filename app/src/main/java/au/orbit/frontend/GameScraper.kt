package au.orbit.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared media saving for the settings scraper and the game popup. */
object GameScraper {
    internal const val NO_AVAILABLE_MEDIA = "No requested media available"
    internal fun scrape(context: Context, provider: String, system: SystemSpec, game: Game, match: ScraperClient.Match, parallelGames:Boolean=false,onMediaPlan:((List<String>)->Unit)?=null,onMediaSaved:((String,String)->Unit)?=null,onMediaStarted:((String)->Unit)?=null,
        plan: ScrapePolicy.MediaPlan = ScrapePolicy.mediaPlanner(context, "all", provider)(system, game)): String =
        GameCustomArtwork.editMedia(GameCustomArtwork.group(system, game)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val client=ScraperClient(context,provider)
            val (metadata,available)=client.media(match, plan.folders)
            val safeMetadata = JSONObject(metadata.toString()).apply {
                remove("medias")
                if (ScrapedMediaPlatform.sourceId(provider, this) == null) {
                    val source = ScrapedMediaPlatform.sourceId(provider, match.raw)
                    if (source != null) put("systeme", JSONObject().put("id", source))
                }
            }
            val record = JSONObject().put("provider", provider).put("providerId", match.id)
                .put("title", match.title).put("romUri", game.uri.toString()).put("metadata", safeMetadata)
            check(ScrapedMediaPlatform.matches(record, system.id)) { "ScreenScraper returned media for another system." }
            if (ScrapedMediaPlatform.sourceId(provider, safeMetadata) != null) {
                record.put("screenScraperMatchVerified", true)
            }
            val selected=ScraperMediaTypes.select(available,plan.folders,ScrapePolicy.region(context),ScrapePolicy.language(context)).toMutableList()
            if(EsDeTheme.enabled(context) && EsDeTheme.variants[EsDeTheme.variantIndex(context)].first.endsWith("titlescreen")) {
                val titleScreenTypes = listOf("sstitle","titlescreen")
                available.firstOrNull { it.type in titleScreenTypes && titleScreenTypes.none(plan.existingTypes::contains) }?.let {
                    selected.add(ScraperMediaTypes.Category("titlescreens","Title screen",listOf("sstitle","titlescreen")) to it)
                }
            }
            val base="${system.id}/${ScraperClient.gameKey(game.uri.toString())}"
            val retained=linkedMapOf<String,JSONObject>()
            var previousMatch: JSONObject? = null
            UserDataStore.readScraped(context,base,"${provider.lowercase()}.json")?.let { bytes ->
                val previous = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                if (previous != null && ScrapedMediaPlatform.matches(previous, system.id) &&
                    (provider != "ScreenScraper" || previous.optString("providerId") == match.id)) {
                    previousMatch = previous
                    ScraperClient.objects(previous.opt("files")).forEach { retained[it.optString("type")] = it }
                }
            }
            val failures=mutableListOf<String>()
            val downloadedFolders = mutableSetOf<String>()
            // Keep network requests busy while the caller saves the preceding file.
            // Only the caller writes storage and metadata, so SAF folders cannot race.
            val downloadSlots = if (parallelGames) 1 else minOf(client.downloadConcurrency, selected.size).coerceAtLeast(1)
            val downloads=java.util.concurrent.Executors.newFixedThreadPool(downloadSlots)
            val completion=java.util.concurrent.ExecutorCompletionService<Pair<Int,Result<ByteArray>>>(downloads)
            onMediaPlan?.invoke(selected.map { it.first.folder })
            var submitted=0
            fun submitNext() {
                if(submitted < selected.size) {
                    val index=submitted++
                    completion.submit(java.util.concurrent.Callable {
                        index to runCatching { onMediaStarted?.invoke(selected[index].first.folder);client.bytes(selected[index].second.url) }
                    })
                }
            }
            try {
                repeat(downloadSlots) { submitNext() }
                repeat(selected.size) {
                    if(Thread.currentThread().isInterrupted) throw InterruptedException()
                    val (index,result)=completion.take().get()
                    submitNext()
                    val (category,media)=selected[index]
                    runCatching {
                        val bytes=result.getOrThrow()
                        val type=UserDataStore.mediaTypeFolder(context,system.id,category.folder)
                        val ext=media.extension.lowercase().replace(Regex("[^a-z0-9]"),"").ifBlank { "bin" }
                        val name=MediaLayout.fileName(game.fileName,ext)
                        UserDataStore.writeScraped(context,"${system.id}/$type",name,bytes)
                        val path="@/${system.id}/$type/$name"
                        retained[media.type]=JSONObject().put("type",media.type).put("file",path)
                        downloadedFolders.add(category.folder)
                        onMediaSaved?.invoke(category.folder,path)
                    }.onFailure { if(it is InterruptedException) throw it; failures.add("${media.type}: ${it.message}") }
                }
            } finally {
                downloads.shutdownNow()
            }
            // ScreenScraper media URLs include account credentials; never persist those URLs.
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val files=JSONArray(retained.values.toList())
            val now = System.currentTimeMillis()
            record.put("files", files).put("errors", JSONArray(failures)).put("scrapedAt", now)
            val media = metadata.opt("medias")
            if (provider == "ScreenScraper" && (media is JSONArray || media is JSONObject)) {
                val types = available.mapTo(mutableSetOf()) { it.type }
                val offered = ScraperMediaTypes.categories.filter { it.types.any(types::contains) }.mapTo(mutableSetOf()) { it.folder }
                record.put("unavailableMedia", ScrapePolicy.updateAvailability(previousMatch, plan.folders, offered, now))
            } else {
                previousMatch?.optJSONObject("unavailableMedia")?.let { record.put("unavailableMedia", it) }
            }
            UserDataStore.writeScraped(context,base,"${provider.lowercase()}.json",record.toString(2).toByteArray())
            GameCustomArtwork.restoreScrapedMedia(context, GameCustomArtwork.group(system, game), downloadedFolders)
            if (selected.isEmpty()) return@editMedia "$NO_AVAILABLE_MEDIA from $provider."
            "Saved ${files.length()} media files in your user data location.${if(failures.isEmpty()) "" else "\n${failures.size} downloads failed:\n${failures.take(4).joinToString("\n")}"}"
        }
}
