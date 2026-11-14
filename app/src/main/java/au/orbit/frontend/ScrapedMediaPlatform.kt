package au.orbit.frontend

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Uses provider console IDs because Orbit saves files under the ROM's system folder. */
internal object ScrapedMediaPlatform {
    fun sourceId(provider: String, metadata: JSONObject?): String? {
        if (provider != "ScreenScraper") return null
        return metadata?.optJSONObject("systeme")?.optString("id")?.takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }
    }

    fun matchesMediaUrl(url: String, systemId: String?, gameId: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && (systemId == null || parsed.queryParameterValues("systemeid").all { it == systemId }) &&
            parsed.queryParameterValues("jeuid").all { it == gameId }
    }

    fun matches(record: JSONObject, systemId: String): Boolean {
        if (!record.optString("provider").equals("ScreenScraper", ignoreCase = true)) return true
        val expected = ScreenScraperMatch.platformForId(systemId) ?: return true
        val source = sourceId("ScreenScraper", record.optJSONObject("metadata")) ?: return true
        return source == expected
    }
}
