package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Html
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ArtworkSearchClient(private val context: Context) {
    enum class Source(val label: String) {
        COMMONS("Wikimedia Commons"),
        WALLHAVEN("Wallhaven"),
        FLICKR("Flickr via Openverse"),
        STEAM("SteamGridDB")
    }

    data class Result(
        val title: String,
        val credit: String,
        val image: HttpUrl,
        val thumbnail: HttpUrl,
        val sourcePage: HttpUrl?,
        val dimensions: ArtworkDimensions?,
        val provider: String = ""
    )

    data class SearchResults(val images: List<Result>, val failedSources: List<String>)

    private class HttpFailure(val status: Int, message: String) : IOException(message)

    private val activeCalls = mutableSetOf<Call>()

    @Synchronized fun cancel() { activeCalls.forEach(Call::cancel) }

    fun search(query: String, role: String, targets: List<ArtworkDimensions>): SearchResults {
        val steamKey = ScraperCredentials.value(context, Source.STEAM.label, "apiKey")
        val sources = Source.entries.filter {
            it != Source.STEAM || (role != "physicalmedia" && steamKey.isNotBlank())
        }
        val keyword = when (role) {
            "covers" -> "box art"
            "physicalmedia" -> "game media"
            "marquees" -> "logo"
            else -> ""
        }
        val webQuery = if (keyword.isEmpty() || query.contains(keyword, ignoreCase = true)) query else "$query $keyword"
        val searches = searchWorkers.invokeAll(sources.map { source ->
            Callable {
                runCatching {
                    val images = when (source) {
                        Source.COMMONS -> try {
                            commons(webQuery)
                        } catch (denied: HttpFailure) {
                            if (denied.status != 401 && denied.status != 403) throw denied
                            openverseCommons(webQuery)
                        }
                        Source.WALLHAVEN -> wallhaven(webQuery)
                        Source.FLICKR -> flickr(webQuery)
                        Source.STEAM -> steam(query, role, targets, steamKey)
                    }
                    images.bestFitFirst(targets).take(12).map { it.copy(provider = source.label) }
                }
            }
        }, 13, TimeUnit.SECONDS)
        val completed = searches.map { if (it.isCancelled) null else it.get().getOrNull() }
        val images = (0 until 12).flatMap { index -> completed.mapNotNull { it?.getOrNull(index) } }
            .distinctBy { it.image.newBuilder().fragment(null).build() }
            .bestFitFirst(targets)
        return SearchResults(images, sources.zip(completed).mapNotNull { (source, results) ->
            source.label.takeIf { results == null }
        })
    }

    private fun List<Result>.bestFitFirst(targets: List<ArtworkDimensions>): List<Result> {
        if (targets.isEmpty()) return this
        return sortedByDescending { result ->
            result.dimensions?.fitFor(targets) ?: -1.0
        }
    }

    private fun commons(query: String): List<Result> {
        val endpoint = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("action", "query")
            addQueryParameter("format", "json")
            addQueryParameter("formatversion", "2")
            addQueryParameter("generator", "search")
            addQueryParameter("gsrsearch", "$query filetype:bitmap")
            addQueryParameter("gsrnamespace", "6")
            addQueryParameter("gsrlimit", "24")
            addQueryParameter("prop", "imageinfo")
            addQueryParameter("iiprop", "url|mime|size|extmetadata")
            addQueryParameter("iiurlwidth", "330")
            addQueryParameter("iiextmetadatafilter", "Artist|LicenseShortName")
        }.build()
        val pages = ScraperClient.objects(json(endpoint).optJSONObject("query")?.opt("pages"))
        val ordered = pages.sortedBy { it.optInt("index", Int.MAX_VALUE) }
        return ordered.mapNotNull { page ->
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            if (info.optString("mime") !in imageTypes || info.optLong("size") > OrbitThemeArtwork.MAX_IMAGE_BYTES) return@mapNotNull null
            val image = mediaUrl(info.optString("url"), commonsMediaHosts) ?: return@mapNotNull null
            val thumb = mediaUrl(info.optString("thumburl"), commonsMediaHosts) ?: image
            val metadata = info.optJSONObject("extmetadata")
            val credit = listOf("Artist", "LicenseShortName").mapNotNull { key ->
                metadata?.optJSONObject(key)?.optString("value")?.takeIf(String::isNotBlank)?.let {
                    Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim().take(120)
                }
            }.joinToString(" · ")
            val title = page.optString("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
            val sourcePage = mediaUrl(info.optString("descriptionurl"), setOf("commons.wikimedia.org"))
            Result(title, credit, image, thumb, sourcePage, dimensions(info))
        }.distinctBy { it.image }
    }

    private fun openverseCommons(query: String): List<Result> {
        val entries = openverseEntries(query, "wikimedia")
        return entries.mapNotNull { entry ->
            val image = mediaUrl(entry.optString("url"), setOf("upload.wikimedia.org")) ?: return@mapNotNull null
            val path = image.encodedPath
            val prefix = "/wikipedia/commons/"
            if (!path.startsWith(prefix) || image.pathSegments.size != 5) return@mapNotNull null
            val filename = path.substringAfterLast('/')
            val thumbnail = image.newBuilder().host("thumb.wikimedia.org")
                .encodedPath("${prefix}thumb/${path.removePrefix(prefix)}/330px-$filename").query(null).build()
            val sourcePage = mediaUrl(entry.optString("foreign_landing_url"), setOf("commons.wikimedia.org"))
            Result(entry.optString("title", filename), openverseCredit(entry), image, thumbnail, sourcePage, dimensions(entry))
        }.distinctBy { it.image }
    }

    private fun flickr(query: String): List<Result> = openverseEntries(query, "flickr")
        .mapNotNull { entry ->
            val image = mediaUrl(entry.optString("url"), setOf("live.staticflickr.com")) ?: return@mapNotNull null
            val thumbnail = mediaUrl(entry.optString("thumbnail"), setOf("api.openverse.org")) ?: image
            val sourcePage = mediaUrl(entry.optString("foreign_landing_url"), setOf("www.flickr.com", "flickr.com"))
            Result(entry.optString("title").ifBlank { "Flickr photo" }, openverseCredit(entry), image, thumbnail, sourcePage, dimensions(entry))
        }.distinctBy { it.image }

    private fun openverseEntries(query: String, source: String): List<JSONObject> {
        val endpoint = "https://api.openverse.org/v1/images/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("source", source)
            .addQueryParameter("extension", "jpg,jpeg,png,webp")
            .addQueryParameter("page_size", "18").build()
        return ScraperClient.objects(json(endpoint).opt("results"))
    }

    private fun openverseCredit(entry: JSONObject): String = listOf(entry.optString("creator"), entry.optString("license"))
        .filter { it.isNotBlank() && it != "null" }.joinToString(" · ")

    private fun wallhaven(query: String): List<Result> {
        val endpoint = "https://wallhaven.cc/api/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("categories", "111")
            .addQueryParameter("purity", "100")
            .addQueryParameter("sorting", "relevance").build()
        return ScraperClient.objects(json(endpoint).opt("data")).mapNotNull { entry ->
            if (entry.optString("purity") != "sfw" || entry.optString("file_type") !in imageTypes ||
                entry.optLong("file_size") > OrbitThemeArtwork.MAX_IMAGE_BYTES) return@mapNotNull null
            val image = mediaUrl(entry.optString("path"), setOf("w.wallhaven.cc")) ?: return@mapNotNull null
            val thumbnail = mediaUrl(entry.optJSONObject("thumbs")?.optString("large").orEmpty(), setOf("th.wallhaven.cc")) ?: image
            val sourcePage = mediaUrl(entry.optString("url"), setOf("wallhaven.cc"))
            val size = ArtworkDimensions.from(entry.optInt("dimension_x"), entry.optInt("dimension_y"))
            Result("Wallpaper ${entry.optString("id")}", "Wallhaven", image, thumbnail, sourcePage, size)
        }.distinctBy { it.image }
    }

    private fun steam(query: String, role: String, targets: List<ArtworkDimensions>, key: String): List<Result> {
        val base = "https://www.steamgriddb.com/api/v2".toHttpUrl()
        val search = base.newBuilder().addPathSegments("search/autocomplete").addPathSegment(query).build()
        val matches = ScraperClient.objects(json(search, key, missingAllowed = true).opt("data"))
        val (type, pageType) = when (role) {
            "covers" -> "grids" to "grid"
            "system", "marquees" -> "logos" to "logo"
            else -> "heroes" to "hero"
        }
        return matches.take(3).flatMap { game ->
            val id = game.optLong("id")
            if (id <= 0) return@flatMap emptyList()
            val endpoint = base.newBuilder().addPathSegments("$type/game/$id")
                .addQueryParameter("types", "static").addQueryParameter("nsfw", "false")
                .addQueryParameter("humor", "false").build()
            ScraperClient.objects(json(endpoint, key, missingAllowed = true).opt("data")).mapNotNull { art ->
                val mime = art.optString("mime")
                if (art.optBoolean("nsfw") || (mime.isNotBlank() && mime !in imageTypes)) return@mapNotNull null
                val image = steamMediaUrl(art.optString("url")) ?: return@mapNotNull null
                val thumbnail = steamMediaUrl(art.optString("thumb")) ?: image
                val artId = art.optLong("id")
                val page = if (artId > 0) "https://www.steamgriddb.com/$pageType/$artId".toHttpUrl() else null
                val credit = art.optJSONObject("author")?.optString("name").orEmpty()
                Result(game.optString("name"), credit, image, thumbnail, page, dimensions(art))
            }.bestFitFirst(targets).take(if (matches.size == 1) 18 else 6)
        }.distinctBy { it.image }
    }

    private fun dimensions(item: JSONObject): ArtworkDimensions? =
        ArtworkDimensions.from(item.optInt("width"), item.optInt("height"))

    fun thumbnail(result: Result): Bitmap? {
        val bytes = read(result.thumbnail, 2 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 440 || bounds.outHeight / sample > 440) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun download(result: Result): ByteArray = read(result.image, OrbitThemeArtwork.MAX_IMAGE_BYTES, timeout = 45)

    private fun json(url: HttpUrl, key: String? = null, missingAllowed: Boolean = false): JSONObject {
        val bytes = read(url, 1024 * 1024, key, missingAllowed = missingAllowed)
        if (missingAllowed && bytes.isEmpty()) return JSONObject()
        val data = JSONObject(bytes.toString(Charsets.UTF_8))
        check(!data.has("error") && data.optBoolean("success", true)) { "Artwork search returned an error. Try a different search." }
        return data
    }

    private fun read(url: HttpUrl, limit: Int, key: String? = null, timeout: Long = 12, missingAllowed: Boolean = false): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", "OrbitAndroidFrontend/0.1 (interactive artwork search)")
        if (key != null && url.host == "www.steamgriddb.com") request.header("Authorization", "Bearer $key")
        val call = synchronized(this) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            client.newCall(request.build()).also { activeCalls.add(it) }
        }
        call.timeout().timeout(timeout, TimeUnit.SECONDS)
        try {
            call.execute().use { response ->
                if (missingAllowed && response.code == 404) return ByteArray(0)
                if (!response.isSuccessful) throw HttpFailure(response.code, when (response.code) {
                    401, 403 -> if (key != null) "SteamGridDB rejected the API key. Check Scraping → Account." else "${url.host} denied access (HTTP ${response.code})."
                    429 -> "The artwork source is busy. Try again shortly."
                    else -> "Artwork request failed (HTTP ${response.code})."
                })
                val body = response.body
                check(body.contentLength() <= limit) { "This image is too large to load." }
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= limit) { "This image is too large to load." }
                        output.write(buffer, 0, count)
                    }
                }
                return output.toByteArray()
            }
        } finally {
            synchronized(this) { activeCalls.remove(call) }
        }
    }

    companion object {
        private val searchWorkers = Executors.newFixedThreadPool(Source.entries.size)
        private val imageTypes = setOf("image/png", "image/jpeg", "image/webp")
        private val commonsMediaHosts = setOf("upload.wikimedia.org", "thumb.wikimedia.org")
        private val steamMediaHosts = setOf("cdn.steamgriddb.com", "s3.amazonaws.com")
        private val client = ScraperHttp.client.newBuilder().followRedirects(false).build()

        private fun mediaUrl(value: String, hosts: Set<String>): HttpUrl? = value.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host in hosts && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() }

        private fun steamMediaUrl(value: String): HttpUrl? = mediaUrl(value, steamMediaHosts)
            ?.takeIf { it.host != "s3.amazonaws.com" || it.encodedPath.startsWith("/steamgriddb/") }
    }
}
