package au.orbit.frontend

import org.json.JSONArray
import org.json.JSONObject

/** Select system media by exact category and preferred region, never by game title. */
internal object SystemArtworkMedia {
    enum class Kind(val title: String, val role: String, val types: List<String>) {
        PHOTO("Console photos", "system", listOf("photo")),
        LOGO("System logos", "systemLogo", listOf("wheel", "logo", "logo-monochrome", "logomonochrome")),
        BACKGROUND("System backgrounds", "hero", listOf("background", "illustration", "fanart"))
    }
    private data class Media(val type: String, val region: String, val url: String)

    fun select(system: JSONObject, kind: Kind, region: String): String? {
        val media = mutableListOf<Media>()
        fun add(type: String, area: String, url: String) {
            if (url.startsWith("https://") && type in kind.types) media += Media(type, area, url)
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
                is JSONObject -> {
                    add(value.optString("type"), value.optString("region"), value.optString("url"))
                    value.keys().asSequence().toList().sorted().forEach { key ->
                        val child = value.opt(key)
                        if (child is String) {
                            val match = Regex("^media_(photo|wheel|logo|logo-monochrome|logomonochrome|background|illustration|fanart)(?:_([a-z]{2,3}))?$").matchEntire(key)
                            if (match != null) add(match.groupValues[1], match.groupValues[2], child)
                        } else visit(child)
                    }
                }
            }
        }
        visit(system.opt("medias"))
        val regions = listOf(region, "wor", "us", "eu", "jp", "").distinct()
        return media.sortedWith(compareBy<Media> { kind.types.indexOf(it.type) }
            .thenBy { regions.indexOf(it.region).takeIf { n -> n >= 0 } ?: regions.size }
            .thenBy { it.url }).firstOrNull()?.url
    }
}
