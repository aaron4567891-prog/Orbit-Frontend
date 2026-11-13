package au.orbit.frontend

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SystemCatalogTest {
    private val assets = File("src/main/assets")
    @Test fun catalogueCoversEveryEsDeAndroidEntryAndProfilesParse() {
        val catalogue = JSONObject(File(assets, "system-catalog.json").readText()).getJSONArray("systems")
        SystemCatalog.load(File(assets, "system-catalog.json").readText())
        assertEquals(195, systems.size)
        val allIds = mutableSetOf<String>()
        var profiles = 0
        for (index in 0 until catalogue.length()) {
            val entry = catalogue.getJSONObject(index)
            val file = File(assets, "systems/${entry.getString("id")}.json")
            assertTrue("Missing $file", file.exists())
            val issues = mutableListOf<String>()
            val canonical = entry.getString("id").let { if (it == "n3ds") "3ds" else it }
            val parsed = runCatching { SharedSystemProfiles.parse(file.readText(), issues, canonical) }.getOrElse {
                assertTrue("Unexplained parser failure: ${file.name}: ${it.message}", issues.isNotEmpty())
                emptyList()
            }
            val document = JSONObject(file.readText())
            assertEquals(setOf("databaseVersion", "revisionNumber", "platform", "playerList"), document.keys().asSequence().toSet())
            val players = document.getJSONArray("playerList")
            assertEquals(entry.getInt("players"), players.length())
            if (entry.optString("playerSource") in listOf("daijisho", "cocoon")) {
                val original = File("../tools/sources/${entry.getString("playerSource")}/${entry.getString("sourceFile")}")
                val originalText = original.readText()
                val expected = JSONObject(SharedSystemProfiles.withEmulatorNamesOnly(originalText) ?: originalText)
                val comparable=JSONObject(document.toString())
                val additions=entry.optJSONArray("additionalPlayerIds")
                if(additions!=null) {
                    val ids=(0 until additions.length()).map { additions.getString(it) }.toSet()
                    val missing = ids - parsed.map { it.id }.toSet()
                    assertTrue("${file.name}: missing $missing. $issues", missing.isEmpty())
                    val originalPlayers=org.json.JSONArray()
                    for(playerIndex in 0 until players.length()) {
                        val player=players.getJSONObject(playerIndex)
                        if(player.getString("uniqueId") !in ids)originalPlayers.put(player)
                    }
                    assertEquals(ids.size,players.length()-originalPlayers.length())
                    comparable.put("playerList",originalPlayers)
                }
                assertEquals("Official fields other than names changed: ${file.name}", expected.toString(), comparable.toString())
            } else {
                assertEquals("${file.name}: ${issues.joinToString()}", entry.getInt("players"), parsed.size)
                assertTrue("Unexpected conversion issues in ${file.name}: $issues", issues.isEmpty())
            }
            parsed.forEach { assertTrue("Duplicate ${it.id}", allIds.add("$canonical::${it.id}")) }
            profiles += parsed.size
        }
        assertTrue(profiles > 1000)
        assertTrue(allIds.contains("psx::psx.com.github.stenzek.duckstation"))
        for (system in listOf("snes", "sfc", "snesna")) {
            assertTrue("Missing Super ZSNES for $system", allIds.contains("$system::snes.com.zsnes.superzsnes"))
        }
        assertTrue(systems.any { it.id == "3ds" })
    }
    @Test fun placeholderDefinitionsAreValidWithoutPretendPlayers() {
        SystemCatalog.load(File(assets, "system-catalog.json").readText())
        val source = File(assets, "systems/ps4.json").readText()
        assertTrue(SharedSystemProfiles.parse(source, mutableListOf()).isEmpty())
        val entries = JSONObject(File(assets, "system-catalog.json").readText()).getJSONArray("systems")
        val ps4 = (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("id") == "ps4" }
        assertTrue(ps4.getJSONObject("metadata").getJSONArray("unconvertedCommands").length() > 0)
    }
}
