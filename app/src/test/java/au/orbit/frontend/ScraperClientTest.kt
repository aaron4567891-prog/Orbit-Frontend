package au.orbit.frontend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScraperClientTest {
    @Test fun completeSearchResultsAvoidRedundantGameDetailFetch() {
        val complete=ScraperClient.Match("1","Game","System",JSONObject("""{"medias":[{"url":"https://example.com/cover.png"}]}"""))
        assertTrue(ScraperClient.hasEmbeddedMedia(complete))
        assertFalse(ScraperClient.hasEmbeddedMedia(complete.copy(raw=JSONObject())))
        assertFalse(ScraperClient.hasEmbeddedMedia(complete.copy(raw=JSONObject("""{"medias":[{}]}"""))))
    }

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
        assertEquals("Crash Bandicoot Warped",queries.first())
        assertTrue(queries.contains("Crash Bandicoot - Warped (Europe)"))
        assertTrue(queries.contains("Crash Bandicoot Warped"))
        assertTrue(queries.contains("Warped"))
        assertFalse(ScraperClient.searchQueries("007").isEmpty())
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
        assertEquals(7,selected.size)
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
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
    }
}
