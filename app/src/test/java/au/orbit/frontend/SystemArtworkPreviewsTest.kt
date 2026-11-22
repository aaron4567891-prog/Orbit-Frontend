package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class SystemArtworkPreviewsTest {
    private val theme = OrbitThemes.Theme("test", "Test", "", 0, 0, 0, 0, 0, "artwork", 0.5f,
        systemHeroes = mapOf(
            "psx" to mapOf("top" to "artwork/psx-bg.png", "bottom" to "artwork/psx-bg.png"),
            "n64" to mapOf("top" to "artwork/n64-top.png", "bottom" to "artwork/n64-bottom.png"),
            "snes" to mapOf("top" to "artwork/unused.png")),
        systemImages = mapOf("psx" to mapOf("top" to "artwork/psx-photo.png", "bottom" to "artwork/psx-photo-bottom.png")),
        systemLogos = mapOf("psx" to "artwork/psx-logo.png"))

    @Test fun startupIncludesAllThreeArtworkTypesAndBothScreensWithoutDuplicateReads() {
        val paths = SystemArtworkPreviews.artworkPaths(theme, setOf("psx", "n64"))
        assertEquals(setOf("artwork/psx-bg.png", "artwork/n64-top.png", "artwork/n64-bottom.png",
            "artwork/psx-photo.png", "artwork/psx-photo-bottom.png", "artwork/psx-logo.png"), paths.toSet())
        assertEquals(paths.size, paths.distinct().size)
        assertFalse("artwork/unused.png" in paths)
    }

    @Test fun firstSystemPreparationIncludesItsTwoScreensWithoutLoadingAnotherSystem() {
        assertEquals(listOf("artwork/n64-top.png", "artwork/n64-bottom.png"),
            SystemArtworkPreviews.artworkPaths(theme, setOf("n64")))
        assertTrue(SystemArtworkPreviews.artworkPaths(theme, emptySet()).isEmpty())
    }

    @Test fun previewSamplingFitsTheMemoryBudgetForSmallAndLargeLibraries() {
        for (count in listOf(1, 30, 100, 500, 1000)) {
            val pixels = SystemArtworkPreviews.pixelLimit(count)
            for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 100001 to 99999, 641 to 401, 320 to 180)) {
                val sample = SystemArtworkPreviews.sampleSize(width, height, pixels)
                val decodedWidth = (width.toLong() + sample - 1) / sample
                val decodedHeight = (height.toLong() + sample - 1) / sample
                assertTrue(decodedWidth <= 640 && decodedHeight <= 640)
                assertTrue(decodedWidth * decodedHeight * 4 * count <= SystemArtworkPreviews.MEMORY_BYTES)
            }
        }
    }

    @Test fun bundledPreviewCacheKeysChangeWithEachInstallButUserArtworkKeysDoNot() {
        val hero = SystemDefaultBackgrounds.key("psx")
        val logo = SystemWordmarkVariants.key("psx")
        val user = "artwork/3f1c2d4e-user.png"
        for (key in listOf(hero, logo, "console/psx"))
            assertTrue(SystemArtworkPreviews.diskKey(key, 1L) != SystemArtworkPreviews.diskKey(key, 2L))
        assertEquals(user, SystemArtworkPreviews.diskKey(user, 1L))
        assertEquals(user, SystemArtworkPreviews.diskKey(user, 2L))
    }

    @Test fun onlyIconicPackHeroesForTheThemeAreReleased() {
        val sources = mapOf(
            "midnight/gb" to "Iconic · Default · Siddy212 · https://github.com/Siddy212/iconic-es-de",
            "midnight/psx" to "Other pack · Someone · https://example.com",
            "mono/snes" to "Iconic · Default · Siddy212 · https://github.com/Siddy212/iconic-es-de")
        assertEquals(setOf("gb"), OrbitThemes.iconicPackSystems("midnight", sources))
        assertEquals(setOf("snes"), OrbitThemes.iconicPackSystems("mono", sources))
    }
}
