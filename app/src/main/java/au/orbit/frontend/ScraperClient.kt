package au.orbit.frontend

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Provider responses are data only. Credentials are never copied into saved metadata. */
class ScraperClient(private val context: Context, val provider: String) {
    data class Match(val id: String, val title: String, val platform: String, val raw: JSONObject,
        val hasGameDetails: Boolean = false)
    private class RequestFailure(val status: Int, message: String) : IllegalStateException(message)
    data class Media(val type: String, val url: String, val extension: String, val region: String = "", val language: String = "")
    var downloadConcurrency = if (provider == "ScreenScraper") cachedAllowance() ?: 1 else 3
        private set
    private fun credential(key: String) = ScraperCredentials.value(context, provider, key)
    private fun required(key: String) = credential(key).also { require(it.isNotBlank()) { "Configure $provider credentials first." } }
    private fun query(base: String, values: Map<String, String>) = Uri.parse(base).buildUpon().apply { values.forEach { (k,v) -> appendQueryParameter(k,v) } }.build().toString()
    private fun ss(endpoint: String, values: Map<String, String> = emptyMap()): JSONObject = json(query("https://api.screenscraper.fr/api2/$endpoint.php", mapOf(
        "devid" to required("developerId"), "devpassword" to required("developerPassword"), "softname" to "Orbit", "output" to "json",
        "ssid" to credential("username"), "sspassword" to credential("password")
    ) + values)).getJSONObject("response").also { response ->
        val reported = screenScraperAllowance(response)
        if (reported != null || endpoint == "ssuserInfos") {
            downloadConcurrency = reported ?: 1
            ScreenScraperRequests.configure(downloadConcurrency)
            accountCache[accountKey()] = System.nanoTime() to downloadConcurrency
        }
    }
    private fun accountKey()=gameKey(listOf(credential("developerId"),credential("username"),credential("password")).joinToString("\u0000"))
    private fun cachedAllowance() = accountCache[accountKey()]
        ?.takeIf { System.nanoTime() - it.first < java.util.concurrent.TimeUnit.MINUTES.toNanos(1) }?.second
    fun accountConcurrency(): Int {
        if (provider != "ScreenScraper") return ScrapePolicy.MAX_MANUAL_GAMES
        ss("ssuserInfos")
        return downloadConcurrency
    }
    private class InvalidProviderResponse(provider: String) :
        IllegalStateException("$provider returned an invalid response.")

