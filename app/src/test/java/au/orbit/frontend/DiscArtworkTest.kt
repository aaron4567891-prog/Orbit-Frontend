package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscArtworkTest {
    @Test
    fun recognisesDiscAndFindsItsCentre() {
        val center = requireNotNull(DiscArtwork.center(240, 200) { x, y ->
            val dx = x - 110
            val dy = y - 90
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared in 49..5625) 255 else 0
        })

        assertEquals(110f / 240, center.x, 0.03f)
        assertEquals(90f / 200, center.y, 0.03f)
    }

    @Test
    fun rejectsSquareAndRectangularArtwork() {
        assertNull(DiscArtwork.center(200, 200) { _, _ -> 255 })
        assertNull(DiscArtwork.center(200, 200) { x, y ->
            if (x in 25..175 && y in 25..175) 255 else 0
        })
        assertNull(DiscArtwork.center(240, 200) { x, y ->
            if (x in 25..215 && y in 25..175) 255 else 0
        })
    }
}
