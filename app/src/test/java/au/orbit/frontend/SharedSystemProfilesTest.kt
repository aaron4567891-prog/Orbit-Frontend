package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SharedSystemProfilesTest {
    private fun componentArgument(system:String,id:String):String {
        val document=JSONObject(java.io.File("src/main/assets/systems/$system.json").readText())
        val players=document.getJSONArray("playerList")
        val player=(0 until players.length()).map { players.getJSONObject(it) }.single { it.getString("uniqueId")==id }
        val arguments=SharedSystemProfiles.tokenize(player.getString("amStartArguments"))
        return arguments[arguments.indexOf("-n")+1]
    }
    @Test fun xenraUsesOriginalAppAndPreservesSavedProfileIdsForBothXboxSystems() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        for(system in listOf("xbox","xbox360")) {
            val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/$system.json").readText(),mutableListOf())
            val player=profiles.single { it.name=="Xenra" }
            assertEquals("$system.Ali.Xanite.green.dev",player.id)
            assertEquals("android.intent.action.VIEW",player.action)
            if(system=="xbox") {
                assertEquals("Ali.Xanite.green/Ali.Xanite.ShortcutLaunchActivity",componentArgument(system,player.id))
                assertNull(player.dataTemplate)
                assertEquals("file://{file.path}",player.stringExtras["Ali.Xanite.extra.SHORTCUT_GAME_URI"])
                assertEquals("{file.name}",player.stringExtras["Ali.Xanite.extra.SHORTCUT_GAME_RELATIVE_PATH"])
            } else {
                assertEquals("Ali.Xanite.green/Ali.Xanite.LauncherActivity",componentArgument(system,player.id))
                assertEquals("{file.uri}",player.dataTemplate)
            }
            assertEquals(setOf("iso","xiso"),player.extensions)
        }
    }
    @Test fun gbaDsUsesDocumentedFrontendUriContract() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/gba.json").readText(),mutableListOf())
        val player=profiles.single { it.id=="gba.io.github.kyannon.gbads" }
        assertEquals("GBA DS",player.name)
        assertEquals("io.github.kyannon.gbads/.LauncherActivity",componentArgument("gba",player.id))
        assertEquals("android.intent.action.VIEW",player.action)
        assertEquals("{file.uri}",player.dataTemplate)
        assertEquals(setOf("gba","zip","7z"),player.extensions)
        assertTrue(player.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue(player.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        val accepted=Regex(player.filenameRegex!!,RegexOption.IGNORE_CASE)
        listOf("Metroid Fusion.gba","Castlevania.ZIP","Mario.7z").forEach { assertTrue(accepted.matches(it)) }
        assertFalse(accepted.matches("Game.nds"))
    }
    @Test fun gopher64UsesTheExportedSlintActivityAndViewUri() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/n64.json").readText(),mutableListOf())
        val player=profiles.single { it.name=="Gopher64" }
        assertEquals("io.github.gopher64.gopher64/.SlintActivity",componentArgument("n64",player.id))
        assertEquals("android.intent.action.VIEW",player.action)
        assertEquals("{file.uri}",player.dataTemplate)
        assertEquals(setOf("n64","v64","z64","zip","7z"),player.extensions)
    }
    @Test fun pokedaisyLaunchesAllFourSystemsUsingTheOfficialViewUriContract() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        for((system,extension) in mapOf("gb" to "gb","gbc" to "gbc","gba" to "gba","nds" to "nds")) {
            val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/$system.json").readText(),mutableListOf())
            val player=profiles.single { it.id=="$system.com.pokedaisy.app" }
            assertEquals("PokeDaisy",player.name)
            assertEquals(setOf(system),player.systems)
            assertEquals("com.pokedaisy.app/.LaunchActivity",componentArgument(system,player.id))
            assertEquals("android.intent.action.VIEW",player.action)
            assertEquals("{file.uri}",player.dataTemplate)
            assertTrue(player.stringExtras.isEmpty())
            assertEquals(0,player.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val accepted=Regex(player.filenameRegex!!,RegexOption.IGNORE_CASE)
            assertTrue(accepted.matches("Pokémon - Test.$extension"))
            assertTrue(accepted.matches("Pokemon.ZIP"))
            assertTrue(accepted.matches("Pokemon.7z"))
            assertFalse(accepted.matches("Unrelated.iso"))
        }
    }
    @Test fun standaloneScummVmAndGameHubPlayersLoadAndResolveTheirDescriptorTags() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val scumm=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/scummvm.json").readText(),mutableListOf())
        assertEquals(5,scumm.size)
        scumm.filter { it.name.startsWith("ScummVM") }.forEach {
            assertEquals("{tags.scummvm_id}",it.dataTemplate)
            assertTrue("dpt" in it.extensions)
        }
        val windows=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/windows.json").readText(),mutableListOf())
        assertEquals(15,windows.size)
        val gameHub=windows.filter { it.name.startsWith("GameHub") }
        assertEquals(4,gameHub.size)
        gameHub.forEach { assertEquals("{tags.localgameid}",it.stringExtras["localGameId"]);assertTrue("localgameid" in it.extensions) }
        assertEquals("monkey",GameLaunchTags.scummVmId("# Game\n[scummvm_id] monkey\n"))
        assertEquals("scumm:monkey",GameLaunchTags.scummVmId("[scummvm_id]\nscumm:monkey"))
        assertEquals("monkey",GameLaunchTags.scummVmId("monkey"))
        assertEquals("12345",GameLaunchTags.localGameId("[localgameid] 12345"))
        assertEquals("game-123",GameLaunchTags.localGameId("[localgameid]\ngame-123"))
        assertEquals("12345",GameLaunchTags.localGameId("12345"))
        listOf("","# no ID","[scummvm_id]","[scummvm_id]\n[other] value").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { GameLaunchTags.scummVmId(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { GameLaunchTags.localGameId("[localgameid]\n[other] 123") }
        for (tag in listOf("gog", "epicgame", "customgame", "amazon")) {
            val player = windows.single { it.integerExtras["app_id"] == "{tags.$tag}" }
            assertEquals(setOf(tag), player.extensions)
            assertEquals("123", GameLaunchTags.resolve("{tags.$tag}", "# Template\n[$tag] 00123", "Game.$tag"))
            assertThrows(IllegalArgumentException::class.java) {
                GameLaunchTags.resolve("{tags.$tag}", "not a number", "Game.$tag")
            }
        }
    }
    @Test fun hakuMaliTargetsPublishedPackageAndUsesContentUri() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/xbox.json").readText(),mutableListOf())
        val player=profiles.single { it.name=="HakuX-Mali" }
        assertEquals("com.rfandango.haku_x.debug/com.rfandango.haku_x.LauncherActivity",componentArgument("xbox",player.id))
        assertEquals("android.intent.action.VIEW",player.action)
        assertEquals("{file.uri}",player.dataTemplate)
        assertEquals(setOf("iso","xiso"),player.extensions)
        assertTrue(profiles.any { it.name=="hakuX" })
    }
    @Test fun hakuUsesViewWhenLaunchArgumentsSupplyOnlyData() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val profile=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/xbox.json").readText(),mutableListOf()).single { it.name=="hakuX" }
        assertEquals("android.intent.action.VIEW",profile.action)
        assertEquals("{file.uri}",profile.dataTemplate)
        assertTrue(profile.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue(profile.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
    @Test fun inesAcceptsNesAndFdsThroughContentUri() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/nes.json").readText(),mutableListOf())
        val ines=profiles.single { it.id=="nes.com.fms.ines.free" }
        assertEquals("iNES",ines.name)
        assertEquals("android.intent.action.VIEW",ines.action)
        assertEquals("{file.uri}",ines.dataTemplate)
        assertEquals("application/octet-stream",ines.mimeType)
        assertTrue(Regex(ines.filenameRegex!!).matches("Game.nes"))
        assertTrue(Regex(ines.filenameRegex).matches("Game.fds"))
        assertFalse(Regex(ines.filenameRegex).matches("Game.sfc"))
    }
    @Test fun steamPlayersResolveStringAndIntegerAppIds() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val issues=mutableListOf<String>()
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/steam.json").readText(),issues)
        assertEquals(9,profiles.size)
        val droidDeck=profiles.single { it.name=="DroidDeck" }
        assertEquals("com.droiddeck.launcher.LAUNCH_GAME",droidDeck.action)
        assertEquals("{tags.steamappid}",droidDeck.stringExtras["app_id"])
        assertEquals("STEAM",droidDeck.stringExtras["game_source"])
        assertTrue("steam" in droidDeck.extensions)
        assertTrue(Regex(droidDeck.filenameRegex!!).matches("Temtem.steam"))
        assertEquals("745920",GameLaunchTags.steamAppId("745920","Temtem.steam"))
        assertTrue(issues.joinToString(),issues.isEmpty())
        assertEquals("{tags.steamappid}",profiles.single { it.name=="GameNative" }.integerExtras["app_id"])
        profiles.filter { it.name.startsWith("GameHub") }.forEach {
            assertEquals("{tags.steamappid}",it.stringExtras["steamAppId"])
            assertEquals(true,it.typedExtras["autoStartGame"])
        }
        assertTrue("steamappid" in systems.single { it.id=="steam" }.extensions)
        assertTrue(profiles.all { "steamappid" in it.extensions })
        assertEquals("620",GameLaunchTags.steamAppId("# Game\n[steamappid] 620\n","Portal 2.steamappid"))
        assertEquals("620",GameLaunchTags.steamAppId("620\n","Portal 2.steamappid"))
        assertEquals("620",GameLaunchTags.steamAppId("","620.steamappid"))
        listOf("bad","-1","0","2147483648").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { GameLaunchTags.steamAppId(invalid,"Game.steamappid") }
        }
    }
    @Test fun ps3PlayersSupportUriExtrasAndFolderDescriptors() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val issues=mutableListOf<String>()
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/ps3.json").readText(),issues)
        assertEquals(setOf("aPS3e","ARMSX3","aPS3e (Folder Style)","EmuCoreC","EmuCoreC (Title ID)"),profiles.map { it.name }.toSet())
        assertEquals("{file.path}",profiles.single { it.name=="ARMSX3" }.uriExtras["android.intent.extra.STREAM"])
        val arms=profiles.single { it.name=="ARMSX3" }
        assertTrue("ps3folder" in arms.extensions)
        assertTrue(Regex(arms.filenameRegex!!).matches("PSN Game.ps3folder"))
        assertFalse(Regex(arms.filenameRegex).matches("PARAM.SFO"))
        val folder=profiles.single { it.name=="aPS3e (Folder Style)" }
        assertEquals("{tags.ps3folder}",folder.stringExtras["game_dir"])
        assertTrue("ps3folder" in folder.extensions)
        assertEquals("/storage/emulated/0/Games/Example",GameLaunchTags.ps3Folder("# DST\n[ps3folder] /storage/emulated/0/Games/Example"))
        assertThrows(IllegalArgumentException::class.java) { GameLaunchTags.ps3Folder("# DST\n[ps3folder]") }
        val emuCore = profiles.single { it.name == "EmuCoreC (Title ID)" }
        assertEquals(setOf("ps3titleid"), emuCore.extensions)
        assertEquals("{tags.ps3_title_id}", emuCore.stringExtras["titleID"])
        assertEquals("NPUB12345", GameLaunchTags.resolve("{tags.ps3_title_id}", "[ps3_title_id]\nNPUB12345", "Game.ps3titleid"))
        assertFalse(issues.joinToString(),issues.any { "Unsupported" in it })
    }
    @Test fun vitaPlayersSupportStringArraysAndTemplateTitleIds() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val issues=mutableListOf<String>()
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/psvita.json").readText(),issues)
        assertEquals(4,profiles.size)
        assertTrue(issues.joinToString(),issues.isEmpty())
        profiles.filter { it.name != "EmuCoreV" }.forEach {
            assertEquals(listOf("-r","{tags.vita_game_id}"),it.stringArrayExtras["AppStartParameters"])
        }
        assertEquals("{tags.vita_game_id}", profiles.single { it.name == "EmuCoreV" }.stringExtras["titleId"])
        assertEquals("PCSF00001",GameLaunchTags.vitaId("# Daijishou Player Template\n[vita_game_id] PCSF00001\n","Golden Abyss.dpt"))
        assertEquals("PCSE00546",GameLaunchTags.vitaId("PCSE00546","Retro City.psvita"))
        assertThrows(IllegalArgumentException::class.java) { GameLaunchTags.vitaId("invalid","Game.dpt") }
    }
    @Test fun pspStandalonePlayersKeepCategoriesAndNoHistoryFlag() {
        val issues=mutableListOf<String>()
        val profiles=SharedSystemProfiles.parse(java.io.File("src/main/assets/systems/psp.json").readText(),issues)
        for(name in listOf("PPSSPP","PPSSPP Gold")) {
            val profile=profiles.single { it.name==name }
            assertEquals(setOf("android.intent.category.DEFAULT"),profile.categories)
            assertEquals("{file.uri}",profile.dataTemplate)
            assertTrue(profile.flags and android.content.Intent.FLAG_ACTIVITY_NO_HISTORY != 0)
        }
        assertEquals(8,profiles.size)
        assertFalse(issues.joinToString(),issues.any { "Unsupported launch argument" in it })
    }
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    @Test fun readsActualCocoonPlayStationFile() {
        val issues = mutableListOf<String>()
        val profiles = SharedSystemProfiles.parse(fixture("cocoon-psx.json"), issues)
        val duck = profiles.first { it.name == "Duckstation" }
        assertEquals("{file.uri}", duck.stringExtras["bootPath"])
        assertEquals(false, duck.typedExtras["resumeState"])
        assertTrue("chd" in duck.extensions)
        assertFalse("bin" in duck.extensions)
        assertTrue(profiles.any { it.dataTemplate == "{file.uri}" })
        assertTrue(profiles.any { it.stringExtras["bootPath"] == "{file.path}" })
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
    }
    @Test fun readsActualDaijishoFileWithDuplicateLegacyId() {
        val profiles = SharedSystemProfiles.parse(fixture("daijisho-psx.json"), mutableListOf())
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        assertEquals(2, profiles.count { it.stringExtras.containsKey("bootPath") })
        assertTrue(profiles.any { it.stringExtras["bootPath"] == "{file.uri}" })
        val core = profiles.single { it.id == "psx.ra64.goosestation" }.stringExtras.getValue("LIBRETRO")
        assertEquals("goosestation", core)
        val corePath = "/data/data/com.retroarch.aarch64/cores/goosestation_libretro_android.so"
        assertEquals(corePath, retroArchCorePath("com.retroarch.aarch64", core))
        assertEquals(corePath, retroArchCorePath("com.retroarch.aarch64", corePath))
    }
    @Test fun azaharLaunchPassesTheCoreNameToRetroArch() {
        SystemCatalog.load(java.io.File("src/main/assets/system-catalog.json").readText())
        val source = java.io.File("src/main/assets/systems/n3ds.json").readText()
        val profiles = SharedSystemProfiles.parse(source, mutableListOf())
        for ((id, packageName) in mapOf("3ds.ra64.azahar" to "com.retroarch.aarch64", "3ds.ra.azahar" to "com.retroarch")) {
            val core = profiles.single { it.id == id }.stringExtras.getValue("LIBRETRO")
            assertEquals("azahar", retroArchCorePath(packageName, core))
        }
    }
    @Test fun quotedArgumentsAndEmptyExtrasRemainSeparate() {
        assertEquals(listOf("-e", "name", "with spaces", "-e", "empty", "", "--ez", "resume", "0"),
            SharedSystemProfiles.tokenize("-e name 'with spaces' -e empty \"\"\n--ez resume 0"))
    }
    @Test fun unknownArgumentIsReportedWithoutDiscardingOtherPlayers() {
        val json = JSONObject(fixture("daijisho-psx.json"))
        json.getJSONArray("playerList").getJSONObject(0).put("amStartArguments", "--unknown argument")
        val issues = mutableListOf<String>()
        val profiles = SharedSystemProfiles.parse(json.toString(), issues)
        assertTrue(profiles.isNotEmpty())
        assertTrue(issues.any { "Unsupported launch argument" in it })
    }
    @Test fun refusesUnsupportedSystemAndBrokenQuotes() {
        val json = JSONObject(fixture("cocoon-psx.json"))
        json.getJSONObject("platform").put("shortname", "unknown-console")
        assertThrows(IllegalArgumentException::class.java) { SharedSystemProfiles.parse(json.toString(), mutableListOf()) }
        assertThrows(IllegalArgumentException::class.java) { SharedSystemProfiles.tokenize("-e key 'unclosed") }
    }
}
