package au.orbit.frontend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ClipData
import org.json.JSONObject

data class EmulatorProfile(
    val id: String, val name: String, val systems: Set<String>, val extensions: Set<String>,
    val component: ComponentName, val action: String, val stringExtras: Map<String, String>,
    val launchDisplay: Boolean, val screenTransfer: ScreenTransferCapability,
    val dataTemplate: String? = null, val mimeType: String? = null,
    val typedExtras: Map<String, Any> = emptyMap(), val flags: Int = 0,
    val filenameRegex: String? = null,
    val categories:Set<String> = emptySet(),
    val stringArrayExtras:Map<String,List<String>> = emptyMap(),
    val uriExtras:Map<String,String> = emptyMap(),
    val integerExtras:Map<String,String> = emptyMap()
) {
    fun installed(context: Context): Boolean = runCatching {
        val activity = context.packageManager.getActivityInfo(component, 0)
        activity.enabled && activity.exported && activity.applicationInfo.enabled
    }.getOrDefault(false)
    fun intent(game: Game,context:Context?=null): Intent = Intent(action).setComponent(component).apply {
        filenameRegex?.let { require(Regex(it, RegexOption.IGNORE_CASE).matches(game.fileName)) { "This player does not accept ${game.fileName}" } }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or this@EmulatorProfile.flags)
        this@EmulatorProfile.categories.forEach { addCategory(it) }
        clipData = ClipData.newRawUri("ROM", game.uri)
        fun expand(value: String): String {
            if(value=="file://{file.path}")return RomLaunchUris.fileUri(expand("{file.path}"))
            val template=if("ps3" in systems && this@EmulatorProfile.component.packageName=="com.armsx3" && game.fileName.endsWith(".ps3folder",true))
                value.replace("{file.path}","{tags.ps3folder}") else value
            var result = template.replace("{romUri}", game.uri.toString()).replace("{file.uri}", game.uri.toString()).replace("{file.documenturi}", game.uri.toString())
                .replace("{file.name}", game.fileName).replace("{file.basename}", game.title)
            if(game.directory)result=result.replace("{tags.ps3folder}","{file.path}")
            val descriptorTags = GameLaunchTags.placeholders.filter { it in result }
            if (descriptorTags.isNotEmpty()) {
                val text=context?.contentResolver?.openInputStream(game.uri)?.use { input ->
                    val bytes=ByteArray(8192);val count=input.read(bytes)
                    if(count>0)String(bytes,0,count,Charsets.UTF_8) else ""
                }.orEmpty()
                for (tag in descriptorTags) {
                    result = result.replace(tag, GameLaunchTags.resolve(tag, text, game.fileName))
                }
            }
            if ("{file.path}" in result) {
                require(game.uri.authority == "com.android.externalstorage.documents") { "This player requires a filesystem path. Choose ROMs from internal storage or an SD card." }
                val id = android.provider.DocumentsContract.getDocumentId(game.uri)
                val volume = id.substringBefore(':'); val relative = id.substringAfter(':', "")
                require(relative.isNotBlank() && relative.split('/').none { it == ".." }) { "Cannot resolve ROM path" }
                val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
                result = result.replace("{file.path}", "$base/$relative")
            }
            require(!Regex("\\{[^}]+\\}").containsMatchIn(result)) { "Unsupported launch placeholder" }
            return result
        }
        if (dataTemplate != null) setDataAndType(android.net.Uri.parse(expand(dataTemplate)), mimeType)
        else if (mimeType != null) type = mimeType
        stringExtras.forEach { (key, value) ->
            val resolved=if(key=="Ali.Xanite.extra.SHORTCUT_GAME_RELATIVE_PATH" && value=="{file.name}" &&
                "xbox" in systems && component?.packageName=="Ali.Xanite.green" && context!=null &&
                game.uri.authority=="com.android.externalstorage.documents") {
                val document=android.provider.DocumentsContract.getDocumentId(game.uri)
                val folders=LibraryRepository(context).folders(au.orbit.frontend.systems.first { it.id=="xbox" }).mapNotNull { folder ->
                    runCatching {
                        if(android.provider.DocumentsContract.isDocumentUri(context,folder))android.provider.DocumentsContract.getDocumentId(folder)
                        else android.provider.DocumentsContract.getTreeDocumentId(folder)
                    }.getOrNull()
                }
                RomLaunchUris.relativeDocumentPath(document,folders,game.fileName)
            } else expand(value)
            putExtra(key, if (key == "LIBRETRO") retroArchCorePath(this@EmulatorProfile.component.packageName, resolved) else resolved)
        }
        stringArrayExtras.forEach { (key,values) -> putExtra(key,values.map(::expand).toTypedArray()) }
        uriExtras.forEach { (key,value) -> putExtra(key,android.net.Uri.parse(expand(value))) }
        integerExtras.forEach { (key,value) -> putExtra(key,java.lang.Integer.decode(expand(value))) }
        typedExtras.forEach { (key, value) -> when (value) { is Boolean -> putExtra(key, value); is Int -> putExtra(key, value); is Long -> putExtra(key, value); is Float -> putExtra(key, value) } }
    }
}

