package au.orbit.frontend

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkDisplayFitTest {
    private val landscape = ArtworkDimensions(1920, 1080)
    private val nearSquare = ArtworkDimensions(1240, 1080)

    @Test fun eachDisplayPrefersItsOwnAspectRatio() {
        assertTrue(landscape.fitFor(listOf(landscape)) > nearSquare.fitFor(listOf(landscape)))
        assertTrue(nearSquare.fitFor(listOf(nearSquare)) > landscape.fitFor(listOf(nearSquare)))
        val portrait = ArtworkDimensions(1080, 2400)
        val tallImage = ArtworkDimensions(1440, 3200)
        assertTrue(tallImage.fitFor(listOf(portrait)) > landscape.fitFor(listOf(portrait)))
    }

    @Test fun sharedArtworkBalancesCroppingAcrossDisplays() {
        val screens = listOf(landscape, nearSquare)
        val compromise = ArtworkDimensions(2520, 1800)
        assertTrue(compromise.fitFor(screens) > landscape.fitFor(screens))
        assertTrue(compromise.fitFor(screens) > nearSquare.fitFor(screens))
    }

    @Test fun adequateResolutionWinsBetweenEqualShapes() {
        val small = ArtworkDimensions(640, 360)
        assertTrue(landscape.fitFor(listOf(landscape)) > small.fitFor(listOf(landscape)))
        assertNull(ArtworkDimensions.from(0, 1080))
    }
}
