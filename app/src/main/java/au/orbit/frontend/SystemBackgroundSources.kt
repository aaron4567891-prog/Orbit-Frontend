package au.orbit.frontend

import org.json.JSONObject

internal object SystemBackgroundSources {
    data class Pack(val name: String, val author: String, val project: String,
        val images: Map<String, String>, val neoFolder: String? = null, val bundled: Boolean = false) {
        fun image(systemId: String): String? = keys(systemId).firstNotNullOfOrNull(images::get)
        fun preview(systems: List<String>): String? = (systems + listOf("psx", "snes", "gba", "n64"))
            .firstNotNullOfOrNull(::image) ?: images.values.firstOrNull()
    }
    fun defaults() = Pack("Orbit defaults · Curated system heroes", "SteamGridDB artists and credited fallback artists",
        "https://www.steamgriddb.com", systems.associate { it.id to SystemDefaultBackgrounds.key(it.id) }, bundled=true)
    data class Style(val name: String, val directory: String)
    data class ThemeSource(val name: String, val author: String, val repository: String, val styles: List<Style>) {
        val project get() = "https://github.com/$repository"
        val tree get() = "https://api.github.com/repos/$repository/git/trees/main?recursive=1"
    }
    val iconic = ThemeSource("Iconic", "Siddy212", "Siddy212/iconic-es-de", listOf(
        Style("Default", "default-backgrounds"), Style("Classic", "classic-backgrounds"),
        Style("Modern", "modern-backgrounds"), Style("Alternate", "alternate-backgrounds")))
    val artBook = ThemeSource("Art Book Next", "Anthony Caccese", "anthonycaccese/art-book-next-es-de", listOf(
        Style("Original", "artwork"), Style("Noir", "artwork-noir"), Style("Outline", "artwork-outline"),
        Style("Circuit", "artwork-circuit"), Style("Screenshots", "artwork-screenshots")))

    // ES-DE names and NeoAssets names differ for several common platforms.
    private val aliases = mapOf(
        "psx" to listOf("ps1", "ps"), "megadrive" to listOf("genesis"),
        "megadrivejp" to listOf("megadrive", "genesis"), "snesna" to listOf("snes"), "sfc" to listOf("snes"),
        "3ds" to listOf("n3ds"), "gc" to listOf("gamecube"), "nds" to listOf("ds"),
        "mastersystem" to listOf("sms"), "sega32x" to listOf("32x"),
        "segacd" to listOf("megacd", "sega-cd"), "megacd" to listOf("segacd", "sega-cd"),
        "pcengine" to listOf("tg16", "pce"), "pcenginecd" to listOf("pcecd", "tgcd"),
        "atari2600" to listOf("2600"), "atari5200" to listOf("5200"), "atari7800" to listOf("7800"),
        "atarilynx" to listOf("lynx"), "atarijaguar" to listOf("jaguar"),
        "colecovision" to listOf("coleco"), "odyssey2" to listOf("videopac"),
        "wonderswan" to listOf("ws"), "wonderswancolor" to listOf("wsc"),
        "psvita" to listOf("vita"), "amigacd32" to listOf("amiga-cd32"))
    internal fun keys(id: String) = listOf(id) + aliases[id].orEmpty()

    fun themePack(source: ThemeSource, style: Style, tree: JSONObject): Pack {
        require(style in source.styles)
        check(!tree.optBoolean("truncated")) { "The artwork catalogue is incomplete. Try again later." }
        val prefix = "_inc/systems/${style.directory}/"
        val images = ScraperClient.objects(tree.opt("tree")).mapNotNull { entry ->
            val path = entry.optString("path")
            val file = path.removePrefix(prefix)
            if (entry.optString("type") != "blob" || !path.startsWith(prefix) ||
                !file.matches(Regex("[a-zA-Z0-9_-]+\\.(?:png|jpg|jpeg|webp)"))) return@mapNotNull null
            file.substringBeforeLast('.') to "https://raw.githubusercontent.com/${source.repository}/main/$path"
        }.toMap()
        return Pack("${source.name} · ${style.name}", source.author, source.project, images)
    }

    fun neoPack(detail: JSONObject): Pack {
        val folder = detail.getString("folder")
        require(folder.matches(Regex("[a-zA-Z0-9_-]+")))
        val images = ScraperClient.objects(detail.opt("files")).mapNotNull { file ->
            val id = file.optString("system_id")
            val url = file.optString("url")
            if (file.optString("kind") != "background" || !id.matches(Regex("[a-zA-Z0-9_-]+")) ||
                !url.startsWith("https://cdn.neoassets.dev/packs/$folder/backgrounds/") ||
                !url.substringAfterLast('/').matches(Regex("[a-zA-Z0-9_-]+\\.(?:png|jpg|jpeg|webp|gif)"))) return@mapNotNull null
            id to url
        }.toMap()
        return Pack("NeoAssets · ${detail.optString("name", folder)}", detail.optString("author"),
            "https://github.com/misobadev/neoassets-service", images, folder)
    }
}
