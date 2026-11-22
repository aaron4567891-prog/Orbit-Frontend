package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class HeroCropTest {
    private fun visible(start: Float, visible: Float) = start to start + visible

    @Test fun narrowScreensKeepTheBundledNameAndConsoleArtInView() {
        // Thor's bottom screen (1240x1080) shows about 65% of a 16:9 image's width.
        val window = 1240f / 1080f / (16f / 9f)
        val (left, right) = visible(OrbitThemeArtwork.cropStart(0.54f, 0.955f, window), window)
        assertTrue(left <= 0.54f && right >= 0.955f && right <= 1.0001f)
    }

    @Test fun focusWiderThanTheWindowCentresOnIt() {
        val start = OrbitThemeArtwork.cropStart(0.2f, 0.9f, 0.4f)
        assertEquals(0.35f, start, 0.0001f)
    }

    @Test fun fullWindowNeedsNoCrop() {
        assertEquals(0f, OrbitThemeArtwork.cropStart(0.54f, 0.955f, 1f), 0f)
    }

    @Test fun everySteamGridDbHeroHasAFocusSpanThatFitsTheImage() {
        val folder = listOf(java.io.File("src/main/assets/system-backgrounds"), java.io.File("app/src/main/assets/system-backgrounds")).first { it.isDirectory }
        val manifest = org.json.JSONObject(java.io.File(folder, "manifest.json").readText())
        val focus = org.json.JSONObject(java.io.File(folder, "focus.json").readText())
        val steamGridDb = manifest.keys().asSequence().filter { manifest.getJSONObject(it).getString("provider") == "SteamGridDB" }.toSet()
        assertEquals(steamGridDb, focus.keys().asSequence().toSet())
        for (id in steamGridDb) {
            val array = focus.getJSONArray(id)
            assertTrue(id, SystemDefaultBackgrounds.validFocus(FloatArray(array.length()) { array.getDouble(it).toFloat() }))
        }
    }

    @Test fun cropLeansTowardsArtOnTheRightWhileKeepingTheLogo() {
        // PlayStation: logo at 17–36% of the width, game cases to the right edge; top screen shows 57%.
        val start = OrbitThemeArtwork.cropWindow(0.17f, 0.36f, 0.17f, 1f, 0.57f, 0f)
        assertEquals(0.17f, start, 0.0001f)
    }

    @Test fun logoStaysClearOfTheSystemsList() {
        val visible = 0.57f; val inset = 0.27f
        val start = OrbitThemeArtwork.cropWindow(0.17f, 0.36f, 0.17f, 1f, visible, inset)
        assertTrue(start + visible * inset <= 0.17f + 0.0001f && start + visible >= 0.36f)
    }
}
