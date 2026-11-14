package au.orbit.frontend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScraperClientTest {
    @Test fun logosPreferUsaByDefaultThenWorld() {
        val media=listOf(
            ScraperClient.Media("wheel-hd","https://example.com/jp","png","jp"),
            ScraperClient.Media("wheel-hd","https://example.com/fr","png","eu","fr"),
            ScraperClient.Media("wheel","https://example.com/us","png","us"),
            ScraperClient.Media("wheel-hd","https://example.com/world","png","wor")
        )
        assertEquals("https://example.com/us",ScraperMediaTypes.select(media,setOf("marquees")).single().second.url)
        assertEquals("https://example.com/jp",ScraperMediaTypes.select(media,setOf("marquees"),"jp","en").single().second.url)
        assertEquals("https://example.com/fr",ScraperMediaTypes.select(media,setOf("marquees"),"eu","fr").single().second.url)
        assertEquals("https://example.com/us",ScraperMediaTypes.select(media.take(3),setOf("marquees")).single().second.url)
        assertEquals("https://example.com/jp",ScraperMediaTypes.select(media.take(1),setOf("marquees")).single().second.url)
    }

    @Test fun titleFallbackKeepsTheSubtitleWhenDatabaseIncludesASequelNumber() {
        val queries=ScraperClient.searchQueries("Crash Bandicoot - Warped (Europe)")
        assertEquals("Crash Bandicoot - Warped",queries.first())
        assertTrue(queries.none { it.contains("Europe") })
        assertTrue(queries.contains("Crash Bandicoot Warped"))
        assertTrue(queries.contains("Warped"))
        assertFalse(ScraperClient.searchQueries("007").isEmpty())
    }

    @Test fun screenScraperSearchesAndRanksWithoutRegionOrLanguageTags() {
        val title = "formula one 99 (usa, canada) (en, fr, es)"
        val match = ScraperClient.Match("19768", "Formula One 99", "PlayStation", JSONObject())
        assertEquals(ScraperClient.searchQueries("formula one 99"), ScraperClient.searchQueries(title))
        assertEquals(listOf(match), ScreenScraperMatch.rank(title, listOf(match), "57"))
        assertEquals(listOf(match), ScreenScraperMatch.rank("FORMULA ONE 99 [Europe, Japan]", listOf(match), "57"))
    }

    @Test fun screenScraperSearchesHyphenatedNamesAndMatchesCaseInsensitively() {
        val match = ScraperClient.Match("19605", "G-Police", "PlayStation", JSONObject())
        assertEquals(listOf("g-police", "g police"), ScraperClient.searchQueries("g-police (USA)"))
        assertEquals(listOf("GPolice", "G-Police"), ScraperClient.searchQueries("GPolice (USA)"))
        assertEquals(listOf("g police", "g-police"), ScraperClient.searchQueries("g police (USA)"))
        assertTrue(ScraperClient.searchQueries("X Men Mutant Academy (USA, Canada)").contains("X-Men Mutant Academy"))
        assertEquals(listOf("XMEN", "X-MEN"), ScraperClient.searchQueries("XMEN (Europe)"))
        for (title in listOf("g-police (usa)", "G-POLICE (USA)", "G Police (USA)", "GPolice (USA)")) {
            assertEquals(listOf(match), ScreenScraperMatch.rank(title, listOf(match), "57", playlist = true))
        }
    }

    @Test fun screenScraperEmptyObjectIsNotALaunchableGameMatch() {
        assertTrue(ScraperClient.screenScraperMatches(JSONObject("""{"jeux":[{}]}""")).isEmpty())
        val response=JSONObject("""{"jeux":[{}, {"id":"39873","noms":[{"region":"wor","text":"1941: Counter Attack"}],"systeme":{"id":"6","text":"Capcom Play System"}}]}""")
        val matches=ScraperClient.screenScraperMatches(response)
        assertEquals(1,matches.size)
        assertEquals("39873",matches.single().id)
        assertEquals("Capcom Play System",matches.single().platform)
    }
    @Test fun scrapingOnlyKeepsRequestedCategoriesAndPrefersFrontendMediaVariants() {
        val types=listOf("wheel","wheel-hd","video","video-normalized","support-2D","ss","fanart","box-2D","manuel","bezel-16-9","mixrbv1","icons","box-3D")
        val media=types.map { ScraperClient.Media(it,"https://example.com/$it","png") }
        val selected=ScraperMediaTypes.select(media,ScraperMediaTypes.categories.map { it.folder }.toSet())
        assertEquals(8,selected.size)
        assertEquals("box-2D", selected.first { it.first.folder == "covers" }.second.type)
        assertEquals("box-3D", selected.first { it.first.folder == "3dboxes" }.second.type)
        assertEquals("wheel-hd",selected.first { it.first.folder=="marquees" }.second.type)
        assertEquals("video-normalized",selected.first { it.first.folder=="videos" }.second.type)
        assertEquals(listOf("manuel"),ScraperMediaTypes.select(media,setOf("manuals")).map { it.second.type })
        assertTrue(ScraperMediaTypes.select(media,emptySet()).isEmpty())
    }
    @Test fun providerCollectionsAcceptArrayAndKeyedObjectResponses() {
        val array=JSONArray("""[{"nomcourt":"box-2D"},{"nomcourt":"new-artwork-type"}]""")
        val keyed=JSONObject("""{"1":{"nomcourt":"box-2D"},"2":{"nomcourt":"new-artwork-type"}}""")
        assertEquals(listOf("box-2D","new-artwork-type"),ScraperClient.objects(array).map { it.getString("nomcourt") })
        assertEquals(ScraperClient.objects(array).map { it.toString() }.toSet(),ScraperClient.objects(keyed).map { it.toString() }.toSet())
        assertTrue(ScraperClient.objects(null).isEmpty())
    }
    @Test fun mediaDirectoriesDistinguishSameNamedRomsAtDifferentUris() {
        val first=ScraperClient.gameKey("content://roms/folder-one/game.zip")
        assertEquals(first,ScraperClient.gameKey("content://roms/folder-one/game.zip"))
        assertNotEquals(first,ScraperClient.gameKey("content://roms/folder-two/game.zip"))
        assertEquals("9555b170b782956a20d847e741b5f9b3f7573607b5eecd06c5040bb86823114e", first)
    }

    @Test fun screenScraperRejectsWrongConsoleAndDoesNotSubstituteASequel() {
        val response = JSONObject(
            """{"jeux":[
                {"id":"10","noms":[{"text":"Crash Bandicoot: Warped"}],"systeme":{"id":"58"}},
                {"id":"11","noms":[{"text":"Crash Bandicoot 2"}],"systeme":{"id":"57"}},
                {"id":"12","noms":[{"text":"Crash Bandicoot 3: Warped"},{"text":"Crash Bandicoot: Warped"}],"systeme":{"id":"57"}}
            ]}"""
        )
        val matches = ScraperClient.screenScraperMatches(response)
        assertEquals("57", ScreenScraperMatch.platform(SystemSpec("psx", "PlayStation", "PS", "", emptySet())))
        assertEquals("12", ScreenScraperMatch.rank("Crash Bandicoot - Warped (Europe)", matches, "57").single().id)
        assertTrue(ScreenScraperMatch.rank("Crash Bandicoot", matches, "57").isEmpty())
    }

    @Test fun screenScraperNormalizesRomanNumeralsAndTrailingArticles() {
        val match = ScraperClient.Match("1", "The Legend of Heroes II", "", JSONObject())
        assertEquals(listOf(match), ScreenScraperMatch.rank("Legend of Heroes 2, The (USA)", listOf(match), null))
    }

    @Test fun playlistScrapeChoosesWholeGameAndNeverUsesDiscOnlyResult() {
        val results = ScraperClient.screenScraperMatches(JSONObject(
            """{"jeux":[
                {"id":"1","noms":[{"text":"Final Fantasy VII (Disc 2)"}],"systeme":{"id":"57"}},
                {"id":"2","noms":[{"text":"Final Fantasy VII"}],"systeme":{"id":"57"}},
                {"id":"3","noms":[{"text":"Final Fantasy VII - Disk II"}],"systeme":{"id":"57"}}
            ]}"""
        ))
        assertEquals(listOf("2"), ScreenScraperMatch.rank("Final Fantasy VII (USA)", results, "57", playlist = true).map { it.id })
        assertTrue(ScreenScraperMatch.rank("Final Fantasy VII", results.filterNot { it.id == "2" }, "57", playlist = true).isEmpty())
    }

    @Test fun screenScraperUsesSixWhenTheServiceReportsSix() {
        val numeric = JSONObject("""{"ssuser":{"maxthreads":6}}""")
        val text = JSONObject("""{"ssuser":{"maxthreads":" 6 "}}""")
        assertEquals(6, ScraperClient.screenScraperAllowance(numeric))
        assertEquals(6, ScraperClient.screenScraperAllowance(text))
        assertNull(ScraperClient.screenScraperAllowance(JSONObject()))
        assertEquals(6, ScrapePolicy.parallelism(6, ScrapePolicy.MAX_MANUAL_GAMES))
        assertEquals(3, ScrapePolicy.parallelism(3, ScrapePolicy.MAX_MANUAL_GAMES))
    }

    @Test fun screenScraperFindsCrashTeamRacingFromCtrRomTitle() {
        val match = ScraperClient.Match("19266", "Crash Team Racing", "PlayStation", JSONObject())
        val romTitle = "CTR - Crash Team Racing (USA)"
        assertEquals("Crash Team Racing", ScraperClient.searchQueries(romTitle).first())
        assertEquals(listOf(match), ScreenScraperMatch.rank(romTitle, listOf(match), "57"))
        assertEquals(listOf(match), ScreenScraperMatch.rank("CTR", listOf(match), "57"))
        assertTrue(ScreenScraperMatch.rank("Crash Team Racing - Nitro-Fueled", listOf(match), "57").isEmpty())
    }

    @Test fun screenScraperMatchesDonaldDuckRegionalTitlesWithoutPickingAnotherGame() {
        val quackAttack = ScraperClient.Match("13601", "Disney's Donald Duck - Quack Attack", "Dreamcast", JSONObject())
        val otherGame = ScraperClient.Match("2", "Disney's Donald Duck: Maui Mallard", "Dreamcast", JSONObject())
        val candidates = listOf(otherGame, quackAttack)
        assertEquals("Donald Duck Quack Attack", ScraperClient.searchQueries("Disney Donald Duck").first())
        assertEquals(listOf(quackAttack), ScreenScraperMatch.rank("Disney Donald Duck", candidates, "23"))
        assertEquals(listOf(quackAttack),
            ScreenScraperMatch.rank("Disney - Donald Duck - Goin' Quackers (US)", candidates, "23"))
    }

    @Test fun screenScraperHandlesPublisherPunctuationAndRomTags() {
        val match = ScraperClient.Match("10", "Chip and Dale Rescue Rangers II", "NES", JSONObject())
        assertEquals(listOf(match), ScreenScraperMatch.rank(
            "Disney's Chip & Dale - Rescue Rangers 2 (USA) (Rev 1)", listOf(match), "3"))
    }

    @Test fun screenScraperKeepsMeaningfulSubtitlesAndRejectsTiedMatches() {
        val original = ScraperClient.Match("10", "Game", "NES", JSONObject())
        val sequel = ScraperClient.Match("11", "Game (New Adventure)", "NES", JSONObject())
        assertEquals(listOf(sequel), ScreenScraperMatch.rank("Game (New Adventure) (Europe)", listOf(original, sequel), "3"))
        assertTrue(ScreenScraperMatch.rank("Game", listOf(original, original.copy(id = "12")), "3").isEmpty())
    }

    @Test fun casperLookupUsesTheSamePlayStationEntryForSpellingVariants() {
        for (title in listOf("Casper - Friends Around the World (USA)", "Casper - Freinds Around the World",
            "Casper: Friend Around the World")) {
            assertEquals("19965", ScreenScraperMatch.knownGame(title, "57")?.id)
            assertNull(ScreenScraperMatch.knownGame(title, "14"))
        }
        assertNull(ScreenScraperMatch.knownGame("Casper", "57"))
        assertNull(ScreenScraperMatch.knownGame("Casper: Spirit Dimensions", "57"))
    }

    @Test fun screenScraperReusesOnlyTheSavedMatchForTheSameRomAndConsole() {
        val uri = "content://roms/casper.chd"
        val record = JSONObject().put("provider", "ScreenScraper").put("providerId", "19965")
            .put("romUri", uri).put("title", "Casper - Friend Around the World")
            .put("metadata", JSONObject("""{"systeme":{"id":57}}"""))
        assertEquals("19965", ScreenScraperMatch.savedGameId(record, "57", uri, "Casper - Freinds Around the World", false))
        assertNull(ScreenScraperMatch.savedGameId(record, "14", uri, "Casper", false))
        assertNull(ScreenScraperMatch.savedGameId(record, "57", "content://roms/other.chd", "Casper", false))
        assertNull(ScreenScraperMatch.savedGameId(record, "57", uri, "Casper: Spirit Dimensions", false))
        record.put("screenScraperMatchVerified", true)
        assertEquals("19965", ScreenScraperMatch.savedGameId(record, "57", uri, "A manually matched title", false))
        record.put("title", "Casper (Disc 2)")
        assertNull(ScreenScraperMatch.savedGameId(record, "57", uri, "Casper", true))
    }

    @Test fun screenScraperReordersArticlesBeforeSubtitlesAndKeepsSequelNumbers() {
        val lostWorld = ScraperClient.Match("1", "The Lost World: Jurassic Park", "PlayStation", JSONObject())
        assertEquals("the lost world Jurassic Park", ScraperClient.searchQueries("lost world, the - Jurassic Park (USA)").first())
        assertEquals(listOf(lostWorld),
            ScreenScraperMatch.rank("Lost World, The - Jurassic Park (USA)", listOf(lostWorld), "57"))
        val kof = ScraperClient.Match("19935", "The King of Fighters '95", "PlayStation", JSONObject())
        assertEquals(listOf(kof), ScreenScraperMatch.rank("King of Fighters '95, The (USA)", listOf(kof), "57"))
        assertTrue(ScreenScraperMatch.rank("King of Fighters '99, The (USA)", listOf(kof), "57").isEmpty())
    }
}
