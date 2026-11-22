package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class SystemLogoWordmarkTest {
    private class Image(val width: Int = 160, val height: Int = 140) {
        val pixels = IntArray(width * height)
        fun fill(left: Int, top: Int, right: Int, bottom: Int) {
            for (y in top until bottom) for (x in left until right) pixels[y * width + x] = -1
        }
        fun bounds() = SystemLogoWordmark.bounds(pixels, width, height)
    }

    @Test fun removesAnEmblemAboveTheName() {
        val image = Image().apply { fill(45, 5, 115, 80); fill(5, 100, 155, 125) }
        assertEquals(SystemLogoWordmark.Bounds(5, 100, 155, 125), image.bounds())
    }

    @Test fun removesAnEmblemBelowTheName() {
        val image = Image().apply { fill(5, 5, 155, 30); fill(45, 50, 115, 125) }
        assertEquals(SystemLogoWordmark.Bounds(5, 5, 155, 30), image.bounds())
    }

    @Test fun keepsSideEmblems() {
        val image = Image(300, 100).apply { fill(5, 10, 85, 90); fill(105, 30, 295, 70) }
        assertEquals(SystemLogoWordmark.Bounds(5, 10, 295, 90), image.bounds())
    }

    @Test fun keepsMultilineSystemNamesInsteadOfRemovingWords() {
        val image = Image().apply { fill(15, 10, 145, 35); fill(5, 60, 155, 85) }
        assertEquals(SystemLogoWordmark.Bounds(5, 10, 155, 85), image.bounds())
    }

    @Test fun retainsBothNameLinesBelowTheBadge() {
        val image = Image(240, 180).apply {
            fill(80, 5, 160, 85)
            fill(20, 105, 220, 120)
            fill(5, 130, 235, 160)
        }
        assertEquals(SystemLogoWordmark.Bounds(5, 105, 235, 160), image.bounds())
    }

    @Test fun keepsArtworkWhenTheEmblemAndLettersCannotBeSeparated() {
        val image = Image().apply { fill(45, 5, 115, 125); fill(5, 100, 155, 125) }
        assertEquals(SystemLogoWordmark.Bounds(5, 5, 155, 125), image.bounds())
    }

    @Test fun handlesEmptyAndAlreadyCroppedWordmarks() {
        assertNull(Image().bounds())
        val image = Image(150, 25).apply { fill(0, 0, 150, 25) }
        assertEquals(SystemLogoWordmark.Bounds(0, 0, 150, 25), image.bounds())
    }
}
