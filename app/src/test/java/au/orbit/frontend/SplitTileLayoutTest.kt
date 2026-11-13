package au.orbit.frontend

import au.orbit.frontend.SplitTileLayout.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitTileLayoutTest {
    @Test fun movingToAnEmptySlotKeepsOtherTilesInPlace() {
        val entries = listOf("a", "b", "c")
        val original = SplitTileLayout(entries, emptyMap())
        val moved = SplitTileLayout(entries, original.move(1, 7))

        assertEquals(Tile.Library("a"), moved.tile(0))
        assertEquals(Tile.Empty, moved.tile(1))
        assertEquals(Tile.Library("c"), moved.tile(2))
        assertEquals(1, moved.libraryIndex(7))
        assertEquals(7, moved.librarySlot(1))
        assertEquals(8, moved.slotCount)
    }

    @Test fun swappingWithAnAppKeepsBothPositionsAfterARescan() {
        val entries = listOf("a", "b", "c")
        val app = Tile.App("com.example/.MainActivity")
        val original = SplitTileLayout(entries, mapOf(4 to app))
        val moved = SplitTileLayout(listOf("new", "a", "b", "c"), original.move(0, 4))

        assertEquals(app, moved.tile(0))
        assertNull(moved.libraryIndex(0))
        assertEquals(1, moved.libraryIndex(4))
        assertEquals(4, moved.librarySlot(1))
    }

    @Test fun missingPinnedGameKeepsItsSlotUntilItReturns() {
        val pinned = mapOf(3 to Tile.Library("a"))
        val missing = SplitTileLayout(listOf("b"), pinned)
        val restored = SplitTileLayout(listOf("a", "b"), pinned)

        assertEquals(Tile.Empty, missing.tile(3))
        assertEquals(Tile.Library("a"), restored.tile(3))
        assertEquals(Tile.Library("b"), restored.tile(0))
    }
}
