package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class MediaLayoutTest {
    @Test fun namesPreserveRegionsAndUnicode() {
        assertEquals("Crash Bandicoot (Europe).png", MediaLayout.fileName("Crash Bandicoot (Europe).chd", "PNG"))
        assertEquals("遊戲.jpg", MediaLayout.fileName("遊戲.nes", "jpg"))
        assertNotEquals(MediaLayout.fileName("Game (USA).nes", "png"), MediaLayout.fileName("Game (Japan).nes", "png"))
    }
    @Test fun filenamesCannotEscapeTheirCategory() {
        val name=MediaLayout.fileName("../bad\\name?.nes", "png")
        assertTrue(MediaLayout.validPart(name))
        assertFalse(name.startsWith('.'))
        assertFalse(MediaLayout.validPart(".."))
        assertFalse(MediaLayout.validPart("covers/file.png"))
    }

    @Test fun gameMediaDeletionAcceptsOwnedPathsAndRejectsTraversal() {
        val id = "a".repeat(64)
        val group = "psx/$id"
        org.junit.Assert.assertEquals(listOf("media", "psx", "videos", "Game.mp4"),
            MediaLayout.gameMediaPath(group, "@/psx/videos/Game.mp4"))
        org.junit.Assert.assertEquals(listOf("media", "psx", id, "covers", "old.png"),
            MediaLayout.gameMediaPath(group, "covers/old.png"))
        org.junit.Assert.assertNull(MediaLayout.gameMediaPath(group, "@/gba/covers/Game.png"))
        org.junit.Assert.assertNull(MediaLayout.gameMediaPath(group, "../covers/Game.png"))
    }
}