    private fun json(url: String) = try { JSONObject(bytes(url).toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")) }
        catch (_: org.json.JSONException) { throw InvalidProviderResponse(provider) }
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
            if (!response.isSuccessful) throw RequestFailure(code, when(code) {
                401,403 -> "$provider rejected the credentials or access permission."
                429 -> "$provider rate limit reached. Please try again later."
                430, 431 -> "$provider daily scrape quota reached."
                else -> "$provider request failed (HTTP $code)."
            })
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

    fun search(title: String, system: SystemSpec? = null, playlist: Boolean = false): List<Match> = when (provider) {
        "NeoAssets" -> objects(json(query("https://api.neoassets.dev/api/v1/metadata/games",mapOf("q" to title,"limit" to "30"))).opt("games")).map {
            Match(it.getString("id"),it.getString("name"),it.optString("system_name",it.optString("system_id")),it)
        }
        "ScreenScraper" -> {
            val platform = ScreenScraperMatch.platform(system)
            val region = ScrapePolicy.region(context)
            val known = ScreenScraperMatch.knownGame(title, platform)
            var matches = listOfNotNull(known)
            var invalid: InvalidProviderResponse? = null
            for (query in if (known == null) searchQueries(title) else emptyList()) {
                val parameters = mutableMapOf("recherche" to query)
                if (platform != null) parameters["systemeid"] = platform
                val response = try {
                    ss("jeuRecherche", parameters)
                } catch (error: RequestFailure) {
                    if (error.status == 404) continue
                    throw error
                } catch (error: InvalidProviderResponse) {
                    invalid = error
                    continue
                }
                val candidates = screenScraperMatches(response, region)
                matches = ScreenScraperMatch.rank(title, candidates, platform, playlist)
                if (matches.isNotEmpty()) break
            }
            if (matches.isEmpty()) invalid?.let { throw it }
            matches
        }
        "SteamGridDB" -> objects(json("https://www.steamgriddb.com/api/v2/search/autocomplete/${Uri.encode(title)}").opt("data")).map {
            Match(it.getString("id"),it.getString("name"),"",it)
        }
        "TheGamesDB" -> objects(json(query("https://api.thegamesdb.net/v1.1/Games/ByGameName", mapOf("apikey" to required("apiKey"),"name" to title,"fields" to "overview,genres,publishers,developers"))).optJSONObject("data")?.opt("games")).map {
            Match(it.getString("id"),it.getString("game_title"),"Platform ${it.optString("platform")}",it)
        }
        else -> emptyList()
    }.let { matches -> if (playlist) matches.filterNot { RomScanner.hasDiscMarker(it.title) } else matches }
    internal fun searchGame(system: SystemSpec, game: Game): Match? {
        if (provider == "ScreenScraper") {
            val platform = ScreenScraperMatch.platform(system)
            if (platform != null) {
                val group = GameCustomArtwork.group(system, game)
                val saved = UserDataStore.readScraped(context, group, "screenscraper.json")?.let { bytes ->
                    runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                }
                ScreenScraperMatch.savedGameId(saved, platform, game.uri.toString(), game.title, game.isPlaylist)?.let { id ->
                    screenScraperGameById(id, platform, game.isPlaylist)?.let { return it }
                }
            }
        }
        val byTitle = search(game.title, system, game.isPlaylist).firstOrNull()
        if (byTitle != null || provider != "ScreenScraper" || game.isPlaylist || game.directory) return byTitle
        val platform = ScreenScraperMatch.platform(system) ?: return null
        val rom = smallRomChecksum(game) ?: return null
        val response = try {
            ss("jeuInfos", mapOf("systemeid" to platform, "romtype" to "rom",
                "romnom" to game.fileName, "romtaille" to rom.first.toString(),
                "crc" to java.lang.Long.toHexString(rom.second).padStart(8, '0')))
        } catch (error: RequestFailure) {
            if (error.status == 404) return null
            throw error
        }
        val result = response.optJSONObject("jeu") ?: return null
        if (result.optJSONObject("systeme")?.optString("id") != platform) return null
        return screenScraperMatches(JSONObject().put("jeux", JSONArray().put(result)), ScrapePolicy.region(context)).singleOrNull()
            ?.copy(hasGameDetails = true)
    }

    private fun screenScraperGameById(id: String, platform: String, playlist: Boolean): Match? {
        val result = try {
            ss("jeuInfos", mapOf("gameid" to id, "systemeid" to platform)).optJSONObject("jeu")
        } catch (error: RequestFailure) {
            if (error.status == 404) return null
            throw error
        } ?: return null
        if (result.optString("id") != id || ScrapedMediaPlatform.sourceId(provider, result) != platform) return null
        val match = screenScraperMatches(JSONObject().put("jeux", JSONArray().put(result)), ScrapePolicy.region(context))
            .singleOrNull() ?: return null
        return match.takeUnless { playlist && RomScanner.hasDiscMarker(it.title) }?.copy(hasGameDetails = true)
    }

    private fun smallRomChecksum(game: Game): Pair<Long, Long>? {
        val limit = 8L * 1024 * 1024
        return try {
            context.contentResolver.openAssetFileDescriptor(game.uri, "r")?.use { descriptor ->
                val length = descriptor.length
                if (length !in 1..limit) return null
                val crc = java.util.zip.CRC32()
                descriptor.createInputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > limit) return null
                        crc.update(buffer, 0, count)
                    }
                    if (read == length) length to crc.value else null
                }
            }
        } catch (_: java.io.IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun media(match: Match, neededFolders: Set<String>): Pair<JSONObject,List<Media>> = when(provider) {
        "NeoAssets" -> {
            val game=json("https://api.neoassets.dev/api/v1/metadata/games/${Uri.encode(match.id)}")
            game to objects(game.opt("media")).map {
                Media(it.getString("kind"),"https://cdn.neoassets.dev/"+it.getString("object_key"),it.optString("mime","image/webp").substringAfter('/'))
            }
        }
        "ScreenScraper" -> {
            require(match.id.matches(Regex("[1-9][0-9]*"))) { "ScreenScraper returned an invalid game match. Please search again." }
            val parameters = mutableMapOf("gameid" to match.id)
            val systemId = ScrapedMediaPlatform.sourceId(provider, match.raw)
            if (systemId != null) parameters["systemeid"] = systemId
            fun verifiedMedia(game: JSONObject): List<Media> = objects(game.opt("medias")).mapNotNull {
                val url = it.optString("url")
                if (!ScrapedMediaPlatform.matchesMediaUrl(url, systemId, match.id)) return@mapNotNull null
                Media(it.optString("type"), url, it.optString("format", "png"), it.optString("region"),
                    it.optString("langue", it.optString("language")))
            }
            val embedded = verifiedMedia(match.raw)
            val types = embedded.mapTo(mutableSetOf()) { it.type }
            val useEmbeddedMedia = embedded.isNotEmpty() &&
                ScraperMediaTypes.categories.filter { it.folder in neededFolders }.all { it.types.any(types::contains) }
            val game = if (match.hasGameDetails || useEmbeddedMedia) match.raw
                else ss("jeuInfos", parameters).getJSONObject("jeu")
            check(game.optString("id") == match.id) { "ScreenScraper returned an unexpected game." }
            game to if (game === match.raw) embedded else verifiedMedia(game)
        }
        "SteamGridDB" -> match.raw to steamGridMediaTypes.filterValues { it in neededFolders }.keys.flatMap { type ->
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
        internal fun screenScraperAllowance(response: JSONObject): Int? =
            response.optJSONObject("ssuser")?.optString("maxthreads")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        private val steamGridMediaTypes = mapOf("grids" to "covers", "heroes" to "fanart", "logos" to "marquees")
        private val accountCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Int>>()

        fun searchQueries(title: String): List<String> {
            val name = ScreenScraperMatch.cleanTitle(title)
            val clean = name.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
            val words = clean.split(' ').filter { it.isNotBlank() }
            val hyphenated = if (name == clean) when {
                words.size >= 2 -> clean.replaceFirst(' ', '-')
                clean.length >= 4 && clean.all(Char::isLetter) -> clean.take(1) + "-" + clean.drop(1)
                else -> clean
            } else clean
            return (ScreenScraperMatch.alternateQueries(title) + listOf(name, clean, hyphenated,
                if (words.size > 2) words.takeLast(2).joinToString(" ") else clean,
                if (words.size > 2) words.last() else clean,
                name.substringBefore(':').substringBefore(" - ").trim()))
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
        fun gameKey(uri: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            val hex = "0123456789abcdef"
            return String(CharArray(digest.size * 2).apply {
                digest.forEachIndexed { index, byte ->
                    val value = byte.toInt() and 0xff
                    this[index * 2] = hex[value ushr 4]
                    this[index * 2 + 1] = hex[value and 0x0f]
                }
            })
        }
    }
}
