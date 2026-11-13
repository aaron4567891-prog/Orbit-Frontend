package au.orbit.frontend

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Every declared player must survive parsing, including official imported definitions. */
class EmulatorProfileAuditTest {
    private fun audit(folder:File) {
        SystemCatalog.load(File("src/main/assets/system-catalog.json").readText())
        val failures=mutableListOf<String>()
        var declared=0
        var loaded=0
        val files=folder.listFiles().orEmpty().filter { it.extension.equals("json",true) }.sortedBy { it.name }
        assertTrue("No profiles in $folder",files.isNotEmpty())
        files.forEach { file ->
            val warnings=mutableListOf<String>()
            runCatching {
                val source=file.readText()
                val json=JSONObject(source)
                val expected=if(json.has("schemaVersion"))1 else json.getJSONArray("playerList").length()
                val hint=file.nameWithoutExtension.let { if(it=="n3ds")"3ds" else it }.takeIf { id -> systems.any { it.id==id } }
                val profiles=SharedSystemProfiles.parse(source,warnings,hint)
                declared+=expected;loaded+=profiles.size
                if(expected!=profiles.size)failures.add("${file.name}: $expected declared, ${profiles.size} loaded: ${warnings.joinToString("; ")}")
                val duplicate=profiles.groupBy { it.id }.filterValues { it.size>1 }.keys
                if(duplicate.isNotEmpty())failures.add("${file.name}: duplicate IDs $duplicate")
            }.onFailure { failures.add("${file.name}: ${it.message}; ${warnings.joinToString("; ")}") }
        }
        println("Profile audit: ${files.size} files, $declared declared, $loaded loaded")
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
    @Test fun everyBundledPlayerLoads()=audit(File("src/main/assets/systems"))
    @Test fun auditDeviceProfilesWhenProvided() {
        System.getenv("ORBIT_PROFILE_AUDIT_DIR")?.let { audit(File(it)) }
    }
}