private val retroArchPackages = setOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32")
private val retroArchCoreName = Regex("[a-zA-Z0-9_]+")

internal fun retroArchCorePath(packageName: String, core: String): String =
    // RetroArch resolves Azahar by name. On Thor the absolute path hangs at startup.
    if (packageName in retroArchPackages && core != "azahar" && retroArchCoreName.matches(core))
        "/data/data/$packageName/cores/${core}_libretro_android.so"
    else core

object GameLaunchTags {
    private val gameNativeTags = setOf("gog", "epicgame", "customgame", "amazon")
    internal val placeholders = (gameNativeTags + setOf(
        "vita_game_id", "ps3folder", "steamappid", "scummvm_id", "localgameid", "ps3_title_id"
    )).map { "{tags.$it}" }.toSet()

    internal fun resolve(placeholder: String, text: String, fileName: String): String =
        when (val tag = placeholder.removePrefix("{tags.").removeSuffix("}")) {
            "vita_game_id" -> vitaId(text, fileName)
            "ps3folder" -> ps3Folder(text)
            "steamappid" -> steamAppId(text, fileName)
            "scummvm_id" -> scummVmId(text)
            "localgameid" -> localGameId(text)
            "ps3_title_id" -> descriptorId(text, tag, "PS3 title ID", "ps3titleid")
            in gameNativeTags -> {
                val id = descriptorId(text, tag, "GameNative game ID", tag)
                requireNotNull(id.toIntOrNull()) { "Invalid GameNative game ID in .$tag file." }.toString()
            }
            else -> error("Unsupported launch placeholder $placeholder")
        }

    private fun descriptorId(text:String,tag:String,label:String,extension:String):String {
        val lines=text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith(';') }.toList()
        val marker="[$tag]"
        val index=lines.indexOfFirst { it.equals(marker,true) || it.startsWith("$marker ",true) || it.startsWith("$marker\t",true) }
        val tagged=if(index>=0)lines[index].substring(marker.length).trim().ifEmpty { lines.getOrNull(index+1).orEmpty() } else null
        val value=tagged ?: lines.singleOrNull()
        require(!value.isNullOrBlank() && !value.startsWith('[') && value.none { it.isISOControl() || it in "{}" }) {
            "$label missing or invalid. Use a .$extension file containing $marker followed by the game's ID."
        }
        return value
    }
    fun scummVmId(text:String)=descriptorId(text,"scummvm_id","ScummVM ID","dpt")
    fun localGameId(text:String)=descriptorId(text,"localgameid","GameHub local game ID","localgameid")
    fun steamAppId(text:String,fileName:String):String {
        val tag=Regex("(?im)^\\s*\\[steamappid\\]\\s*([0-9]+)\\s*$").find(text)?.groupValues?.get(1)
        val plain=text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList().singleOrNull()
        val id=tag ?: plain?.takeIf { it.matches(Regex("[0-9]+")) }
            ?: fileName.substringBeforeLast('.').takeIf { it.matches(Regex("[0-9]+")) }
        require(id!=null && id.toIntOrNull()?.let { it>0 }==true) {
            "Steam app ID missing or invalid. Use a .steamappid file containing [steamappid] followed by the game's numeric Steam app ID."
        }
        return id
    }
    fun ps3Folder(text:String):String {
        val path=Regex("(?im)^\\s*\\[ps3folder\\]\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
            ?: text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }.orEmpty()
        require((path.startsWith('/') || path.startsWith("content://")) && '\u0000' !in path) {
            "PS3 folder path missing. Use a .ps3folder descriptor containing [ps3folder] followed by the game's folder path."
        }
        return path
    }
    fun vitaId(text:String,fileName:String):String {
        val tag=Regex("(?im)^\\s*\\[vita_game_id\\]\\s*([a-z0-9]{9})\\s*$").find(text)?.groupValues?.get(1)
        val id=tag ?: Regex("(?i)\\b[A-Z]{4}[0-9]{5}\\b").find(text)?.value
            ?: Regex("(?i)\\b[A-Z]{4}[0-9]{5}\\b").find(fileName)?.value
        return requireNotNull(id) { "Vita game ID missing. Use a .dpt file containing [vita_game_id] followed by the installed game's title ID." }.uppercase()
    }
}

