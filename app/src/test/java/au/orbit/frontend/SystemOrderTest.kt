package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemOrderTest {
    @Test fun savedOrderWinsAndNewEntriesFollowTheirDefaultNeighbour() {
        val defaults = listOf("psx", "snes", "gba", "nds")
        assertEquals(listOf("gba", "psx", "snes", "nds"),
            SystemOrder.apply(defaults, { it }, saved = listOf("gba", "psx", "snes", "nds")))
        // "tile" is new: it was inserted after snes in the default order, so it stays after snes.
        assertEquals(listOf("gba", "psx", "snes", "tile", "nds"),
            SystemOrder.apply(listOf("psx", "snes", "tile", "gba", "nds"), { it }, saved = listOf("gba", "psx", "snes", "nds")))
        assertEquals(defaults, SystemOrder.apply(defaults, { it }, saved = emptyList()))
    }
}
