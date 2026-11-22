package au.orbit.frontend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SystemArtworkMediaTest {
    private val photo = SystemArtworkMedia.Kind.PHOTO
    private val logo = SystemArtworkMedia.Kind.LOGO
    private val background = SystemArtworkMedia.Kind.BACKGROUND

    @Test fun documentedNestedMediaSelectsAllThreeDistinctRoles() {
        val system = JSONObject("""{"medias":{
            "media_photos":{"media_photo_us":"https://example.com/console.png","media_photo_us_md5":"abc"},
            "media_wheels":{"media_wheel_us":"https://example.com/logo.png"},
            "media_fanart":"https://example.com/background.jpg",
            "media_video":"https://example.com/video.mp4"}}""")
        assertEquals("https://example.com/console.png", SystemArtworkMedia.select(system, photo, "us"))
        assertEquals("https://example.com/logo.png", SystemArtworkMedia.select(system, logo, "us"))
        assertEquals("https://example.com/background.jpg", SystemArtworkMedia.select(system, background, "us"))
    }

    @Test fun preferredRegionWinsWithWorldAsFallback() {
        val system = JSONObject("""{"medias":{"media_photos":{
            "media_photo_us":"https://example.com/us.png", "media_photo_wor":"https://example.com/world.png",
            "media_photo_jp":"https://example.com/japan.png"}}}""")
        assertEquals("https://example.com/japan.png", SystemArtworkMedia.select(system, photo, "jp"))
        assertEquals("https://example.com/world.png", SystemArtworkMedia.select(system, photo, "fr"))
    }

    @Test fun arraysAndMonochromeLogoFallbackAreSupported() {
        val system = JSONObject("""{"medias":[
            {"type":"logomonochrome","region":"wor","url":"https://example.com/mono.png"},
            {"type":"fanart","url":"https://example.com/hero.jpg"}]}""")
        assertEquals("https://example.com/mono.png", SystemArtworkMedia.select(system, logo, "us"))
        assertEquals("https://example.com/hero.jpg", SystemArtworkMedia.select(system, background, "us"))
        assertNull(SystemArtworkMedia.select(system, photo, "us"))
    }

    @Test fun colourLogoWinsOverMonochromeAndStyledWheelsAreExcluded() {
        val system = JSONObject("""{"medias":{
            "media_logomonochrome_us":"https://example.com/mono.png",
            "media_wheelcarbon_us":"https://example.com/carbon.png",
            "media_wheel_wor":"https://example.com/colour.png"}}""")
        assertEquals("https://example.com/colour.png", SystemArtworkMedia.select(system, logo, "us"))
    }

    @Test fun missingMediaAndInsecureUrlsDoNotProduceFakeMatches() {
        assertNull(SystemArtworkMedia.select(JSONObject(), photo, "us"))
        val system = JSONObject("""{"medias":{
            "media_photo_us":"http://example.com/photo.png",
            "media_photo_us_md5":"https://example.com/not-an-image",
            "media_screenshot":"https://example.com/game.png"}}""")
        assertNull(SystemArtworkMedia.select(system, photo, "us"))
        assertNull(SystemArtworkMedia.select(system, background, "us"))
    }

    @Test fun liveSystemIllustrationsAreHeroCandidatesWithoutAnyFanartEntry() {
        val system = JSONObject("""{"id":"57","medias":[
            {"type":"photo","region":"wor","format":"png","url":"https://example.com/console.png"},
            {"type":"illustration","region":"wor","format":"png","url":"https://example.com/world.png"},
            {"type":"illustration","region":"us","format":"png","url":"https://example.com/usa.png"}]}""")
        assertEquals("https://example.com/usa.png", SystemArtworkMedia.select(system, background, "us"))
        assertEquals("https://example.com/world.png", SystemArtworkMedia.select(system, background, "fr"))
    }

    @Test fun backgroundsTakePriorityWithIllustrationsAsFallback() {
        val system = JSONObject("""{"medias":[
            {"type":"illustration","region":"wor","url":"https://example.com/illustration.png"},
            {"type":"background","region":"wor","url":"https://example.com/background.png"},
            {"type":"fanart","url":"https://example.com/fanart.png"}]}""")
        assertEquals("https://example.com/background.png", SystemArtworkMedia.select(system, background, "us"))
        val nested = JSONObject("""{"medias":{"media_illustration_us":"https://example.com/nested.png"}}""")
        assertEquals("https://example.com/nested.png", SystemArtworkMedia.select(nested, background, "us"))
    }

    @Test fun liveHyphenatedMonochromeLogoTypeIsRecognised() {
        val system = JSONObject("""{"medias":[
            {"type":"logo-monochrome","region":"wor","url":"https://example.com/logo.png"}]}""")
        assertEquals("https://example.com/logo.png", SystemArtworkMedia.select(system, logo, "us"))
    }
}
