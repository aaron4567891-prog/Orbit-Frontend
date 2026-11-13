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
    @Test fun noPlaylistLeavesDiscsVisible() {
        val disc = game("Game (Disc 1).chd")
        assertEquals(listOf(disc), RomScanner.hidePlaylistDiscs(listOf(disc)))
    }
}
