package au.orbit.frontend

import android.content.Context
import org.json.JSONObject

object ScrapePolicy {
    fun requestedConcurrency(context:Context)=context.getSharedPreferences("scraper_credentials",0).getInt("scrape.gamesAtOnce",0)
    fun parallelism(allowed:Int,requested:Int=0)=if(requested<=0)allowed.coerceAtLeast(1) else minOf(allowed.coerceAtLeast(1),requested.coerceIn(1,6))
    fun systemsForSelection(catalogue:List<SystemSpec>,selected:Set<String>):List<SystemSpec> {
        val chosen=catalogue.filter { it.id in selected }
        return if(chosen.isEmpty())catalogue else chosen
    }
    fun mode(context:Context)=context.getSharedPreferences("scraper_credentials",0).getString("scrape.mode","all") ?: "all"
    fun region(context:Context)=context.getSharedPreferences("scraper_credentials",0).getString("scrape.region","us") ?: "us"
    fun language(context:Context)=context.getSharedPreferences("scraper_credentials",0).getString("scrape.language","en") ?: "en"
    fun existing(context:Context,system:SystemSpec,game:Game):List<JSONObject> {
        val group="${system.id}/${ScraperClient.gameKey(game.uri.toString())}"
        return UserDataStore.scrapedRecordNames(context,group).flatMap { name ->
            runCatching {
                val bytes=UserDataStore.readScraped(context,group,name) ?: return@runCatching emptyList<JSONObject>()
                ScraperClient.objects(JSONObject(bytes.toString(Charsets.UTF_8)).opt("files")).filter {
                    UserDataStore.scrapedUri(context,group,it.optString("file"))!=null
                }
            }.getOrDefault(emptyList())
        }
    }
    fun shouldScrape(context:Context,system:SystemSpec,game:Game):Boolean {
        val mode=mode(context)
        if(mode=="all")return true
        val existing=existing(context,system,game)
        if(mode=="new")return existing.isEmpty()
        val enabled=context.getSharedPreferences("scraper_credentials",0).getStringSet("media.categories",ScraperMediaTypes.categories.map { it.folder }.toSet()).orEmpty()
        return ScraperMediaTypes.categories.any { category -> category.folder in enabled && existing.none { it.optString("type") in category.types } }
    }
}
