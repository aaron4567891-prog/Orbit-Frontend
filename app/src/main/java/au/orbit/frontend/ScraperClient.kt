package au.orbit.frontend

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Provider responses are data only. Credentials are never copied into saved metadata. */
class ScraperClient(private val context: Context, val provider: String) {
    data class Match(val id: String, val title: String, val platform: String, val raw: JSONObject)
    data class Media(val type: String, val url: String, val extension: String, val region: String = "", val language: String = "")
    var downloadConcurrency = if (provider == "ScreenScraper") 1 else 3
        private set
    private fun credential(key: String) = ScraperCredentials.value(context, provider, key)
    private fun required(key: String) = credential(key).also { require(it.isNotBlank()) { "Configure $provider credentials first." } }
    private fun query(base: String, values: Map<String, String>) = Uri.parse(base).buildUpon().apply { values.forEach { (k,v) -> appendQueryParameter(k,v) } }.build().toString()
    private fun ss(endpoint: String, values: Map<String, String> = emptyMap()): JSONObject = json(query("https://api.screenscraper.fr/api2/$endpoint.php", mapOf(
        "devid" to required("developerId"), "devpassword" to required("developerPassword"), "softname" to "Orbit", "output" to "json",
        "ssid" to credential("username"), "sspassword" to credential("password")
    ) + values)).getJSONObject("response").also { response ->
        response.optJSONObject("ssuser")?.optString("maxthreads")?.toIntOrNull()?.takeIf { it>0 }?.let {
            downloadConcurrency = it.coerceAtLeast(1)
            ScreenScraperRequests.configure(downloadConcurrency)
            accountCache[accountKey()]=System.nanoTime() to downloadConcurrency
        }
    }
    private fun accountKey()=gameKey(listOf(credential("developerId"),credential("username"),credential("password")).joinToString("\u0000"))
    fun accountConcurrency():Int {
        if(provider=="ScreenScraper") {
            accountCache[accountKey()]?.takeIf { System.nanoTime()-it.first < java.util.concurrent.TimeUnit.MINUTES.toNanos(1) }?.let {
                downloadConcurrency=it.second;ScreenScraperRequests.configure(it.second);return it.second
            }
            ss("ssuserInfos")
        }
        return downloadConcurrency
    }
    private fun json(url: String) = try { JSONObject(bytes(url).toString(Charsets.UTF_8)) }
        catch (e: org.json.JSONException) { throw IllegalStateException("$provider returned an invalid response. Check your account and service availability.") }
    fun bytes(url: String): ByteArray {
        if(provider=="ScreenScraper")ScreenScraperRequests.acquire()
        try { return downloadBytes(url) }
        finally { if(provider=="ScreenScraper")ScreenScraperRequests.release() }
    }
    private fun downloadBytes(url:String,maxMegabytes:Int=128):ByteArray {
        val parsed = Uri.parse(url)
        require(parsed.scheme == "https") { "Only secure media URLs are supported." }
        val request=okhttp3.Request.Builder().url(url).header("User-Agent","Orbit/0.1")
        if(parsed.host=="www.steamgriddb.com")request.header("Authorization","Bearer ${required("apiKey")}")
        if(Thread.currentThread().isInterrupted)throw InterruptedException()
        ScraperHttp.client.newCall(request.build()).execute().use { response ->
            val code=response.code
            check(code in 200..299) { when(code) {
                401,403 -> "$provider rejected the credentials or access permission."
                429 -> "$provider rate limit reached. Please try again later."
                else -> "$provider request failed (HTTP $code)."
            } }
            val body=response.body
            val limit=maxMegabytes*1024*1024
            check(body.contentLength()<=limit) { "Media exceeds the $maxMegabytes MB download limit." }
            return body.byteStream().use { input ->
                val capacity=body.contentLength().takeIf { it in 1..(2*1024*1024) }?.toInt() ?: 32768
                val output=java.io.ByteArrayOutputStream(capacity)
                val buffer=ByteArray(65536)
                while(true) {
                    if(Thread.currentThread().isInterrupted)throw InterruptedException()
                    val count=input.read(buffer);if(count<0)break
                    check(output.size()+count<=limit) { "Media exceeds the $maxMegabytes MB download limit." }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
        }
    }

    fun search(title: String): List<Match> = when (provider) {
        "NeoAssets" -> objects(json(query("https://api.neoassets.dev/api/v1/metadata/games",mapOf("q" to title,"limit" to "30"))).opt("games")).map {
            Match(it.getString("id"),it.getString("name"),it.optString("system_name",it.optString("system_id")),it)
        }
        "ScreenScraper" -> {
            var found = emptyList<Match>()
            for (query in searchQueries(title)) {
                found = screenScraperMatches(ss("jeuRecherche", mapOf("recherche" to query)),ScrapePolicy.region(context))
                if (found.isNotEmpty()) break
            }
            val wanted = searchWords(title)
            found.sortedByDescending { match ->
                val names = objects(match.raw.opt("noms")).map { it.optString("text") } + match.title
                names.maxOf { name -> searchWords(name).intersect(wanted).size }
            }
        }
        "SteamGridDB" -> objects(json("https://www.steamgriddb.com/api/v2/search/autocomplete/${Uri.encode(title)}").opt("data")).map {
            Match(it.getString("id"),it.getString("name"),"",it)
        }
        "TheGamesDB" -> objects(json(query("https://api.thegamesdb.net/v1.1/Games/ByGameName", mapOf("apikey" to required("apiKey"),"name" to title,"fields" to "overview,genres,publishers,developers"))).optJSONObject("data")?.opt("games")).map {
            Match(it.getString("id"),it.getString("game_title"),"Platform ${it.optString("platform")}",it)
        }
        else -> emptyList()
    }
    fun media(match: Match): Pair<JSONObject,List<Media>> = when(provider) {
        "NeoAssets" -> {
            val game=json("https://api.neoassets.dev/api/v1/metadata/games/${Uri.encode(match.id)}")
            game to objects(game.opt("media")).map {
                Media(it.getString("kind"),"https://cdn.neoassets.dev/"+it.getString("object_key"),it.optString("mime","image/webp").substringAfter('/'))
            }
        }
        "ScreenScraper" -> {
            require(match.id.matches(Regex("[1-9][0-9]*"))) { "ScreenScraper returned an invalid game match. Please search again." }
            val parameters = mutableMapOf("gameid" to match.id)
            match.raw.optJSONObject("systeme")?.optString("id")?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.let { parameters["systemeid"] = it }
            val game = if(hasEmbeddedMedia(match))match.raw else ss("jeuInfos", parameters).getJSONObject("jeu")
            game to objects(game.opt("medias")).mapNotNull {
                val url = it.optString("url"); if (url.isBlank()) null else Media(it.optString("type"),url,it.optString("format","png"),it.optString("region"),it.optString("langue",it.optString("language")))
            }
        }
        "SteamGridDB" -> match.raw to listOf("grids","heroes","logos","icons").flatMap { type ->
            objects(json("https://www.steamgriddb.com/api/v2/$type/game/${Uri.encode(match.id)}?types=static&nsfw=false").opt("data")).take(1).map {
                Media(type,it.getString("url"),it.optString("mime","image/png").substringAfter('/'))
            }
        }
        "TheGamesDB" -> {
            val images = json(query("https://api.thegamesdb.net/v1/Games/Images",mapOf("apikey" to required("apiKey"),"games_id" to match.id))).getJSONObject("data")
            val base = images.getJSONObject("base_url").getString("original")
            match.raw to objects(images.optJSONObject("images")?.opt(match.id)).map { Media(it.optString("type","image"),base+it.getString("filename"),it.getString("filename").substringAfterLast('.',"png")) }
        }
        else -> match.raw to emptyList()
    }
    fun packs() = objects(json("https://api.neoassets.dev/api/v1/packs").opt("themes"))
    fun pack(folder: String) = json("https://api.neoassets.dev/api/v1/packs/${Uri.encode(folder)}/download")
    internal fun packPreview(path:String) = downloadBytes("https://cdn.neoassets.dev/$path",maxMegabytes=8)
    companion object {
        private val accountCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Int>>()
        internal fun hasEmbeddedMedia(match:Match)=objects(match.raw.opt("medias")).any { it.optString("url").startsWith("https://") }

        private fun searchWords(title: String): Set<String> = title.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()

        fun searchQueries(title: String): List<String> {
            val clean = title.replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
            val words = clean.split(' ').filter { it.isNotBlank() }
            return listOf(clean, title.trim(),
                if (words.size > 2) words.takeLast(2).joinToString(" ") else clean,
                if (words.size > 2) words.last() else clean,
                title.substringBefore(':').substringBefore(" - ").trim())
                .filter { it.length >= 3 }.distinct()
        }

        fun screenScraperMatches(response: JSONObject,region:String="us"): List<Match> = objects(response.opt("jeux")).mapNotNull { game ->
            // No-match searches can return [{}], rather than an empty array.
            val id = game.optString("id")
            if (!id.matches(Regex("[1-9][0-9]*"))) return@mapNotNull null
            val names = objects(game.opt("noms")).filter { it.optString("text").isNotBlank() }
            val name = names.firstOrNull { it.optString("region")==region }
                ?: names.firstOrNull { it.optString("region") in listOf("wor", "us", "eu") }
                ?: names.firstOrNull() ?: return@mapNotNull null
            Match(id, name.getString("text"), game.optJSONObject("systeme")?.optString("text").orEmpty(), game)
        }
        fun objects(value: Any?): List<JSONObject> = when(value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            is JSONObject -> value.keys().asSequence().mapNotNull { value.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        fun gameKey(uri: String) = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