class EmulatorProfileRepository(private val context: Context) {
    private val snapshot = snapshot(context)
    val errors get() = snapshot.second
    val profiles get() = snapshot.first
    fun forSystem(system: SystemSpec) = profiles.filter { system.id in it.systems }
    fun selected(system: SystemSpec): EmulatorProfile? {
        val saved = UserDataStore.string(context, "emulators", system.id)
        return forSystem(system).find { it.id == saved } ?: if (system.id == "psx" && saved == "duckstation")
            forSystem(system).firstOrNull { it.component.packageName == "com.github.stenzek.duckstation" && it.stringExtras["bootPath"] == "{file.uri}" } else null
    }
    fun select(system: SystemSpec, profile: EmulatorProfile?) {
        UserDataStore.put(context, "emulators", system.id, profile?.id)
    }
    fun gameOverride(system: SystemSpec, game: Game): EmulatorProfile? {
        val saved = UserDataStore.string(context, "gameEmulators", gameKey(system.id, game.uri.toString()))
        return forSystem(system).find { it.id == saved }
    }
    fun selectedForGame(system: SystemSpec, game: Game):EmulatorProfile? {
        val override=gameOverride(system,game)
        val chosen=override ?: selected(system)
        return if(override==null && game.directory && system.id=="ps3" && chosen!=null)
            forSystem(system).firstOrNull { it.component==chosen.component && "ps3folder" in it.extensions } ?: chosen
        else chosen
    }
    fun selectForGame(system: SystemSpec, game: Game, profile: EmulatorProfile?) {
        require(profile == null || system.id in profile.systems) { "Emulator does not support this system" }
        UserDataStore.put(context, "gameEmulators", gameKey(system.id, game.uri.toString()), profile?.id)
    }
    companion object {
        internal fun gameKey(system: String, uri: String): String = "$system:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        @Volatile private var cache: Pair<List<EmulatorProfile>, List<String>>? = null
        fun invalidate() { cache = null;UserDataStore.invalidateProfileSources() }
        @Synchronized private fun snapshot(context: Context): Pair<List<EmulatorProfile>, List<String>> {
            cache?.let { return it }
            val errors = mutableListOf<String>()
            val profiles = linkedMapOf<String, EmulatorProfile>()
            val bundledSources=mutableMapOf<String,String>()
            fun key(profile: EmulatorProfile) = "${profile.systems.sorted().joinToString()}::${profile.id}"
            fun hint(name: String): String? = name.removeSuffix(".json").let { if (it == "n3ds") "3ds" else it }.takeIf { id -> systems.any { it.id == id } }
            context.assets.list("systems").orEmpty().filter { it.endsWith(".json") }.sorted().forEach { name ->
                runCatching { context.assets.open("systems/$name").bufferedReader().use {
                    val source=it.readText();bundledSources[name]=source;SharedSystemProfiles.parse(source, errors, hint(name))
                } }
                    .onSuccess { list -> list.forEach { profiles[key(it)] = it } }.onFailure { errors.add("$name: ${it.message}") }
            }
            runCatching { UserDataStore.profileSources(context) }.onFailure { errors.add(it.message ?: "User profile folder unavailable") }
                .getOrDefault(emptyList()).forEach { (name, source) ->
                    if(bundledSources[name]==source)return@forEach
                    runCatching { SharedSystemProfiles.parse(source, errors, hint(name)) }.onSuccess { list -> list.forEach { profiles[key(it)] = it } }.onFailure { errors.add("$name: ${it.message}") }
                }
            return (profiles.values.toList() to errors.toList()).also { cache = it }
        }
        fun parse(source: String): EmulatorProfile {
            val json = JSONObject(source)
            require(json.getInt("schemaVersion") == 1) { "Unsupported profile schema" }
            fun strings(key: String): Set<String> {
                val array = json.getJSONArray(key)
                return (0 until array.length()).map { array.getString(it).also { value -> require(value.isNotBlank()) } }.toSet().also { require(it.isNotEmpty()) }
            }
            fun required(key: String) = json.getString(key).also { require(it.isNotBlank()) { "Missing $key" } }
            val extras = json.getJSONObject("stringExtras")
            val values = extras.keys().asSequence().associateWith { key ->
                val value = extras.get(key)
                require(value is String) { "Extra $key must be a string" }
                require(!Regex("\\{[^}]+\\}").findAll(value).any { it.value != "{romUri}" }) { "Unknown placeholder in $key" }
                value
            }
            require(values.values.any { "{romUri}" in it }) { "Profile must pass the ROM URI" }
            val capabilities = json.getJSONObject("capabilities")
            val transfer = when (capabilities.getString("screenTransfer")) {
                "unsupported" -> ScreenTransferCapability.UNSUPPORTED
                // Live transfer requires an implemented Kotlin adapter, not a JSON declaration.
                else -> error("Screen transfer integration is not implemented")
            }
            return EmulatorProfile(required("id"), required("name"), strings("systems"), strings("extensions"),
                ComponentName(required("packageName"), required("activityName")), required("action"), values,
                capabilities.getBoolean("launchDisplay"), transfer)
        }
    }
}
