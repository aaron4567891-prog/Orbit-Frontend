package au.orbit.frontend

internal object ScreenScraperMatch {
    private val systems = mapOf(
        "megadrive" to 1, "megadrivejp" to 1, "genesis" to 1, "mastersystem" to 2, "mark3" to 2,
        "nes" to 3, "famicom" to 3, "snes" to 4, "snesna" to 4, "sfc" to 4,
        "cps" to 6, "cps1" to 6, "cps2" to 7, "cps3" to 8,
        "gb" to 9, "sgb" to 9, "gbc" to 10, "virtualboy" to 11, "gba" to 12, "gc" to 13,
        "n64" to 14, "nds" to 15, "wii" to 16, "n3ds" to 17, "3ds" to 17, "wiiu" to 18,
        "sega32x" to 19, "sega32xjp" to 19, "sega32xna" to 19, "segacd" to 20, "megacd" to 20,
        "megacdjp" to 20, "gamegear" to 21, "saturn" to 22, "saturnjp" to 22, "dreamcast" to 23,
        "ngp" to 25, "atari2600" to 26, "atarijaguar" to 27, "atarilynx" to 28, "3do" to 29,
        "ngage" to 30, "pcengine" to 31, "tg16" to 31, "xbox" to 32, "xbox360" to 33,
        "bbcmicro" to 37, "atari5200" to 40, "atari7800" to 41, "atarist" to 42, "atari800" to 43,
        "atarixe" to 43, "astrocde" to 44, "wonderswan" to 45, "wonderswancolor" to 46, "colecovision" to 48,
        "daphne" to 49, "gameandwatch" to 52, "atomiswave" to 53, "model3" to 55, "naomi" to 56,
        "psx" to 57, "ps2" to 58, "ps3" to 59, "ps4" to 60, "psp" to 61, "psvita" to 62,
        "amiga" to 64, "amiga600" to 64, "amiga1200" to 64, "amstradcpc" to 65, "c64" to 66,
        "scv" to 67, "neogeocd" to 70, "neogeocdjp" to 70, "pcfx" to 72, "vic20" to 73,
        "pv1000" to 74,
        "zxspectrum" to 76, "zx81" to 77, "x68000" to 79, "channelf" to 80, "ngpc" to 82,
        "archimedes" to 84, "electron" to 85, "apple2" to 86, "gx4000" to 87, "adam" to 89,
        "megaduck" to 90, "dragon32" to 91, "arcadia" to 94, "fm7" to 97, "supracan" to 100,
        "vectrex" to 102, "gmaster" to 103, "odyssey2" to 104, "videopac" to 104, "supergrafx" to 105,
        "fds" to 106, "satellaview" to 107, "sufami" to 108, "sg-1000" to 109,
        "msx" to 113, "msx1" to 113, "pcenginecd" to 114, "tg-cd" to 114, "intellivision" to 115,
        "msx2" to 116, "msxturbor" to 118, "vsmile" to 120, "gamecom" to 121, "n64dd" to 122,
        "scummvm" to 123, "cdtv" to 129, "amigacd32" to 130, "oric" to 131, "cdimono1" to 133,
        "dos" to 135, "windows" to 138, "to8" to 141, "neogeo" to 142, "coco" to 144,
        "macintosh" to 146, "atarijaguarcd" to 171, "ti99" to 205, "lutro" to 206, "supervision" to 207,
        "pc98" to 208, "pokemini" to 211, "samcoupe" to 213, "openbor" to 214, "uzebox" to 216,
        "apple2gs" to 217, "spectravideo" to 218, "palm" to 219, "x1" to 220, "pc88" to 221,
        "tic80" to 222, "solarus" to 223, "switch" to 225, "easyrpg" to 231, "pico8" to 234,
        "crvision" to 241, "lowresnx" to 244, "fmtowns" to 253, "wasm4" to 262, "arduboy" to 263,
        "gamate" to 266, "vircon32" to 272
    )

    fun platform(system: SystemSpec?) = system?.id?.let(::platformForId)

    fun platformForId(systemId: String) = systems[systemId]?.toString()

    private val punctuation = Regex("""[^\p{L}\p{N}]+""")
    private val qualifiers = Regex("""\s*[\[(][^\])]*[\])]""")
    private val releaseRegions = setOf(
        "usa", "us", "north america", "europe", "eur", "japan", "jp", "world", "asia", "australia",
        "canada", "korea", "china", "taiwan", "hong kong", "brazil", "spain", "italy", "france",
        "germany", "russia", "uk", "united kingdom", "netherlands", "sweden", "denmark",
        "norway", "finland", "portugal", "mexico", "argentina", "ntsc", "pal"
    )
    private val releaseQualifier = Regex("""(?i)^(?:rev(?:ision)?\b.*|v\d.*|(?:disc|disk|cd)\s*[-. ]*(?:\d+|[ivx]+)(?:\s+of\s+\d+)?|(?:[a-z]{2}\s*,\s*)+[a-z]{2}|[a-z]{4}-\d{5}|edc|redump|[!])$""")
    private val trailingArticle = Regex(""",\s*(The|A|An)(?=$|\s*[-:–—])""", RegexOption.IGNORE_CASE)
    private val accents = Regex("""\p{M}+""")
    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10")

    internal fun cleanTitle(title: String): String = qualifiers.replace(title) { tag ->
        val value = tag.value.trim().drop(1).dropLast(1).trim()
        val regionTag = value.split(',').all { it.trim().lowercase(java.util.Locale.ROOT) in releaseRegions }
        if (regionTag || releaseQualifier.matches(value)) "" else " $value"
    }.trim()

