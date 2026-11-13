package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class ArcadeNamesTest {
    @Test fun knownArcadeIdUsesFullNameWithoutChangingLaunchIdentity() {
        val source = JSONObject(File("src/main/assets/arcade-names.json").readText())
        val original = RomRecord("SF2", "content://roms/SF2.zip", "SF2.zip")
        val renamed = ArcadeNames.resolve("cps1", original, mapOf("sf2" to source.getString("sf2")))
        assertTrue(renamed.title.startsWith("Street Fighter II"))
        assertEquals(original.uri, renamed.uri)
        assertEquals(original.fileName, renamed.fileName)
    }
    @Test fun unknownIdsConsoleGamesAndCustomTitlesArePreserved() {
        val lookup = mapOf("sf2" to "Street Fighter II")
        val original = RomRecord("sf2", "content://game", "sf2.zip")
        assertEquals(original, ArcadeNames.resolve("snes", original, lookup))
        assertEquals(original, ArcadeNames.resolve("arcade", original, emptyMap()))
        val custom = original.copy(title = "My custom title")
        assertEquals(custom, ArcadeNames.resolve("arcade", custom, lookup))
    }
}
