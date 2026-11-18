package au.orbit.frontend

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

internal object RetroAchievements {
    class Account(val username: String, val key: String)
    open class RequestError(message: String) : Exception(message)
    class AuthenticationError : RequestError("Check your RetroAchievements username and Web API key.")
    data class Profile(val name: String, val points: Int, val softcorePoints: Int)
    data class Unlock(val id: Int, val title: String, val game: String, val points: Int, val hardcore: Boolean)
    data class GameProgress(val id: Int, val title: String, val system: String, val earned: Int,
        val hardcore: Int, val total: Int)
    data class ProgressPage(val total: Int, val games: List<GameProgress>)

    const val ACCOUNT_URL = "https://retroachievements.org/controlpanel.php"
    const val PAGE_SIZE = 25
    private val client = ScraperHttp.client.newBuilder()
        .followRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()

    private fun preferences(context: Context) = context.getSharedPreferences("retroachievements", Context.MODE_PRIVATE)

    fun account(context: Context): Account? {
        val prefs = preferences(context)
        val username = prefs.getString("username", "").orEmpty()
        val key = prefs.getString("key", "").orEmpty()
        return if (username.isBlank() || key.isBlank()) null else Account(username, key)
    }

    fun save(context: Context, account: Account?) {
        val editor = preferences(context).edit()
        if (account == null) editor.clear()
        else editor.putString("username", account.username).putString("key", account.key)
        editor.apply()
    }

    fun profile(account: Account): Profile = parseProfile(request(account, "GetUserProfile") as JSONObject)

    fun recent(account: Account): List<Unlock> =
        parseUnlocks(request(account, "GetUserRecentAchievements", "m" to "1440") as JSONArray)

    fun progress(account: Account, offset: Int): ProgressPage =
        parseProgress(request(account, "GetUserCompletionProgress", "o" to offset.toString(),
            "c" to PAGE_SIZE.toString()) as JSONObject)

    internal fun parseProfile(data: JSONObject) =
        Profile(data.getString("User"), data.getInt("TotalPoints"), data.optInt("TotalSoftcorePoints"))

    internal fun parseUnlocks(data: JSONArray): List<Unlock> = (0 until minOf(data.length(), 25)).map { index ->
        val item = data.getJSONObject(index)
        Unlock(item.getInt("AchievementID"), item.getString("Title"), item.getString("GameTitle"),
            item.getInt("Points"), item.optInt("HardcoreMode") == 1 || item.optBoolean("HardcoreMode"))
    }

    internal fun parseProgress(data: JSONObject): ProgressPage {
        val rows = data.getJSONArray("Results")
        return ProgressPage(data.getInt("Total"), (0 until rows.length()).map { index ->
            val game = rows.getJSONObject(index)
            GameProgress(game.getInt("GameID"), game.getString("Title"), game.getString("ConsoleName"),
                game.getInt("NumAwarded"), game.getInt("NumAwardedHardcore"), game.getInt("MaxPossible"))
        })
    }


    data class GameSet(val id: Int, val title: String)
    data class Badge(val id: Int, val title: String, val description: String, val image: String, val points: Int,
        val order: Int, val earnedOn: String?, val hardcoreOn: String?) {
        val hardcore get() = hardcoreOn != null
        val earned get() = earnedOn != null || hardcore
        val status get() = if (hardcore) "Hardcore" else if (earned) "Unlocked" else "Locked"

        fun progressSummary(): String = listOfNotNull(
            status, "$points points", (hardcoreOn ?: earnedOn)?.take(10)
        ).joinToString(" · ")
    }

    private val consoleIds = mapOf(
        "megadrive" to 1, "megadrivejp" to 1, "genesis" to 1, "n64" to 2,
        "snes" to 3, "snesna" to 3, "sfc" to 3, "gb" to 4, "sgb" to 4, "gba" to 5, "gbc" to 6,
        "nes" to 7, "famicom" to 7, "pcengine" to 8, "tg16" to 8, "supergrafx" to 8,
        "segacd" to 9, "megacd" to 9, "megacdjp" to 9, "sega32x" to 10, "sega32xjp" to 10, "sega32xna" to 10,
        "mastersystem" to 11, "mark3" to 11, "psx" to 12, "atarilynx" to 13, "ngp" to 14, "ngpc" to 14,
        "gamegear" to 15, "gc" to 16, "atarijaguar" to 17, "nds" to 18, "wii" to 19, "wiiu" to 20,
        "ps2" to 21, "xbox" to 22, "odyssey2" to 23, "videopac" to 23, "pokemini" to 24, "atari2600" to 25,
        "dos" to 26, "arcade" to 27, "mame" to 27, "mame-advmame" to 27, "fbneo" to 27, "fba" to 27,
        "cps" to 27, "cps1" to 27, "cps2" to 27, "cps3" to 27, "neogeo" to 27, "virtualboy" to 28,
        "msx" to 29, "msx1" to 29, "msx2" to 29, "msxturbor" to 29, "c64" to 30, "zx81" to 31,
        "oric" to 32, "sg-1000" to 33, "vic20" to 34, "amiga" to 35, "amiga600" to 35,
        "amiga1200" to 35, "amigacd32" to 35, "atarist" to 36,
        "amstradcpc" to 37, "gx4000" to 37, "apple2" to 38, "saturn" to 39, "saturnjp" to 39,
        "dreamcast" to 40, "psp" to 41, "cdimono1" to 42, "3do" to 43, "colecovision" to 44,
        "intellivision" to 45, "vectrex" to 46, "pc88" to 47, "pc98" to 48, "pcfx" to 49,
        "atari5200" to 50, "atari7800" to 51, "x68000" to 52, "wonderswan" to 53, "wonderswancolor" to 53,
        "scv" to 55, "neogeocd" to 56, "neogeocdjp" to 56, "channelf" to 57,
        "fmtowns" to 58, "zxspectrum" to 59, "gameandwatch" to 60, "ngage" to 61, "n3ds" to 62,
        "supervision" to 63, "x1" to 64, "tic80" to 65, "to8" to 66, "megaduck" to 69,
        "arduboy" to 71, "wasm4" to 72, "arcadia" to 73, "pcenginecd" to 76, "tg-cd" to 76,
        "atarijaguarcd" to 77, "uzebox" to 80, "fds" to 81, "ps3" to 82
    )
    private val titleTags = Regex("""\s*[\[(](?:USA|Europe|Japan|World|Asia|Australia|Canada|Korea|France|Germany|Spain|Italy|En\b|Rev\b|Disc\b|Disk\b|Beta\b|Proto\b)[^\])]*[\])]""", RegexOption.IGNORE_CASE)
    private val trailingArticle = Regex(""", (The|A|An)$""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""[^\p{L}\p{N}]+""")
    private val badgeName = Regex("[0-9]+")