    private fun titleKey(title: String): String {
        val clean = cleanTitle(title)
        val article = trailingArticle.find(clean)
        val ordered = if (article == null) clean else article.groupValues[1] + " " + clean.removeRange(article.range)
        return accents.replace(java.text.Normalizer.normalize(ordered.lowercase(java.util.Locale.ROOT),
            java.text.Normalizer.Form.NFD), "")
            .replace("&", " and ")
            .replace(Regex("""(?<=\p{L})[’'](?=\p{L})"""), "")
            .split(punctuation).filter(String::isNotBlank).joinToString(" ") { roman[it] ?: it }
    }

    internal fun savedGameId(record: org.json.JSONObject?, platform: String, romUri: String,
        title: String, playlist: Boolean): String? {
        if (record == null || !record.optString("provider").equals("ScreenScraper", ignoreCase = true) ||
            record.optString("romUri") != romUri) return null
        val id = record.optString("providerId").takeIf { it.matches(Regex("[1-9][0-9]*")) } ?: return null
        val metadata = record.optJSONObject("metadata") ?: return null
        if (ScrapedMediaPlatform.sourceId("ScreenScraper", metadata) != platform) return null
        val name = record.optString("title")
        if (playlist && RomScanner.hasDiscMarker(name)) return null
        if (record.optBoolean("screenScraperMatchVerified")) return id
        // Older records might have been saved by the loose title matcher.
        val candidate = ScraperClient.Match(id, name, "", metadata)
        return id.takeIf { knownGame(title, platform)?.id == id ||
            rank(title, listOf(candidate), platform, playlist).isNotEmpty() }
    }

    fun knownGame(title: String, platform: String?): ScraperClient.Match? {
        if (platform != "57") return null
        val key = titleKey(title)
        if (key !in setOf("casper friend around the world", "casper friends around the world",
                "casper freinds around the world")) return null
        val raw = org.json.JSONObject().put("systeme", org.json.JSONObject().put("id", platform))
        return ScraperClient.Match("19965", "Casper - Friend Around the World", "PlayStation", raw)
    }

    private val acronymPrefix = Regex("""^([A-Za-z]{2,6})\s*[-:]\s*(.+)$""")
    private val donaldDuckTitles = setOf(
        "donald duck", "donald duck quack attack", "donald duck goin quackers",
        "donald duck going quackers"
    )

    private fun titleWithoutAcronym(title: String): String? {
        val clean = cleanTitle(title)
        val match = acronymPrefix.matchEntire(clean) ?: return null
        val (prefix, expanded) = match.destructured
        val initials = expanded.split(punctuation).mapNotNull { it.firstOrNull() }.joinToString("")
        return expanded.takeIf { prefix.equals(initials, ignoreCase = true) }
    }

    private fun isDonaldDuck(key: String): Boolean {
        val title = key.removePrefix("disney s ").removePrefix("disneys ").removePrefix("disney ")
        return title in donaldDuckTitles
    }

    private val publisherPrefix = Regex("""(?i)^(?:disney(?:[’']s)?(?:/pixar(?:[’']s)?)?|marvel(?:[’']s)?|tom clancy[’']s)\s*[-:]?\s+""")

    fun alternateQueries(title: String): List<String> {
        val key = titleKey(title)
        if (key == "ctr") return listOf("Crash Team Racing")
        if (isDonaldDuck(key)) return listOf("Donald Duck Quack Attack", "Donald Duck Goin Quackers", "Donald Duck")
        val clean = cleanTitle(title)
        val unbranded = clean.replace(publisherPrefix, "")
        val article = trailingArticle.find(unbranded)
        val reordered = article?.let { it.groupValues[1] + " " + unbranded.removeRange(it.range) }
        return listOfNotNull(titleWithoutAcronym(clean), reordered, unbranded.takeIf { it != clean })
            .map { it.replace(punctuation, " ").trim() }
    }

    private fun alternateKey(title: String): String {
        val expanded = titleWithoutAcronym(title) ?: cleanTitle(title)
        val key = titleKey(expanded.replace(publisherPrefix, ""))
        return when {
            key == "ctr" -> "crash team racing"
            isDonaldDuck(key) -> "donald duck quack attack"
            else -> key
        }
    }

    fun rank(title: String, matches: List<ScraperClient.Match>, platform: String?, playlist: Boolean = false): List<ScraperClient.Match> {
        val wanted = titleKey(title)
        if (wanted.isEmpty()) return emptyList()
        val alternate = alternateKey(title)
        val ranked = matches.mapNotNull { match ->
            if (playlist && RomScanner.hasDiscMarker(match.title)) return@mapNotNull null
            val system = match.raw.optJSONObject("systeme")?.optString("id").orEmpty()
            if (platform != null && system.isNotEmpty() && system != platform) return@mapNotNull null
            val names = ScraperClient.objects(match.raw.opt("noms")).map { it.optString("text") } +
                listOf(match.title, match.raw.optString("nom"))
            val score = names.maxOf { name ->
                if (playlist && RomScanner.hasDiscMarker(name)) return@maxOf 0
                val candidate = titleKey(name)
                when {
                    candidate == wanted -> 3
                    candidate.replace(" ", "") == wanted.replace(" ", "") -> 2
                    alternateKey(name) == alternate -> 1
                    candidate.removePrefix("the ") == wanted.removePrefix("the ") -> 1
                    else -> 0
                }
            }
            if (score > 0) match to score else null
        }.sortedByDescending { it.second }.distinctBy { it.first.id }
        val best = ranked.firstOrNull()?.second ?: return emptyList()
        return ranked.filter { it.second == best }.map { it.first }.takeIf { it.size == 1 }.orEmpty()
    }
}
