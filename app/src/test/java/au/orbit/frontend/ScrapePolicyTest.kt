package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrapePolicyTest {
    @Test fun manualAmountStaysWithinAccountAllowanceAndMaximumIsDefault() {
        assertEquals(6,ScrapePolicy.parallelism(6,0))
        assertEquals(12,ScrapePolicy.parallelism(12,0))
        assertEquals(3,ScrapePolicy.parallelism(6,3))
        assertEquals(2,ScrapePolicy.parallelism(2,6))
    }
    @Test fun noSystemTicksMeansAllAndAnySubsetIsAllowed() {
        val catalogue=(1..20).map { SystemSpec("system$it","System $it","S$it","",setOf("rom")) }
        assertEquals(20,ScrapePolicy.systemsForSelection(catalogue,emptySet()).size)
        assertEquals(20,ScrapePolicy.systemsForSelection(catalogue,catalogue.map { it.id }.toSet()).size)
        assertEquals(15,ScrapePolicy.systemsForSelection(catalogue,catalogue.take(15).map { it.id }.toSet()).size)
        assertEquals("system4",ScrapePolicy.systemsForSelection(catalogue,setOf("system4")).single().id)
    }

    @Test fun concurrencyMatchesAccountWithoutThreeGameCap() {
        assertEquals(1,ScrapePolicy.parallelism(1))
        assertEquals(2,ScrapePolicy.parallelism(2))
        assertEquals(6,ScrapePolicy.parallelism(6))
        assertEquals(12,ScrapePolicy.parallelism(12))
        assertEquals(1,ScrapePolicy.parallelism(0))
    }

    @Test fun scrapePlanChecksAlternateFormatsAndPreservesRescrapeModes() {
        val enabled = setOf("covers", "marquees", "videos")
        val existing = setOf("box-2D", "wheel-hd", "ss")

        val missing = ScrapePolicy.mediaPlan("missing", enabled, existing)
        assertEquals(setOf("videos"), missing.folders)
        assertEquals(existing, missing.existingTypes)
        assertEquals(false, missing.skip)
        assertEquals(true, ScrapePolicy.mediaPlan("missing", enabled, existing + "video-normalized").skip)
        assertEquals(true, ScrapePolicy.mediaPlan("new", enabled, existing).skip)
        assertEquals(enabled, ScrapePolicy.mediaPlan("all", enabled, existing).folders)
    }

    @Test fun missingMediaSkipsUnavailableCategoriesButStillRetriesAvailableFiles() {
        val enabled = setOf("fanart", "covers", "videos")
        val previous = org.json.JSONObject()
        previous.put("unavailableMedia", ScrapePolicy.updateAvailability(null, enabled, setOf("covers", "videos"), 1000L))

        val unavailable = ScrapePolicy.unavailableMedia(previous, 2000L).keys
        assertEquals(setOf("fanart"), unavailable)
        assertEquals(true, ScrapePolicy.mediaPlan("missing", enabled, setOf("box-2D", "video"), unavailable).skip)
        assertEquals(setOf("covers"), ScrapePolicy.mediaPlan("missing", enabled, setOf("video"), unavailable).folders)
        assertEquals(enabled, ScrapePolicy.mediaPlan("all", enabled, setOf("box-2D", "video"), unavailable).folders)
        assertEquals(emptySet<String>(), ScrapePolicy.unavailableMedia(previous, 8 * 86400000L).keys)
        assertEquals(0, ScrapePolicy.updateAvailability(previous, setOf("fanart"), setOf("fanart"), 2000L).length())
    }

    @Test fun restoredBackgroundMediaAlsoClearsLegacyHeroRemoval() {
        assertEquals(setOf("fanart", "covers", GameCustomArtwork.HERO),
            GameCustomArtwork.restoredRoles(setOf("fanart", "covers"), "fanart"))
        assertEquals(setOf("covers"), GameCustomArtwork.restoredRoles(setOf("covers"), "videos"))
    }

    @Test fun screenScraperMediaMustBelongToTheRequestedConsoleAndGame() {
        val url = "https://api.screenscraper.fr/api2/mediaJeu.php?systemeid=57&jeuid=19329&media=box-2D"
        assertEquals(true, ScrapedMediaPlatform.matchesMediaUrl(url, "57", "19329"))
        assertEquals(false, ScrapedMediaPlatform.matchesMediaUrl(url.replace("systemeid=57", "systemeid=10"), "57", "19329"))
        assertEquals(false, ScrapedMediaPlatform.matchesMediaUrl(url.replace("jeuid=19329", "jeuid=37031"), "57", "19329"))
    }

    @Test fun missingMediaCanRejectLegacyScreenScraperArtFromAnotherConsole() {
        val n64 = org.json.JSONObject("""{"provider":"ScreenScraper","metadata":{"systeme":{"id":14}}}""")
        val ps1 = org.json.JSONObject("""{"provider":"ScreenScraper","metadata":{"systeme":{"id":"57"}}}""")
        val imported = org.json.JSONObject("""{"provider":"ES-DE","metadata":{}}""")
        assertEquals(false, ScrapedMediaPlatform.matches(n64, "psx"))
        assertEquals(true, ScrapedMediaPlatform.matches(n64, "n64"))
        assertEquals(true, ScrapedMediaPlatform.matches(ps1, "psx"))
        assertEquals(true, ScrapedMediaPlatform.matches(imported, "psx"))
        assertEquals(true, ScrapedMediaPlatform.matches(n64, "unknown"))
    }
}
