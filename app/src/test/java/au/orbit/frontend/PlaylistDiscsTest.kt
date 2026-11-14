package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class PlaylistDiscsTest {
    private fun game(name: String, folder: String = "psx") = RomRecord(name.substringBeforeLast('.'), "content://roms/$folder/$name", name)
    @Test fun matchingDiscsAreHiddenAndPlaylistKeepsLaunchIdentity() {
        val playlist = game("Final Fantasy VII (USA).m3u")
        val records = listOf(playlist, game("Final Fantasy VII (USA) (Disc 1).chd"), game("Final Fantasy VII (USA) (Disc 2).cue"), game("Final Fantasy VII (USA).bin"))
        assertEquals(listOf(playlist), RomScanner.hidePlaylistDiscs(records))
    }
    @Test fun preservesOtherRegionsGamesAndCollections() {
        val playlist = game("Game (USA).m3u")
        val otherRegion = game("Game (Europe) (Disc 1).chd")
        val otherCollection = game("Game (USA) (Disc 1).chd", "other")
        val sequel = game("Game 2 (USA) (Disc 1).chd")
        assertEquals(listOf(playlist, otherRegion, otherCollection, sequel), RomScanner.hidePlaylistDiscs(listOf(playlist, otherRegion, otherCollection, sequel)))
    }
    @Test fun handlesCaseDiscSuffixesAndNestedDiscFolders() {
        val playlist = game("GAME.m3u")
        assertEquals(listOf(playlist), RomScanner.hidePlaylistDiscs(listOf(playlist, game("game - Disc 1.chd", "psx/discs"), game("Game (Disk II).iso"))))
    }
    @Test fun hidesReferencedDiscsWithDifferentNamesAndKeepsUnrelatedFiles() {
        val playlist = RomRecord("Final Fantasy IX (USA)", "content://provider/tree/primary%3AROMs/document/primary%3AROMs%2Fpsx%2FGame.m3u", "Game.m3u")
        val disc = RomRecord("Final Fantasy IX", "content://provider/tree/primary%3AROMs%2Fpsx/document/primary%3AROMs%2Fpsx%2Fdiscs%2FDisc%202%20(Rev%201)%20(1).chd", "Disc 2 (Rev 1) (1).chd")
        val otherDisc = game(disc.fileName, "other")
        val records = listOf(playlist, disc, otherDisc)
        val visible = RomScanner.hidePlaylistDiscs(records) {
            listOf("\uFEFF#EXTM3U", "", "discs\\disc 2 (rev 1) (1).chd")
        }
        assertEquals(listOf(playlist, otherDisc), visible)
    }

    @Test fun noPlaylistLeavesDiscsVisible() {
        val disc = game("Game (Disc 1).chd")
        assertEquals(listOf(disc), RomScanner.hidePlaylistDiscs(listOf(disc)))
    }
}