    internal fun titleKey(title: String): String {
        val clean = title.replace(titleTags, "").trim()
        val article = trailingArticle.find(clean)
        val ordered = if (article == null) clean else "${article.groupValues[1]} ${clean.substring(0, article.range.first)}"
        return java.text.Normalizer.normalize(ordered.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFKD)
            .replace(separators, "")
    }

    internal fun matchingSets(catalog: JSONArray, title: String): List<GameSet> {
        val key = titleKey(title)
        if (key.isEmpty()) return emptyList()
        return (0 until catalog.length()).mapNotNull { index ->
            val item = catalog.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("Title")
            val id = item.optInt("ID")
            if (id > 0 && titleKey(name) == key) GameSet(id, name) else null
        }.distinctBy { it.id }
    }

    fun match(context: Context, account: Account, system: SystemSpec, game: Game): List<GameSet> {
        val console = consoleIds[system.id] ?: return emptyList()
        val directory = java.io.File(context.cacheDir, "ra-catalog")
        val file = android.util.AtomicFile(java.io.File(directory, "$console.json"))
        val cached = runCatching { JSONArray(file.readFully().toString(Charsets.UTF_8)) }.getOrNull()
        val catalog = if (cached != null && System.currentTimeMillis() - file.baseFile.lastModified() < TimeUnit.DAYS.toMillis(7)) {
            cached
        } else {
            val fresh = try {
                request(account, "GetGameList", "i" to console.toString(), "f" to "1") as JSONArray
            } catch (error: java.io.IOException) {
                cached ?: throw error
            }
            if (fresh !== cached) runCatching {
                val output = file.startWrite()
                try {
                    output.write(fresh.toString().toByteArray())
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
            fresh
        }
        return matchingSets(catalog, game.title).ifEmpty {
            matchingSets(catalog, game.fileName.substringBeforeLast('.'))
        }
    }

    fun linkedSet(context: Context, gameKey: String): Int? =
        context.getSharedPreferences("retroachievements_games", Context.MODE_PRIVATE)
            .getInt(gameKey, 0).takeIf { it > 0 }

    fun linkSet(context: Context, gameKey: String, id: Int?) {
        context.getSharedPreferences("retroachievements_games", Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(gameKey) else putInt(gameKey, id)
        }.apply()
    }

    fun gameBadges(account: Account, id: Int): Pair<String, List<Badge>> {
        val data = request(account, "GetGameInfoAndUserProgress", "g" to id.toString()) as JSONObject
        val title = data.optString("Title").takeIf(String::isNotBlank)
            ?: throw RequestError("No achievement set found for game #$id.")
        return title to parseBadges(data)
    }

    internal fun parseBadges(data: JSONObject): List<Badge> {
        val achievements = data.optJSONObject("Achievements") ?: return emptyList()
        return achievements.keys().asSequence().mapNotNull { key ->
            val item = achievements.optJSONObject(key) ?: return@mapNotNull null
            fun earnedOn(field: String) = if (item.isNull(field)) null
                else item.optString(field).takeIf(String::isNotBlank)
            Badge(item.getInt("ID"), item.getString("Title"), item.optString("Description"),
                item.optString("BadgeName").takeIf(badgeName::matches).orEmpty(), item.optInt("Points"),
                item.optInt("DisplayOrder"), earnedOn("DateEarned"), earnedOn("DateEarnedHardcore"))
        }.sortedWith(compareBy<Badge> { it.order }.thenBy { it.id }).toList()
    }

    fun imageRequest(name: String): okhttp3.Call =
        client.newCall(Request.Builder().url("https://media.retroachievements.org/Badge/$name.png").build())

    private fun request(account: Account, endpoint: String, vararg options: Pair<String, String>): Any {
        val url = "https://retroachievements.org/API/".toHttpUrl().newBuilder()
            .addPathSegment("API_$endpoint.php")
            .addQueryParameter("u", account.username)
            .addQueryParameter("y", account.key)
            .apply { options.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw AuthenticationError()
            if (!response.isSuccessful) throw RequestError(when (response.code) {
                429 -> "RetroAchievements is receiving too many requests. Try again shortly."
                else -> "RetroAchievements returned HTTP ${response.code}."
            })
            val data = JSONTokener(response.body.string()).nextValue()
            if (data is JSONObject && (data.has("error") || data.has("Error"))) {
                throw AuthenticationError()
            }
            return data
        }
    }
}
