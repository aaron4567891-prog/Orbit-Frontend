package au.orbit.frontend

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared media saving for the settings scraper and the game popup. */
object GameScraper {
    fun scrape(context: Context, provider: String, system: SystemSpec, game: Game, match: ScraperClient.Match, parallelGames:Boolean=false,onProgress:((Int,Int)->Unit)?=null,onMediaPlan:((List<String>)->Unit)?=null,onMediaSaved:((String,String)->Unit)?=null,onMediaStarted:((String)->Unit)?=null): String {
        val client=ScraperClient(context,provider)
            val (metadata,available)=client.media(match)
            val enabled=context.getSharedPreferences("scraper_credentials",0).getStringSet("media.categories",ScraperMediaTypes.categories.map { it.folder }.toSet()).orEmpty()
            val selected=ScraperMediaTypes.select(available,enabled,ScrapePolicy.region(context),ScrapePolicy.language(context)).toMutableList()
            if(EsDeTheme.enabled(context) && EsDeTheme.variants[EsDeTheme.variantIndex(context)].first.endsWith("titlescreen")) {
                available.firstOrNull { it.type in listOf("sstitle","titlescreen") }?.let {
                    selected.add(ScraperMediaTypes.Category("titlescreens","Title screen",listOf("sstitle","titlescreen")) to it)
                }
            }
            val base="${system.id}/${ScraperClient.gameKey(game.uri.toString())}"
            if(ScrapePolicy.mode(context)!="all") {
                val existing=ScrapePolicy.existing(context,system,game)
                selected.removeAll { (category,_) -> existing.any { it.optString("type") in category.types } }
            }
            val retained=linkedMapOf<String,JSONObject>()
            UserDataStore.readScraped(context,base,"${provider.lowercase()}.json")?.let { bytes ->
                runCatching { ScraperClient.objects(JSONObject(bytes.toString(Charsets.UTF_8)).opt("files")) }.getOrDefault(emptyList()).forEach { retained[it.optString("type")]=it }
            }
            val failures=mutableListOf<String>()
            // Keep network requests busy while the caller saves the preceding file.
            // Only the caller writes storage and metadata, so SAF folders cannot race.
            val downloads=java.util.concurrent.Executors.newFixedThreadPool(if(parallelGames)1 else minOf(client.downloadConcurrency,selected.size.coerceAtLeast(1)))
            val completion=java.util.concurrent.ExecutorCompletionService<Pair<Int,Result<ByteArray>>>(downloads)
            onMediaPlan?.invoke(selected.map { it.first.folder })
            onProgress?.invoke(0,selected.size)
            var mediaCompleted=0
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
                repeat(minOf(client.downloadConcurrency,selected.size)) { submitNext() }
                repeat(selected.size) {
                    if(Thread.currentThread().isInterrupted) throw InterruptedException()
                    val (index,result)=completion.take().get()
                    submitNext()
                    val (category,media)=selected[index]
                    val type=UserDataStore.mediaTypeFolder(context,system.id,category.folder)
                    val ext=media.extension.lowercase().replace(Regex("[^a-z0-9]"),"").ifBlank { "bin" }
                    val name=MediaLayout.fileName(game.fileName,ext)
                    runCatching {
                        val bytes=result.getOrThrow()
                        UserDataStore.writeScraped(context,"${system.id}/$type",name,bytes)
                        val path="@/${system.id}/$type/$name"
                        retained[media.type]=JSONObject().put("type",media.type).put("file",path)
                        onMediaSaved?.invoke(category.folder,path)
                    }.onFailure { if(it is InterruptedException) throw it; failures.add("${media.type}: ${it.message}") }
                    mediaCompleted++;onProgress?.invoke(mediaCompleted,selected.size)
                }
            } finally {
                downloads.shutdownNow()
            }
            // ScreenScraper media URLs include account credentials; never persist those URLs.
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val safeMetadata=JSONObject(metadata.toString()).apply { remove("medias") }
            val files=JSONArray(retained.values.toList())
            val record=JSONObject().put("provider",provider).put("providerId",match.id).put("title",match.title).put("romUri",game.uri.toString())
                .put("metadata",safeMetadata).put("files",files).put("errors",JSONArray(failures)).put("scrapedAt",System.currentTimeMillis())
            UserDataStore.writeScraped(context,base,"${provider.lowercase()}.json",record.toString(2).toByteArray())
            return "Saved ${files.length()} media files in your user data location.${if(failures.isEmpty()) "" else "\n${failures.size} downloads failed:\n${failures.take(4).joinToString("\n")}"}"
    }
}
