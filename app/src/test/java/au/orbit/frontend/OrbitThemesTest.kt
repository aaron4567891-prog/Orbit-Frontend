package au.orbit.frontend

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitThemesTest {
    private val folder=File("src/main/assets/themes/native")
    @Test fun systemLogosPersistSeparatelyFromOtherSystemArtwork() {
        val original=OrbitThemes.parse(File(folder,"midnight.json").readText())
            .withSystemHero("psx",setOf("top"),"artwork/hero.png")
            .withSystemImage("psx",setOf("bottom"),"artwork/console.png")
        assertTrue(original.systemLogos.isEmpty())
        val customized=original.withSystemLogo("psx","artwork/logo.webp")
            .withSystemLogo("gba","artwork/gba.png")
        val restored=OrbitThemes.parse(customized.json())
        assertEquals("artwork/logo.webp",restored.systemLogos["psx"])
        assertEquals(original.systemHeroes,restored.systemHeroes)
        assertEquals(original.systemImages,restored.systemImages)
        assertEquals(mapOf("gba" to "artwork/gba.png"),restored.withSystemLogo("psx",null).systemLogos)
        val invalid=JSONObject(restored.json()).put("systemLogos",JSONObject().put("psx","artwork/../logo.png"))
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(invalid.toString()) }
    }
    @Test fun customSystemImagesAreIndependentOfHeroesAndOtherScreens() {
        val global=OrbitThemes.parse(File(folder,"midnight.json").readText())
            .withArtwork("hero",setOf("top","bottom"),"artwork/global-hero.png")
        val custom=global.withSystemHero("psx",setOf("top"),"artwork/psx-hero.png")
            .withSystemImage("psx",setOf("top"),"artwork/top-console.webp")
            .withSystemImage("psx",setOf("bottom"),"artwork/bottom-console.png")
            .withSystemImage("gba",setOf("top"),"artwork/gba.png")
        assertEquals("artwork/psx-hero.png",custom.heroImage("top","psx"))
        assertEquals("artwork/global-hero.png",custom.heroImage("bottom","psx"))
        assertEquals("artwork/top-console.webp",custom.systemImages["psx"]?.get("top"))
        assertEquals("artwork/bottom-console.png",custom.systemImages["psx"]?.get("bottom"))
        assertEquals(global.artwork,custom.artwork)
        assertEquals(custom,OrbitThemes.parse(custom.json()))
        val reset=custom.withSystemImage("psx",setOf("top"),null)
        assertNull(reset.systemImages["psx"]?.get("top"))
        assertEquals(custom.systemImages["psx"]?.get("bottom"),reset.systemImages["psx"]?.get("bottom"))
        assertEquals(custom.systemImages["gba"],reset.systemImages["gba"])
        assertEquals(custom.systemHeroes,reset.systemHeroes)
        assertEquals(custom.systemImages,custom.withSystemHero("psx",setOf("top"),null).systemImages)
    }
    @Test fun topAndBottomImageVisibilityCanBeToggledIndependentlyWithoutRemovingImages() {
        val original=OrbitThemes.parse(File(folder,"midnight.json").readText())
            .withSystemHero("psx",setOf("top","bottom"),"artwork/hero.png")
            .withSystemImage("psx",setOf("top","bottom"),"artwork/console.png")
        val topHidden=original.withSystemArtworkVisible("psx","top",false)
        assertFalse(topHidden.showSystemArtwork("psx","top"))
        assertTrue(topHidden.showSystemArtwork("psx","bottom"))
        assertTrue(topHidden.showSystemArtwork("gba","top"))
        val bothHidden=topHidden.withSystemArtworkVisible("psx","bottom",false)
        assertFalse(bothHidden.showSystemArtwork("psx","top"))
        assertFalse(bothHidden.showSystemArtwork("psx","bottom"))
        val bottomHidden=bothHidden.withSystemArtworkVisible("psx","top",true)
        assertTrue(bottomHidden.showSystemArtwork("psx","top"))
        assertFalse(bottomHidden.showSystemArtwork("psx","bottom"))
        assertEquals(original.systemHeroes,bottomHidden.systemHeroes)
        assertEquals(original.systemImages,bottomHidden.systemImages)
        assertEquals(bottomHidden,OrbitThemes.parse(bottomHidden.json()))
        assertEquals(original,bottomHidden.withSystemArtworkScreens("psx",null))
    }
    @Test fun customSystemImagePathsAndScreensAreValidated() {
        val base=OrbitThemes.parse(File(folder,"midnight.json").readText())
        assertTrue(base.systemImages.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { base.withSystemImage("../psx",setOf("top"),"artwork/test.png") }
        assertThrows(IllegalArgumentException::class.java) { base.withSystemImage("psx",setOf("left"),"artwork/test.png") }
        assertThrows(IllegalArgumentException::class.java) { base.withSystemImage("psx",setOf("top"),"../settings.json") }
        val invalid=JSONObject(base.json()).put("systemImages",JSONObject().put("psx",JSONObject().put("bottom","artwork/../settings.json")))
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(invalid.toString()) }
    }
    @Test fun perSystemHeroOverridesOnlyThatSystemAndScreen() {
        val global=OrbitThemes.parse(File(folder,"midnight.json").readText())
            .withArtwork("hero",setOf("top","bottom"),"artwork/global.png")
            .withArtwork("background",setOf("top"),"artwork/background.jpg")
        val custom=global.withSystemHero("psx",setOf("top"),"artwork/playstation.webp")
            .withSystemHero("gba",setOf("bottom"),"artwork/gameboy.png")
        assertEquals("artwork/playstation.webp",custom.heroImage("top","psx"))
        assertEquals("artwork/global.png",custom.heroImage("bottom","psx"))
        assertEquals("artwork/global.png",custom.heroImage("top","snes"))
        assertEquals("artwork/gameboy.png",custom.heroImage("bottom","gba"))
        assertEquals(global.artwork,custom.artwork)
        val restored=custom.withSystemHero("psx",setOf("top","bottom"),null)
        assertFalse(restored.systemHeroes.containsKey("psx"))
        assertEquals("artwork/global.png",restored.heroImage("top","psx"))
        assertEquals(custom.systemHeroes["gba"],restored.systemHeroes["gba"])
        val changedGlobal=custom.withArtwork("hero",setOf("top"),"artwork/new-global.png")
        assertEquals("artwork/playstation.webp",changedGlobal.heroImage("top","psx"))
        assertEquals("artwork/new-global.png",changedGlobal.heroImage("top","snes"))
    }
    @Test fun perSystemImageVisibilityCanOverrideAndReturnToGlobalSettings() {
        val global=OrbitThemes.parse(File(folder,"midnight.json").readText()).copy(systemArtworkScreens=setOf("bottom"))
        val custom=global.withSystemHero("psx",setOf("top"),"artwork/psx.png")
            .withSystemArtworkScreens("psx",setOf("top"))
            .withSystemArtworkScreens("gba",emptySet())
        assertTrue(custom.showSystemArtwork("psx","top"))
        assertFalse(custom.showSystemArtwork("psx","bottom"))
        assertFalse(custom.showSystemArtwork("gba","top"))
        assertFalse(custom.showSystemArtwork("gba","bottom"))
        assertTrue(custom.showSystemArtwork("snes","bottom"))
        assertFalse(custom.showSystemArtwork("snes","top"))
        assertEquals("artwork/psx.png",custom.heroImage("top","psx"))
        val restored=custom.withSystemArtworkScreens("psx",null)
        assertTrue(restored.showSystemArtwork("psx","bottom"))
        assertFalse(restored.showSystemArtwork("psx","top"))
        assertFalse(restored.systemArtworkOverrides.containsKey("psx"))
        assertEquals(custom.systemHeroes,restored.systemHeroes)
    }
    @Test fun systemArtworkOverridesSurviveSavingAndPreserveIndependentScreens() {
        val original=OrbitThemes.parse(File(folder,"midnight.json").readText())
        assertTrue(original.systemHeroes.isEmpty())
        assertTrue(original.systemArtworkOverrides.isEmpty())
        val custom=original.withSystemHero("psx",setOf("top","bottom"),"artwork/both.png")
            .withSystemHero("psx",setOf("bottom"),"artwork/bottom.webp")
            .withSystemArtworkScreens("psx",emptySet())
        assertEquals(custom,OrbitThemes.parse(custom.json()))
        val removed=custom.withSystemHero("psx",setOf("top"),null)
        assertNull(removed.heroImage("top","psx"))
        assertEquals("artwork/bottom.webp",removed.heroImage("bottom","psx"))
        assertEquals(custom.systemArtworkOverrides,removed.systemArtworkOverrides)
        assertEquals(removed,OrbitThemes.parse(removed.json()))
    }
    @Test fun invalidPerSystemArtworkCannotEscapeStorageOrUseUnknownScreens() {
        val base=OrbitThemes.parse(File(folder,"midnight.json").readText())
        for(system in listOf("../settings","","psx/top")) {
            assertThrows(IllegalArgumentException::class.java) { base.withSystemHero(system,setOf("top"),"artwork/hero.png") }
            assertThrows(IllegalArgumentException::class.java) { base.withSystemArtworkScreens(system,emptySet()) }
        }
        assertThrows(IllegalArgumentException::class.java) { base.withSystemHero("psx",setOf("left"),"artwork/hero.png") }
        assertThrows(IllegalArgumentException::class.java) { base.withSystemHero("psx",emptySet(),"artwork/hero.png") }
        assertThrows(IllegalArgumentException::class.java) { base.withSystemHero("psx",setOf("top"),"../settings.json") }
        assertThrows(IllegalArgumentException::class.java) { base.withSystemArtworkScreens("psx",setOf("left")) }
        val badHero=JSONObject(base.json()).put("systemHeroes",JSONObject().put("psx",JSONObject().put("top","artwork/../secret.png")))
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(badHero.toString()) }
        val badScreen=JSONObject(base.json()).put("systemArtworkOverrides",JSONObject().put("psx",org.json.JSONArray().put("left")))
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(badScreen.toString()) }
    }
    @Test fun systemArtworkCanBeHiddenWithoutRemovingCustomHeroOrBackground() {
        val original=OrbitThemes.parse(File(folder,"midnight.json").readText())
            .withArtwork("hero",setOf("top"),"artwork/hero.png")
            .withArtwork("background",setOf("top","bottom"),"artwork/background.png")
        val hidden=original.copy(systemArtworkScreens=emptySet())
        val restored=OrbitThemes.parse(hidden.json())
        assertTrue(restored.systemArtworkScreens.isEmpty())
        assertEquals(original.artwork,restored.artwork)
        val bottom=original.copy(systemArtworkScreens=setOf("bottom"))
        assertEquals(setOf("bottom"),OrbitThemes.parse(bottom.json()).systemArtworkScreens)
        val invalid=JSONObject(original.json()).put("systemArtworkScreens",org.json.JSONArray().put("left"))
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(invalid.toString()) }
    }
    @Test fun artworkTargetsRoundTripAndCanBeChangedIndependently() {
        val base=OrbitThemes.parse(File(folder,"midnight.json").readText())
        val both=base.withArtwork("background",setOf("top","bottom"),"artwork/wallpaper.png")
        assertEquals("artwork/wallpaper.png",both.image("background","top"))
        assertEquals("artwork/wallpaper.png",both.image("background","bottom"))
        val hero=both.withArtwork("hero",setOf("bottom"),"artwork/hero.webp")
        assertNull(hero.image("hero","top"))
        assertEquals("artwork/hero.webp",hero.image("hero","bottom"))
        assertEquals(hero,OrbitThemes.parse(hero.json()))
        val removed=hero.withArtwork("background",setOf("top"),null)
        assertNull(removed.image("background","top"))
        assertEquals("artwork/wallpaper.png",removed.image("background","bottom"))
        assertEquals("artwork/hero.webp",removed.image("hero","bottom"))
        val replaced=hero.withArtwork("background",setOf("top"),"artwork/new.jpg")
        assertEquals("artwork/new.jpg",replaced.image("background","top"))
        assertEquals("artwork/wallpaper.png",replaced.image("background","bottom"))
    }
    @Test fun artworkReferencesCannotEscapeTheirFolderOrDuplicateScreenAssignments() {
        val base=OrbitThemes.parse(File(folder,"midnight.json").readText())
        listOf("../settings.json","artwork/../escape.png","/sdcard/picture.png","artwork/a/b.png","https://example.com/a.png").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { base.withArtwork("hero",setOf("top"),path) }
        }
        assertThrows(IllegalArgumentException::class.java) { base.withArtwork("hero",setOf("invalid"),"artwork/hero.png") }
        val item=JSONObject().put("role","hero").put("screen","top").put("file","artwork/hero.png")
        val invalid=JSONObject(base.json()).put("artwork",org.json.JSONArray().put(item).put(item))
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(invalid.toString()) }
    }
    @Test fun everyCatalogSystemHasStandaloneConsoleArtwork() {
        val systems=JSONObject(File("src/main/assets/system-catalog.json").readText()).getJSONArray("systems")
        for(index in 0 until systems.length()) {
            val id=systems.getJSONObject(index).getString("id").let { if(it=="n3ds")"3ds" else it }
            val file=File("src/main/assets/system-artwork/$id.webp")
            assertTrue("Missing console image for $id",file.isFile && file.length()>0)
        }
    }
    @Test fun allNativePresetsRoundTripWithoutLosingTheirLayoutOrColors() {
        val presets=folder.listFiles()!!.filter { it.extension=="json" }.map { OrbitThemes.parse(it.readText()) }
        assertEquals(setOf("midnight","orbit-split"),presets.map { it.id }.toSet())
        presets.forEach { assertEquals(it,OrbitThemes.parse(it.json())) }
        val midnight=presets.single { it.id=="midnight" }
        assertEquals("artwork",midnight.systemLayout)
        assertEquals(0xff081a32.toInt(),midnight.background)
        assertTrue(midnight.listFraction<0.5f)
        assertEquals("top",midnight.libraryScreen)
        val split=presets.single { it.id=="orbit-split" }
        assertEquals("3DS",split.name)
        assertEquals("bottom",split.libraryScreen)
    }
    @Test fun customNamesAndPaletteCanBeEditedInJson() {
        val theme=OrbitThemes.parse(File(folder,"midnight.json").readText()).copy(id="my-theme",name="Aaron’s theme",accent=0xff2266cc.toInt(),listFraction=0.5f)
        assertEquals(theme,OrbitThemes.parse(theme.json()))
        assertTrue(OrbitThemes.canDelete(theme))
    }
    @Test fun builtInOrbitThemesCannotBeDeleted() {
        folder.listFiles()!!.filter { it.extension=="json" }.map { OrbitThemes.parse(it.readText()) }.forEach {
            assertFalse(it.name,OrbitThemes.canDelete(it))
            assertThrows(IllegalArgumentException::class.java) { OrbitThemes.delete(android.content.ContextWrapper(null),it) }
        }
    }
    @Test fun invalidNativeDefinitionsAreRejected() {
        val source=File(folder,"midnight.json").readText()
        for((key,value) in listOf("version" to 2,"id" to "../escape","name" to " ","systemLayout" to "unknown","gameListFraction" to 1.0,"libraryScreen" to "left")) {
            assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(JSONObject(source).put(key,value).toString()) }
        }
        val color=JSONObject(source).apply { getJSONObject("colors").put("accent","oops") }
        assertThrows(IllegalArgumentException::class.java) { OrbitThemes.parse(color.toString()) }
    }

    @Test fun removedSystemArtworkHidesHeroesAndLogosAndSurvivesSaving() {
        val original=OrbitThemes.parse(File(folder,"midnight.json").readText())
            .withSystemHero("psx",setOf("top"),"artwork/custom.png")
        val removed=original.withSystemArtworkRemoved("psx","hero/top",true).withSystemArtworkRemoved("psx","logo",true)
        assertNull(removed.heroImage("top","psx"))
        assertTrue(removed.heroRemoved("psx","top") && !removed.heroRemoved("psx","bottom"))
        assertTrue(removed.logoRemoved("psx"))
        val restored=OrbitThemes.parse(removed.json())
        assertEquals(removed.removedSystemArtwork,restored.removedSystemArtwork)
        // Choosing a new image or logo brings that slot back.
        val chosen=restored.withSystemHero("psx",setOf("top"),"artwork/new.png").withSystemLogo("psx","artwork/logo.png")
        assertEquals("artwork/new.png",chosen.heroImage("top","psx"))
        assertFalse(chosen.logoRemoved("psx"))
        assertThrows(IllegalArgumentException::class.java) { original.withSystemArtworkRemoved("psx","system/top",true) }
    }
}
