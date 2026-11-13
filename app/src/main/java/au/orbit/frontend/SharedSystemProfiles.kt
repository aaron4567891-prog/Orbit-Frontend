package au.orbit.frontend

import android.content.ComponentName
import android.content.Intent
import org.json.JSONObject

/** Reads Cocoon/Daijishō platform+playerList documents. Arguments become Intents, never shell commands. */
object SharedSystemProfiles {
    fun portableSource(source: String): String {
        val json = JSONObject(source)
        if (!json.has("schemaVersion")) return source
        val profile = EmulatorProfileRepository.parse(source)
        require(profile.systems.size == 1) { "Legacy profile must target one system to export" }
        fun quote(value: String) = JSONObject.quote(value)
        val arguments = buildString {
            append("-n ${profile.component.flattenToShortString()} -a ${profile.action}")
            profile.stringExtras.forEach { (key, value) -> append(" --es ${quote(key)} ${quote(value.replace("{romUri}", "{file.uri}"))}") }
        }
        val system = profile.systems.single()
        require(profile.extensions.all { it.matches(Regex("[a-zA-Z0-9]+")) }) { "Invalid extension" }
        val regex = "^(.*)\\.(?:${profile.extensions.joinToString("|")})$"
        val platform = JSONObject().put("name", systems.firstOrNull { it.id == system }?.title ?: system)
            .put("uniqueId", system).put("shortname", system).put("acceptedFilenameRegex", regex)
            .put("scraperSourceList", org.json.JSONArray()).put("boxArtAspectRatioId", 0).put("screenAspectRatioId", 1).put("extra", "")
        val player = JSONObject().put("name", profile.name).put("uniqueId", profile.id).put("acceptedFilenameRegex", regex)
            .put("amStartArguments", arguments).put("killPackageProcesses", false).put("killPackageProcessesWarning", true).put("extra", "")
        return JSONObject().put("databaseVersion", 14).put("revisionNumber", 1).put("platform", platform)
            .put("playerList", org.json.JSONArray().put(player)).toString(2)
    }
    fun parse(source: String, warnings: MutableList<String>, systemOverride: String? = null): List<EmulatorProfile> {
        val json = JSONObject(source)
        if (json.has("schemaVersion")) return listOf(EmulatorProfileRepository.parse(source).let { profile ->
            profile.copy(name = emulatorName(profile.name, profile.systems.toList()))
        })
        val platform = json.getJSONObject("platform")
        val raw = platform.optString("shortname", platform.optString("uniqueId")).lowercase()
        val aliases = mapOf("ps1" to "psx", "ps" to "psx", "playstation" to "psx", "n3ds" to "3ds", "ds" to "nds", "sfc" to "snes",
            "cpc" to "amstradcpc", "appleii" to "apple2", "jaguar" to "atarijaguar", "jaguarcd" to "atarijaguarcd", "lynx" to "atarilynx",
            "cdi" to "cdimono1", "coleco" to "colecovision", "gw" to "gameandwatch", "master" to "mastersystem", "sg1000" to "sg-1000",
            "tgcd" to "pcenginecd", "vita" to "psvita", "ws" to "wonderswan", "wsc" to "wonderswancolor")
        val system = systemOverride ?: if (systems.any { it.id == raw }) raw else aliases[raw] ?: raw
        require(systems.any { it.id == system }) { "System '$raw' is not available in this frontend yet" }
        val players = json.getJSONArray("playerList")
        val result = mutableListOf<EmulatorProfile>()
        for (index in 0 until players.length()) {
            val player = players.getJSONObject(index)
            runCatching { parsePlayer(player, system, platform.optString("acceptedFilenameRegex")).let { profile ->
                profile.copy(name = emulatorName(profile.name, listOf(raw, system, platform.optString("uniqueId"), platform.optString("name"))))
            } }
                .onSuccess { profile ->
                    if (player.optBoolean("killPackageProcesses")) warnings.add("${profile.name}: process-kill requests are not supported; configure cores inside the emulator if needed.")
                    // Some published lists reuse uniqueId for URI and legacy path players.
                    val unique = if (result.any { it.id == profile.id }) profile.copy(id = "${profile.id}.variant.$index") else profile
                    result.add(unique)
                }.onFailure { warnings.add("${player.optString("name", "Player $index")}: ${it.message}") }
        }
        require(result.isNotEmpty() || players.length() == 0) { "No supported players. ${warnings.takeLast(3).joinToString()}" }
        return result
    }
    private fun emulatorName(name: String, prefixes: List<String>): String {
        val trimmed = name.trim().replace("MelonDualDS", "WatermelonDS", ignoreCase = true)
        val separator = Regex("\\s+-\\s+").find(trimmed) ?: return trimmed
        val prefix = trimmed.substring(0, separator.range.first).trim()
        return if (prefixes.any { it.isNotBlank() && it.equals(prefix, ignoreCase = true) })
            trimmed.substring(separator.range.last + 1).trim() else trimmed
    }
    fun withEmulatorNamesOnly(source: String): String? {
        val json = JSONObject(source)
        val platform = json.optJSONObject("platform") ?: return null
        val players = json.optJSONArray("playerList") ?: return null
        val prefixes = listOf(platform.optString("shortname"), platform.optString("uniqueId"), platform.optString("name"))
        var changed = false
        for (index in 0 until players.length()) {
            val player = players.getJSONObject(index)
            val oldName = player.optString("name")
            val newName = emulatorName(oldName, prefixes)
            if (newName != oldName) { player.put("name", newName); changed = true }
        }
        return if (changed) json.toString(2) else null
    }
    private fun parsePlayer(player: JSONObject, system: String, platformRegex: String): EmulatorProfile {
        val tokens = tokenize(player.getString("amStartArguments"))
        var component: ComponentName? = null
        var componentPackage: String? = null
        var action: String? = null
        var data: String? = null; var mime: String? = null; var flags = 0
        val categories=linkedSetOf<String>()
        val arrays=linkedMapOf<String,List<String>>()
        val uris=linkedMapOf<String,String>()
        val integers=linkedMapOf<String,String>()
        val strings = linkedMapOf<String, String>(); val typed = linkedMapOf<String, Any>()
        var index = 0
        fun next(): String { require(index < tokens.size) { "Missing argument value" }; return tokens[index++] }
        fun value(): String = next().also { require(!it.startsWith("--")) { "Missing argument value" } }
        while (index < tokens.size) {
            when (val option = next()) {
                "-n", "--component" -> {
                    val flattened = value(); val parts = flattened.split('/')
                    require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Invalid component" }
                    componentPackage=parts[0]
                    component = ComponentName(parts[0], if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1])
                }
                "-a", "--action" -> action = value()
                "-c", "--category" -> categories.add(value())
                "-d", "--data" -> data = value()
                "-t", "--type" -> mime = value()
                "--esa" -> { val key=value();arrays[key]=next().split(',') }
                "--eu" -> { val key=value();uris[key]=value() }
                "-e", "--es" -> {
                    val key = value()
                    strings[key] = if (index == tokens.size || tokens[index].startsWith("-")) "" else next()
                }
                "--ez" -> { val key = value(); typed[key] = when (value().lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> error("Invalid boolean extra") } }
                "--ei" -> { val key = value();val number=value();if ('{' in number) integers[key]=number else typed[key] = java.lang.Integer.decode(number) }
                "--el" -> { val key = value(); typed[key] = java.lang.Long.decode(value()) }
                "--ef" -> { val key = value(); typed[key] = value().toFloat() }
                "--activity-clear-top" -> flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP
                "--activity-clear-task" -> flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
                "--activity-new-task" -> flags = flags or Intent.FLAG_ACTIVITY_NEW_TASK
                "--activity-single-top" -> flags = flags or Intent.FLAG_ACTIVITY_SINGLE_TOP
                "--activity-no-history" -> flags = flags or Intent.FLAG_ACTIVITY_NO_HISTORY
                "--grant-read-uri-permission" -> Unit // Always attached by our launcher.
                else -> error("Unsupported launch argument $option")
            }
        }
        val placeholders = Regex("\\{[^}]+\\}")
        val allowed = setOf("{file.uri}", "{file.documenturi}", "{file.path}", "{file.name}", "{file.basename}") +
            GameLaunchTags.placeholders
        val templates = strings.values + arrays.values.flatten() + uris.values + integers.values + listOfNotNull(data)
        require(templates.any { placeholders.containsMatchIn(it) }) { "Player does not pass a ROM" }
        require(templates.all { text -> placeholders.findAll(text).all { it.value in allowed } }) { "Unsupported placeholder" }
        val regex = player.getString("acceptedFilenameRegex").let {
            when {
                system=="psvita" && templates.any { value -> "{tags.vita_game_id}" in value } -> it.replace("(?:dpt)","(?:dpt|psvita)")
                system=="ps3" && componentPackage=="com.armsx3" -> "(?:$it)|(?:^.*\\.ps3folder$)"
                else -> it
            }
        }
        Regex(regex); if (platformRegex.isNotBlank()) Regex(platformRegex)
        val extensions = systems.first { it.id == system }.extensions.filter { extension ->
            Regex(regex, RegexOption.IGNORE_CASE).matches("game.$extension") &&
                (platformRegex.isBlank() || Regex(platformRegex,RegexOption.IGNORE_CASE).matches("game.$extension"))
        }.toMutableSet()
        // Common extension groups: preserve additional explicitly named formats without admitting arbitrary files.
        Regex("[a-zA-Z0-9]{2,16}").findAll(regex).map { it.value.lowercase() }.filter { ext ->
            Regex(regex, RegexOption.IGNORE_CASE).matches("game.$ext") && (platformRegex.isBlank() || Regex(platformRegex, RegexOption.IGNORE_CASE).matches("game.$ext"))
        }.forEach { extensions.add(it) }
        require(extensions.isNotEmpty()) { "Cannot determine supported ROM extensions" }
        // hakuX launches its emulator in a separate activity/process. Reusing its task
        // leaves the previous disc running instead of booting the newly selected game.
        if(componentPackage in setOf("com.rfandango.haku_x","com.izzy2lost.x1box"))
            flags=flags or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return EmulatorProfile(player.getString("uniqueId"), player.getString("name"), setOf(system), extensions,
            component ?: error("Missing activity component"), action ?: if(data!=null) Intent.ACTION_VIEW else Intent.ACTION_MAIN, strings, true, ScreenTransferCapability.UNSUPPORTED,
            data, mime, typed, flags, if (platformRegex.isBlank()) regex else "(?=$platformRegex)$regex",categories,arrays,uris,integers)
    }
    internal fun tokenize(source: String): List<String> {
        val result = mutableListOf<String>(); val word = StringBuilder()
        var quote: Char? = null; var escaped = false; var started = false
        for (char in source) {
            if (escaped) { word.append(char); escaped = false; started = true }
            else if (char == '\\' && quote != '\'') { escaped = true; started = true }
            else if (quote != null) { if (char == quote) quote = null else word.append(char) }
            else if (char == '\'' || char == '"') { quote = char; started = true }
            else if (char.isWhitespace()) { if (started) { result.add(word.toString()); word.clear(); started = false } }
            else { word.append(char); started = true }
        }
        require(quote == null && !escaped) { "Unclosed quote or escape" }
        if (started) result.add(word.toString())
        return result
    }
}
