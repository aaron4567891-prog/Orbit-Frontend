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
}
