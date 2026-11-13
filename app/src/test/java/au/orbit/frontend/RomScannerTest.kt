package au.orbit.frontend

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class RomScannerTest {
    @Test fun overlappingRomPathsReadEachDirectoryOnce() {
        val calls=mutableMapOf<String,Int>()
        fun source(root:String)=object:RomSource {
            override val directoryCacheNamespace="consistent-test-provider"
            override val rootId=root
            override val label=root
            override fun identity(node:RomNode)=node.id
            override fun children(id:String):List<RomNode> {
                calls[id]=(calls[id] ?: 0)+1
                return if(id=="root")listOf(RomNode("nested","nested",true,"content://roms/nested"))
                else listOf(RomNode("game","Game.gba",false,"content://roms/game"))
            }
        }
        val result=RomScanner.scan(listOf(source("root"),source("nested")),setOf("gba"))
        assertEquals(1,result.games.size)
        assertEquals(mapOf("root" to 1,"nested" to 1),calls)
    }
    private class Source(override val rootId: String, private val nodes: Map<String, List<RomNode>>) : RomSource {
        override val label = rootId
        override fun children(id: String) = nodes[id] ?: error("Permission lost")
        override fun identity(node: RomNode) = node.id
        override fun directory(id:String)=nodes.values.flatten().firstOrNull { it.id==id && it.directory }
            ?: RomNode(id,id,true,"content://roms/$id")
    }
    private fun file(id: String, name: String) = RomNode(id, name, false, "content://roms/$id")
    @Test fun ps3DiscAndPsnFoldersAreSingleGamesWithoutInternalFiles() {
        fun dir(id:String,name:String)=RomNode(id,name,true,"content://roms/$id")
        val source=Source("ps3",mapOf(
            "ps3" to listOf(dir("disc","Disc Game"),dir("psn","PSN Game"),file("iso","Image.iso"),dir("other","Ordinary folder")),
            "disc" to listOf(dir("disc/game","PS3_GAME"),dir("disc/update","PS3_UPDATE"),file("disc/extra","internal.iso")),
            "psn" to listOf(dir("psn/usr","usrdir"),file("psn/sfo","param.sfo"),file("psn/data","internal.bin")),
            "other" to listOf(file("other/game","Another.iso"))
        ))
        val result=RomScanner.scan(listOf(source),setOf("iso","bin","ps3folder"),detectPs3Folders=true)
        assertTrue(result.errors.joinToString(),result.errors.isEmpty())
        assertEquals(setOf("Disc Game","PSN Game","Image","Another"),result.games.map { it.title }.toSet())
        assertEquals(2,result.games.count { it.directory })
        assertEquals("content://roms/disc",result.games.single { it.title=="Disc Game" }.uri)
        assertEquals("PSN Game.ps3folder",result.games.single { it.title=="PSN Game" }.fileName)
        assertFalse(result.games.any { "internal" in it.title })
    }
    @Test fun ps3RootSelectionAndOverlappingPathsDeduplicateTheSameFolder() {
        val root=Source("game",mapOf("game" to listOf(RomNode("game/usr","USRDIR",true,"content://roms/game/usr"),file("game/param","PARAM.SFO"))))
        val result=RomScanner.scan(listOf(root,root),setOf("ps3folder"),detectPs3Folders=true)
        assertEquals(1,result.games.size)
        assertTrue(result.games.single().directory)
        assertTrue(result.errors.isEmpty())
    }
    @Test fun ps3FolderRecognitionDoesNotApplyToOtherSystems() {
        val source=Source("root",mapOf("root" to listOf(RomNode("usr","USRDIR",true,"content://roms/usr"),file("param","PARAM.SFO")),"usr" to listOf(file("inside","Example.bin"))))
        val result=RomScanner.scan(listOf(source),setOf("bin"))
        assertEquals(listOf("Example"),result.games.map { it.title })
    }
    @Test fun mergesNestedAndOverlappingFoldersWithoutMergingDifferentGamesWithSameTitle() {
        val game = file("disc/game.chd", "Game.CHD")
        val nested = RomNode("disc", "Discs", true, "content://roms/disc")
        val a = Source("root", mapOf("root" to listOf(nested, file("hidden", ".hidden.chd"), file("note", "readme.txt")), "disc" to listOf(game)))
        val b = Source("disc", mapOf("disc" to listOf(game, file("other/game.chd", "Game.chd"))))
        val result = RomScanner.scan(listOf(a, b), setOf("chd"))
        assertEquals(2, result.games.size)
        assertTrue(result.errors.isEmpty())
        assertEquals(setOf("content://roms/disc/game.chd", "content://roms/other/game.chd"), result.games.map { it.uri }.toSet())
    }
    @Test fun inaccessibleFolderDoesNotDiscardOtherFolderResults() {
        val result = RomScanner.scan(listOf(Source("missing", emptyMap()), Source("good", mapOf("good" to listOf(file("one", "One.gba"))))), setOf("gba"))
        assertEquals(1, result.games.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("Permission lost"))
    }
    @Test fun cancellationStopsTraversal() {
        assertThrows(CancellationException::class.java) { RomScanner.scan(listOf(Source("root", emptyMap())), setOf("chd"), { true }) }
    }
    @Test fun cyclesAreVisitedOnce() {
        val source = Source("root", mapOf("root" to listOf(RomNode("root", "loop", true, "content://roms/root"), file("one", "One.chd"))))
        assertEquals(1, RomScanner.scan(listOf(source), setOf("chd")).games.size)
    }
    @Test fun migratesSinglePathAndDeduplicatesPathArrays() {
        assertEquals(listOf("content://old"), UserDataStore.decodePaths("content://old"))
        assertEquals(listOf("content://a", "content://b"), UserDataStore.decodePaths(JSONArray().put("content://a").put("content://b").put("content://a").put("")))
    }
    @Test fun folderMatchingUsesIdsAliasesAndFullNamesWithoutGuessingExtensions() {
        val catalogue = listOf(SystemSpec("psx", "PlayStation", "PS", "", setOf("chd")), SystemSpec("3ds", "Nintendo 3DS", "3D", "", setOf("3ds")))
        assertEquals("psx", RomScanner.matchFolder("PSX", catalogue)?.id)
        assertEquals("psx", RomScanner.matchFolder("PlayStation", catalogue)?.id)
        assertEquals("3ds", RomScanner.matchFolder("n3ds", catalogue)?.id)
        assertNull(RomScanner.matchFolder("CHD collection", catalogue))
    }
    @Test fun globalDiscoveryFindsShortNamesInsideNestedCollections() {
        val catalogue = listOf(SystemSpec("gba", "Game Boy Advance", "GB", "", setOf("gba")), SystemSpec("psx", "PlayStation", "PS", "", setOf("chd")))
        val source = Source("root", mapOf(
            "root" to listOf(RomNode("nintendo", "Nintendo", true, "content://roms/nintendo"), RomNode("psx", "PS1", true, "content://roms/psx"), file("loose", "Loose.chd")),
            "nintendo" to listOf(RomNode("gba", "GBA", true, "content://roms/gba"))))
        val result = RomScanner.discover(source, "ROMs", catalogue)
        assertEquals(setOf("gba", "psx"), result.folders.keys.map { it.id }.toSet())
        assertEquals(listOf("content://roms/gba"), result.folders.entries.first { it.key.id == "gba" }.value)
        assertTrue(result.notes.any { "Unassigned files" in it })
    }
    @Test fun fullNameAndShortNameReferToSameSystem() {
        val catalogue = listOf(SystemSpec("gba", "Game Boy Advance", "GB", "", setOf("gba")), SystemSpec("gbc", "Game Boy Color", "GC", "", setOf("gbc")))
        assertEquals("gba", RomScanner.matchFolder("Game Boy Advance", catalogue)?.id)
        assertEquals("gba", RomScanner.matchFolder("gBa", catalogue)?.id)
        assertEquals("gbc", RomScanner.matchFolder("Game Boy Colour", catalogue)?.id)
    }
}
