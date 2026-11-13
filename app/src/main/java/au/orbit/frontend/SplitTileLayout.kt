package au.orbit.frontend

/** Only manually assigned slots are saved. Other library entries fill the unreserved slots. */
internal class SplitTileLayout(
    private val libraryKeys: List<String>,
    private val assigned: Map<Int, Tile>
) {
    sealed interface Tile {
        data class Library(val key: String) : Tile
        data class App(val component: String) : Tile
        data object Empty : Tile
    }

    private val indexAtSlot = HashMap<Int, Int>()
    private val slotAtIndex = IntArray(libraryKeys.size) { -1 }

    init {
        val indexByKey = libraryKeys.withIndex().associate { (index, key) -> key to index }
        for ((slot, tile) in assigned) {
            if (tile !is Tile.Library) continue
            val index = indexByKey[tile.key] ?: continue
            if (slotAtIndex[index] < 0) place(index, slot)
        }

        var slot = 0
        for (index in libraryKeys.indices) {
            if (slotAtIndex[index] >= 0) continue
            while (slot in assigned) slot++
            place(index, slot++)
        }
    }

    private fun place(index: Int, slot: Int) {
        indexAtSlot[slot] = index
        slotAtIndex[index] = slot
    }

    val slotCount = maxOf(indexAtSlot.keys.maxOrNull() ?: -1, assigned.keys.maxOrNull() ?: -1) + 1

    fun libraryIndex(slot: Int) = indexAtSlot[slot]
    fun librarySlot(index: Int) = slotAtIndex.getOrNull(index)

    fun tile(slot: Int): Tile {
        val index = libraryIndex(slot)
        return if (index != null) Tile.Library(libraryKeys[index]) else assigned[slot] as? Tile.App ?: Tile.Empty
    }

    fun assign(slot: Int, tile: Tile): Map<Int, Tile> = assigned + (slot to tile)

    fun move(from: Int, to: Int): Map<Int, Tile> = assigned + (from to tile(to)) + (to to tile(from))
}
