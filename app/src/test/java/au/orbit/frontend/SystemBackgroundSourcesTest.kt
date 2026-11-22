package au.orbit.frontend

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SystemBackgroundSourcesTest {
    @Test fun iconicUsesOnlyTheChosenBackgroundFolder() {
        val source = SystemBackgroundSources.iconic
        val tree = JSONObject("""{"tree":[
            {"type":"blob","path":"_inc/systems/modern-backgrounds/psx.webp"},
            {"type":"blob","path":"_inc/systems/modern-overlay/psx.webp"},
            {"type":"blob","path":"_inc/systems/classic-backgrounds/snes.webp"},
            {"type":"blob","path":"_inc/systems/modern-backgrounds/_default.webp"},
            {"type":"blob","path":"_inc/systems/modern-backgrounds/nested/nes.webp"}
        ]}""")
        val pack = SystemBackgroundSources.themePack(source, source.styles.single { it.name == "Modern" }, tree)
        assertEquals(2, pack.images.size)
        assertEquals("https://raw.githubusercontent.com/Siddy212/iconic-es-de/main/_inc/systems/modern-backgrounds/psx.webp", pack.image("psx"))
        assertNull(pack.image("snes"))
        assertNull(pack.image("nes"))
        assertEquals(source.project, pack.project)
    }

    @Test fun artBookStylesHaveIndependentImageCatalogues() {
        val source = SystemBackgroundSources.artBook
        val tree = JSONObject("""{"tree":[
            {"type":"blob","path":"_inc/systems/artwork-noir/gba.png"},
            {"type":"blob","path":"_inc/systems/artwork-outline/gba.png"}
        ]}""")
        for (name in listOf("Noir", "Outline")) {
            val style = source.styles.single { it.name == name }
            val pack = SystemBackgroundSources.themePack(source, style, tree)
            assertTrue(pack.image("gba")!!.contains("/${style.directory}/"))
            assertEquals(1, pack.images.size)
        }
    }

    @Test fun neoAssetsMapsCommonSystemNamesAndRejectsUnrelatedFiles() {
        val pack = SystemBackgroundSources.neoPack(JSONObject("""{"folder":"test","name":"Test pack","author":"Artist","files":[
            {"kind":"background","system_id":"ps1","url":"https://cdn.neoassets.dev/packs/test/backgrounds/ps1.webp"},
            {"kind":"background","system_id":"genesis","url":"https://cdn.neoassets.dev/packs/test/backgrounds/genesis.gif"},
            {"kind":"background","system_id":"snes","url":"https://elsewhere.example/image.png"},
            {"kind":"logo","system_id":"gba","url":"https://cdn.neoassets.dev/packs/test/backgrounds/gba.webp"}
        ]}"""))
        assertEquals(2, pack.images.size)
        assertTrue(pack.image("psx")!!.endsWith("ps1.webp"))
        assertTrue(pack.image("megadrive")!!.endsWith("genesis.gif"))
        assertNull(pack.image("gba"))
        assertEquals("Artist", pack.author)
    }

    @Test fun exactSystemArtworkTakesPriorityOverAliases() {
        val pack = SystemBackgroundSources.Pack("Test", "Artist", "project", mapOf("psx" to "exact", "ps1" to "alias"))
        assertEquals("exact", pack.image("psx"))
        assertEquals("exact", pack.preview(listOf("psx")))
        assertNull(pack.image("unknown"))
    }

    @Test fun truncatedRepositoryCataloguesAreNotSilentlyAccepted() {
        val source = SystemBackgroundSources.artBook
        assertThrows(IllegalStateException::class.java) {
            SystemBackgroundSources.themePack(source, source.styles.first(), JSONObject("""{"truncated":true,"tree":[]}"""))
        }
    }
}
