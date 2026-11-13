package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class MediaImportPlanTest {
    @Test fun singleSystemImportExcludesOtherSystemsAndAcceptsCategoryRoot() {
        val plan=MediaImportPlan.create(listOf(
            MediaImportPlan.Source("selected",listOf("psx","covers","Game.png")),
            MediaImportPlan.Source("other",listOf("steam","covers","Game.png")),
            MediaImportPlan.Source("category",listOf("videos","Game.mp4"))
        ),listOf(psx,steam),mapOf("psx" to listOf(RomRecord("Game","ps-rom","Game.chd")),
            "steam" to listOf(RomRecord("Game","steam-rom","Game.steam"))),psx)
        assertEquals(setOf("selected","category"),plan.matches.map { it.source.uri }.toSet())
        assertTrue(plan.matches.all { it.system.id=="psx" && it.game.uri=="ps-rom" })
    }

    @Test fun preservesNonLatinNamesAndDoesNotConflateRegions() {
        val plan=MediaImportPlan.create(listOf(
            MediaImportPlan.Source("a",listOf("psx","covers","星のカービィ.png")),
            MediaImportPlan.Source("b",listOf("psx","covers","Game (Europe).png"))
        ),listOf(psx),mapOf("psx" to listOf(RomRecord("星のカービィ","one","星のカービィ.chd"),RomRecord("Game (USA)","two","Game (USA).chd"))))
        assertEquals(1,plan.matches.size);assertEquals("one",plan.matches.single().game.uri);assertEquals(1,plan.unmatched)
    }
    @Test fun rejectsOverlapIncludingWholeVolumesButNotSiblingPrefixes() {
        assertTrue(MediaImportPlan.documentPathsOverlap("primary:Orbit/data","primary:"))
        assertTrue(MediaImportPlan.documentPathsOverlap("primary:Orbit/data","primary:Orbit/data/media"))
        assertFalse(MediaImportPlan.documentPathsOverlap("primary:Orbit/data","primary:Orbit/data2"))
        assertFalse(MediaImportPlan.documentPathsOverlap("primary:Orbit/data","ABCD-1234:Orbit/data"))
    }
    @Test fun personalNeoStationShortNamesResolveToCanonicalSystems() {
        val atari=SystemSpec("atari2600","Atari 2600","2600","",setOf("a26"))
        val plan=MediaImportPlan.create(listOf(MediaImportPlan.Source("a",listOf("Neostation 2","media","2600","box2d","Game.png"))),
            listOf(atari),mapOf(atari.id to listOf(RomRecord("Game","rom","Game.a26"))))
        assertEquals("atari2600",plan.matches.single().system.id)
        assertEquals("covers",plan.matches.single().kind.folder)
    }
    private val psx=SystemSpec("psx","PlayStation","PS","",setOf("cue","chd"))
    private val steam=SystemSpec("steam","Valve Steam","STM","",setOf("steam","steamappid"))
    @Test fun translatesBothLayoutsAndMatchesCaseAndPunctuation() {
        val plan=MediaImportPlan.create(listOf(
            MediaImportPlan.Source("cover",listOf("downloaded_media","psx","covers","Alone in the Dark The New Nightmare (USA).png")),
            MediaImportPlan.Source("logo",listOf("media","steam","wheels","Temtem.png")),
            MediaImportPlan.Source("fan",listOf("media","steam","fanarts","Temtem.jpg"))
        ),listOf(psx,steam),mapOf("psx" to listOf(RomRecord("Alone in the Dark - The New Nightmare (USA)","rom1","alone in the dark - the new nightmare (usa).chd")),
            "steam" to listOf(RomRecord("Temtem","rom2","Temtem.steam"))))
        assertEquals(3,plan.matches.size)
        assertEquals(listOf("covers","marquees","fanart"),plan.matches.map { it.kind.folder })
        assertEquals(0,plan.unmatched)
    }
    @Test fun skipsAmbiguousUnmatchedAndUnsupportedFilesWithoutCrossSystemMatches() {
        val plan=MediaImportPlan.create(listOf(
            MediaImportPlan.Source("a",listOf("psx","covers","Game.png")),
            MediaImportPlan.Source("b",listOf("steam","covers","Game.png")),
            MediaImportPlan.Source("c",listOf("psx","covers","Other.png")),
            MediaImportPlan.Source("d",listOf("psx","covers","Game.exe"))
        ),listOf(psx,steam),mapOf("psx" to listOf(RomRecord("Game","one","Game.cue"),RomRecord("Game","two","Game.chd"))))
        assertTrue(plan.matches.isEmpty());assertEquals(1,plan.ambiguous);assertEquals(2,plan.unmatched);assertEquals(1,plan.unsupported)
    }
    @Test fun acceptsSystemFolderRootsRomExtensionsAndAliasNames() {
        val gba=SystemSpec("gba","Game Boy Advance","GBA","",setOf("gba"))
        val files=listOf(MediaImportPlan.Source("a",listOf("Game Boy Advance","videos","Game.gba.mp4")),
            MediaImportPlan.Source("b",listOf("gba","manuals","Game.pdf")),
            MediaImportPlan.Source("c",listOf("gba","3dboxes","Game.png")))
        val plan=MediaImportPlan.create(files+files.first(),listOf(gba),mapOf("gba" to listOf(RomRecord("Game","rom","Game.gba"))))
        assertEquals(3,plan.matches.size)
    }
}
