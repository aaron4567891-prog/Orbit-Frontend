package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class RomLaunchUrisTest {
    @Test fun fileUrisPreserveSpacesPercentSignsAndOtherFilenameCharacters() {
        val path="/storage/emulated/0/ROMs/xbox/Spider-Man (USA) (Rev 1).xiso.iso"
        val uri=RomLaunchUris.fileUri(path)
        assertTrue(uri.startsWith("file:///"))
        assertTrue(uri.contains("Spider-Man%20(USA)"))
        assertEquals(path,java.net.URI(uri).path)
        val special="/storage/1234-5678/xbox/100% #1 Pokémon.xiso"
        assertEquals(special,java.net.URI(RomLaunchUris.fileUri(special)).path)
        assertThrows(IllegalArgumentException::class.java) { RomLaunchUris.fileUri("relative/game.iso") }
    }
    @Test fun relativeGameIdentityRetainsSubfoldersAndMatchesWholeFolderNames() {
        val roots=listOf("primary:ROMs","primary:ROMs/xbox")
        assertEquals("Spider-Man.iso",RomLaunchUris.relativeDocumentPath("primary:ROMs/xbox/Spider-Man.iso",roots,"fallback.iso"))
        assertEquals("Action/Spider-Man.iso",RomLaunchUris.relativeDocumentPath("primary:ROMs/xbox/Action/Spider-Man.iso",roots,"fallback.iso"))
        assertEquals("fallback.iso",RomLaunchUris.relativeDocumentPath("primary:ROMs/xbox360/Game.iso",listOf("primary:ROMs/xbox"),"fallback.iso"))
        assertEquals("fallback.iso",RomLaunchUris.relativeDocumentPath("1234:ROMs/xbox/Game.iso",roots,"fallback.iso"))
    }
}
