package au.orbit.frontend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONObject
import java.io.File

/** All portable frontend data lives below one root. Only its URI and recovery copy stay private. */
object UserDataStore {
    private val forceProfileSources=java.util.concurrent.atomic.AtomicBoolean(false)
    fun invalidateProfileSources() { forceProfileSources.set(true) }
    private val mediaDirectories=android.util.LruCache<String,DataFolder>(256)
    private val mediaRecordCache=mutableMapOf<String,List<String>>()
    private val scraperRecords=listOf("screenscraper.json","neoassets.json","thegamesdb.json","steamgriddb.json")
    private fun mediaAliases(type:String)=when(type) {
        "covers" -> listOf("covers","box2d","boxart")
        "marquees" -> listOf("marquees","wheels","logos")
        "fanart" -> listOf("fanart","fanarts")
        "physicalmedia" -> listOf("physicalmedia","discs")
        else -> listOf(type)
    }
    @Synchronized fun mediaTypeFolder(context:Context,system:String,type:String,sourcePath:List<String> = emptyList()):String {
        initialize(context)
        val aliases=mediaAliases(type)
        return aliases.firstOrNull { resolveMediaFolder(listOf("media",system,it))!=null }
            ?: sourcePath.dropLast(1).asReversed().firstOrNull { it in aliases } ?: type
    }
    private fun mediaFolder(context:Context,group:String):DataFolder {
        initialize(context);check(accessError==null) { accessError!! }
        var folder=root!!.directory(if(MediaLayout.isRecordGroup(group))"media-index" else "media",true)!!
        for(part in group.split('/')) {
            require(part.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid media folder" }
            folder=folder.directory(part,true)!!
        }
        return folder
    }
    fun scrapedRecordNames(context:Context,group:String):List<String> =
        scraperRecords+importedRecords(context,group)

    @Synchronized private fun importedRecords(context:Context,group:String):List<String> {
        val key=location(context)+"/"+group
        return mediaRecordCache.getOrPut(key) {
            runCatching {
                initialize(context)
                val entries=listOf("media-index","media").flatMap { directory ->
                    var folder=root!!.directory(directory,false)
                    for(part in group.split('/'))folder=folder?.directory(part,false)
                    folder?.entries().orEmpty()
                }
                entries.filter { !it.second && it.first.startsWith("import-") && it.first.endsWith(".json") }.map { it.first }.sortedDescending()
            }.getOrDefault(emptyList())
        }
    }
    /** Keeps existing destinations and verifies each imported file by streaming it back. */
    fun importMediaFile(context:Context,group:String,name:String,source:File,hash:String):Uri {
        require(MediaLayout.validPart(name) && !name.startsWith('.'))
        val folder=synchronized(this) { mediaFolder(context,group) }
        if(folder.fileUri(name)==null)folder.writeFrom(name,source)
        val uri=folder.fileUri(name) ?: error("Imported file is missing")
        val digest=context.contentResolver.openInputStream(uri)?.use { MediaTransfer.sha256(it) } ?: error("Cannot verify imported file")
        check(digest==hash) { "A different file already exists at media/$group/$name; existing artwork and source kept" }
        synchronized(this) { mediaRecordCache.remove(location(context)+"/"+group);mediaDirectories.evictAll() }
        return uri
    }
    @Synchronized fun overlapsUserData(context:Context,uri:Uri):Boolean {
        initialize(context)
        val current=(root as? TreeFolder)?.uri ?: return false
        if(current.authority!=uri.authority)return false
        fun id(value:Uri)=if(Docs.isDocumentUri(context,value))Docs.getDocumentId(value) else Docs.getTreeDocumentId(value)
        val left=id(current).trimEnd('/');val right=id(uri).trimEnd('/')
        return MediaImportPlan.documentPathsOverlap(left,right)
    }
    private var lastAccessAttempt=0L
    @Volatile private var state: JSONObject? = null
    private var root: DataFolder? = null
    var accessError: String? = null
        private set
    private fun bootstrap(context: Context) = context.getSharedPreferences("data_location", 0)
    private fun recovery(context: Context) = File(context.filesDir, "settings-recovery.json")
    @Synchronized fun initialize(context: Context) {
        if (state != null && accessError==null) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(state!=null && now-lastAccessAttempt<2000)return
        lastAccessAttempt=now
        val uri = bootstrap(context).getString("uri", null)
        root = if (uri == null) LocalFolder(File(context.filesDir, "userdata").apply { mkdirs() }) else TreeFolder(context.applicationContext, Uri.parse(uri))
        try {
            val bytes = root!!.read("settings.json")
            state = if (bytes == null) {
                check(uri == null) { "The selected user data folder is missing settings.json." }
                JSONObject().apply {
                    put("schemaVersion", 1)
                    for (section in listOf("library", "emulators", "settings")) {
                        put(section, JSONObject(context.getSharedPreferences(section, 0).all))
                    }
                }
            } else parseSettings(bytes)
            if (bytes == null) root!!.write("settings.json", state!!.toString(2).toByteArray())
            recovery(context).writeText(state.toString())
            seedProfiles(context, root!!)
            accessError=null
            mediaRecordCache.clear();mediaDirectories.evictAll()
        } catch (error: Exception) {
            accessError = "User data folder unavailable: ${error.message}. Choose a new folder to restore access."
            state = if (recovery(context).exists()) parseSettings(recovery(context).readBytes()) else JSONObject().put("schemaVersion", 1)
        }
    }
    private fun parseSettings(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8)).also {
        require(it.getInt("schemaVersion") == 1) { "Unsupported settings version" }
    }
    // Settings are published as complete snapshots. Reading them must never wait for
    // media writes, theme restoration or migration holding the storage monitor.
    private fun settingsSnapshot(context:Context):JSONObject {
        state?.let { return it }
        initialize(context)
        return state!!
    }
    fun string(context: Context, section: String, key: String): String? {
        return settingsSnapshot(context).optJSONObject(section)?.optString(key)?.takeIf { it.isNotBlank() }
    }
    fun section(context: Context, section: String): Map<String, String> {
        val values = settingsSnapshot(context).optJSONObject(section) ?: return emptyMap()
        return values.keys().asSequence().mapNotNull { key -> values.optString(key).takeIf(String::isNotBlank)?.let { key to it } }.toMap()
    }
    fun strings(context: Context, section: String, key: String): List<String> {
        return decodePaths(settingsSnapshot(context).optJSONObject(section)?.opt(key))
    }
    internal fun decodePaths(value: Any?): List<String> = when (value) {
        is org.json.JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.distinct()
        is String -> listOf(value).filter(String::isNotBlank)
        else -> emptyList()
    }
    @Synchronized fun readLibrary(context: Context, id: String): String? {
        initialize(context)
        return root!!.directory("library", false)?.read("$id.json")?.toString(Charsets.UTF_8)
    }
    @Synchronized fun writeLibrary(context: Context, id: String, source: String) {
        initialize(context); check(accessError == null) { accessError!! }
        root!!.directory("library", true)!!.write("$id.json", source.toByteArray())
    }
    @Synchronized fun writeScraped(context: Context, group: String, name: String, bytes: ByteArray) {
        initialize(context); check(accessError == null) { accessError!! }
        val parts = group.split('/')
        require(parts.all { it.matches(Regex("[a-zA-Z0-9_-]+")) } && MediaLayout.validPart(name))
        val base = if (MediaLayout.isRecordGroup(group)) "media-index" else "media"
        try {
            requireNotNull(resolveMediaFolder(listOf(base) + parts, create = true)).write(name, bytes)
        } catch (error: Exception) {
            mediaDirectories.evictAll()
            throw error
        }
    }

    /** Deletes only files referenced by this game's records, preserving files shared with other games. */
    @Synchronized
    internal fun deleteGameMedia(context: Context, group: String, types: Set<String>?) {
        initialize(context)
        check(accessError == null) { accessError!! }
        require(MediaLayout.isRecordGroup(group) && group.split('/').all(MediaLayout::validPart))
        val system = group.substringBefore('/')
        val game = group.substringAfter('/')
        val records = listOf("media-index", "media").mapNotNull { base ->
            resolveMediaFolder(listOf(base, system, game))
        }.flatMap { folder -> mediaRecords(folder).map { (name, record) -> Triple(folder, name, record) } }
        val candidates = linkedSetOf<List<String>>()
        val stillUsed = mutableSetOf<List<String>>()
        for ((_, _, record) in records) {
            for (file in ScraperClient.objects(record.opt("files"))) {
                val path = MediaLayout.gameMediaPath(group, file.optString("file")) ?: continue
                if (types == null || file.optString("type") in types) candidates.add(path) else stillUsed.add(path)
            }
        }
        val mayBeShared = candidates.filter { it.getOrNull(2) != game }.toMutableSet()
        if (mayBeShared.isNotEmpty()) {
            for (base in listOf("media-index", "media")) {
                val parent = resolveMediaFolder(listOf(base, system)) ?: continue
                for ((other, directory) in parent.entries()) {
                    if (!directory || other == game || !MediaLayout.isRecordGroup("$system/$other")) continue
                    val folder = parent.directory(other, false) ?: continue
                    for ((_, record) in mediaRecords(folder)) {
                        for (file in ScraperClient.objects(record.opt("files"))) {
                            val path = MediaLayout.gameMediaPath("$system/$other", file.optString("file"))
                            if (path != null && mayBeShared.remove(path)) stillUsed.add(path)
                        }
                    }
                    if (mayBeShared.isEmpty()) break
                }
                if (mayBeShared.isEmpty()) break
            }
        }
        val failures = mutableSetOf<List<String>>()
        for (path in candidates - stillUsed) {
            val success = runCatching {
                val folder = resolveMediaFolder(path.dropLast(1))
                folder == null || folder.fileUri(path.last()) == null || folder.deleteFile(path.last())
            }.getOrDefault(false)
            if (!success) failures.add(path)
        }
        for ((folder, name, record) in records) {
            val files = ScraperClient.objects(record.opt("files"))
            val remaining = files.filter { file ->
                (types != null && file.optString("type") !in types) ||
                    MediaLayout.gameMediaPath(group, file.optString("file")) in failures
            }
            if (files.size != remaining.size) {
                record.put("files", org.json.JSONArray(remaining))
                folder.write(name, record.toString(2).toByteArray())
            }
        }
        check(failures.isEmpty()) { "Could not delete ${failures.size} media file(s). Check folder access and try again." }
    }

    private fun mediaRecords(folder: DataFolder): List<Pair<String, JSONObject>> = folder.entries().mapNotNull { (name, directory) ->
        if (directory || (name !in scraperRecords && !(name.startsWith("import-") && name.endsWith(".json")))) {
            return@mapNotNull null
        }
        val bytes = folder.read(name) ?: return@mapNotNull null
        name to JSONObject(bytes.toString(Charsets.UTF_8))
    }

    @Synchronized
    internal fun deleteGameArtworkFile(context: Context, path: String) {
        initialize(context)
        check(accessError == null) { accessError!! }
        require(OrbitThemes.validArtworkPath(path))
        val otherArtwork = state?.optJSONObject("gameArtwork")
        if (otherArtwork != null && otherArtwork.keys().asSequence().count { otherArtwork.optString(it) == path } > 1) return
        val usedByTheme = nativeThemeSources(context).any { source ->
            val theme = OrbitThemes.parse(source)
            theme.artwork.any { it.file == path } || path in theme.systemLogos.values ||
                theme.systemHeroes.values.any { path in it.values } || theme.systemImages.values.any { path in it.values }
        }
        if (usedByTheme) return
        val folder = root!!.directory("themes", false)?.directory("native", false)?.directory("artwork", false) ?: return
        val name = path.removePrefix("artwork/")
        check(folder.fileUri(name) == null || folder.deleteFile(name)) { "Could not delete custom artwork." }
    }

    private fun resolveMediaFolder(parts:List<String>, create: Boolean = false):DataFolder? {
        val key=parts.joinToString("/")
        mediaDirectories.get(key)?.let { return it }
        var folder:DataFolder?=root
        for(index in parts.indices) {
            val prefix=parts.take(index+1).joinToString("/")
            folder=mediaDirectories.get(prefix) ?: folder?.directory(parts[index],create)?.also { mediaDirectories.put(prefix,it) }
            if(folder==null)return null
        }
        return folder
    }

    /** A sound the user placed at sounds/<system id>.<ogg|mp3|wav|m4a|flac> in their data folder. */
    @Synchronized fun systemSoundUri(context: Context, systemId: String): Uri? {
        if (!systemId.matches(Regex("[a-z0-9_-]+"))) return null
        initialize(context)
        val folder = root?.directory("sounds", false) ?: return null
        return listOf("ogg", "mp3", "wav", "m4a", "flac").firstNotNullOfOrNull { folder.fileUri("$systemId.$it") }
    }

    internal fun readScrapedRecords(context:Context,group:String):Sequence<ByteArray> = sequence {
        val parts=group.split('/')
        require(parts.all(MediaLayout::validPart))
        val folders=synchronized(this@UserDataStore) {
            initialize(context)
            val roots=if(MediaLayout.isRecordGroup(group))listOf("media-index","media") else listOf("media")
            roots.mapNotNull { resolveMediaFolder(listOf(it)+parts) }
        }
        if(folders.isEmpty())return@sequence
        val names=scraperRecords.asSequence()+sequence { yieldAll(importedRecords(context,group)) }
        for(name in names) {
            val candidates=synchronized(this@UserDataStore) { folders.mapNotNull { it.fileUri(name) } }
            val bytes=readMediaBytes(context,candidates)
            if(bytes!=null)yield(bytes)
        }
    }

    fun readScraped(context: Context, group: String, path: String): ByteArray? {
        val candidates=synchronized(this) {
            initialize(context)
            val parts=(if(path.startsWith("@/"))path.removePrefix("@/") else "$group/$path").split('/')
            require(parts.all(MediaLayout::validPart))
            val roots=if(!path.startsWith("@/") && MediaLayout.isRecordGroup(group) && !path.contains('/'))listOf("media-index","media") else listOf("media")
            roots.mapNotNull { directory ->
                resolveMediaFolder(listOf(directory)+parts.dropLast(1))?.fileUri(parts.last())
            }
        }
        return readMediaBytes(context,candidates)
    }
    // File reads must not hold the settings monitor used by controller navigation.
    private fun readMediaBytes(context:Context,candidates:List<Uri>):ByteArray? =
        candidates.firstNotNullOfOrNull { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
    /** Resolve videos without reading the movie or repeatedly enumerating media directories. */
    @Synchronized fun scrapedUri(context: Context, group: String, path: String): Uri? {
        initialize(context)
        val parts=(if(path.startsWith("@/"))path.removePrefix("@/") else "$group/$path").split('/')
        require(parts.all(MediaLayout::validPart))
        return resolveMediaFolder(listOf("media")+parts.dropLast(1))?.fileUri(parts.last())
    }
    fun cachedInt(key:String,fallback:Int):Int = state?.optJSONObject("settings")?.optInt(key,fallback) ?: fallback
    fun cachedString(section:String,key:String):String? = state?.optJSONObject(section)?.optString(key)?.takeIf { it.isNotBlank() }
    fun int(context: Context, key: String, fallback: Int): Int {
        return settingsSnapshot(context).optJSONObject("settings")?.optInt(key, fallback) ?: fallback
    }
    @Synchronized fun put(context: Context, section: String, key: String, value: Any?) {
        initialize(context)
        check(accessError == null) { accessError!! }
        val next = JSONObject(state.toString())
        val values = next.optJSONObject(section) ?: JSONObject().also { next.put(section, it) }
        if (value == null) values.remove(key) else values.put(key, value)
        val bytes = next.toString(2).toByteArray()
        val old = root!!.read("settings.json")
        if (old != null) root!!.write("settings-backup.json", old)
        try {
            root!!.write("settings.json", bytes)
            check(root!!.read("settings.json")!!.contentEquals(bytes)) { "Could not verify saved settings" }
        } catch (error: Exception) {
            if (old != null) runCatching { root!!.write("settings.json", old) }
            throw error
        }
        recovery(context).writeBytes(bytes); state = next
    }
    fun profileSources(context: Context): List<Pair<String, String>> {
        val directory=synchronized(this) {
            initialize(context)
            check(accessError == null) { accessError!! }
            root!!.directory("systems",false)
        } ?: return emptyList()
        val cache=ProfileSourceCache(File(context.cacheDir,"emulator-profile-sources"))
        val force=forceProfileSources.getAndSet(false)
        val namespace=location(context)
        directory.prepareRevisionListing()
        return directory.entries().filter { !it.second && it.first.endsWith(".json", true) }.sortedBy { it.first }.map { (name,_) ->
            name to cache.read("$namespace/$name",directory.revision(name),force) { directory.read(name)!!.toString(Charsets.UTF_8) }
        }
    }
    @Synchronized fun importSystem(context: Context, source: String): String {
        initialize(context); check(accessError == null) { accessError!! }
        val warnings = mutableListOf<String>()
        val profiles = SharedSystemProfiles.parse(source, warnings)
        val name = "import-${System.currentTimeMillis()}.json"
        val directory = root!!.directory("systems", true)!!
        val bytes = source.toByteArray()
        directory.write(name, bytes)
        check(directory.read(name)!!.contentEquals(bytes)) { "Import verification failed" }
        EmulatorProfileRepository.invalidate()
        return "Imported ${profiles.size} player(s) into systems/$name${if (warnings.isEmpty()) "" else "\n\nCompatibility notes:\n${warnings.joinToString("\n")}"}"
    }
    @Synchronized fun nativeThemeSources(context:Context):List<String> {
        initialize(context)
        val folder=root!!.directory("themes",false)?.directory("native",false) ?: return emptyList()
        return folder.entries().filter { !it.second && it.first.endsWith(".json",true) }.sortedBy { it.first }
            .mapNotNull { folder.read(it.first)?.toString(Charsets.UTF_8) }
    }
    @Synchronized fun saveNativeTheme(context:Context,id:String,source:String) {
        initialize(context);check(accessError==null) { accessError!! }
        require(OrbitThemes.parse(source).id==id)
        val folder=root!!.directory("themes",true)!!.directory("native",true)!!
        val bytes=source.toByteArray()
        folder.write("$id.json",bytes)
        check(folder.read("$id.json")!!.contentEquals(bytes)) { "Could not verify theme save" }
    }
    @Synchronized fun saveNativeArtwork(context:Context,path:String,bytes:ByteArray) {
        initialize(context);check(accessError==null) { accessError!! };require(OrbitThemes.validArtworkPath(path))
        val folder=root!!.directory("themes",true)!!.directory("native",true)!!.directory("artwork",true)!!
        val name=path.removePrefix("artwork/")
        folder.write(name,bytes)
        check(folder.read(name)!!.contentEquals(bytes)) { "Could not verify artwork save" }
    }
    @Synchronized fun readNativeArtwork(context:Context,path:String):ByteArray? {
        initialize(context);require(OrbitThemes.validArtworkPath(path))
        return root!!.directory("themes",false)?.directory("native",false)?.directory("artwork",false)?.read(path.removePrefix("artwork/"))
    }
    @Synchronized fun deleteNativeTheme(context:Context,id:String) {
        initialize(context);check(accessError==null) { accessError!! }
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,79}")) && !OrbitThemes.isBuiltIn(id)) { "Built-in Orbit themes cannot be deleted" }
        val folder=root!!.directory("themes",false)?.directory("native",false) ?: error("Theme folder is missing")
        val source=folder.read("$id.json") ?: error("Theme file is missing")
        require(OrbitThemes.parse(source.toString(Charsets.UTF_8)).id==id) { "Theme file does not match this theme" }
        val active=cachedString("settings","nativeTheme")==id
        if(active)put(context,"settings","nativeTheme","midnight")
        try { check(folder.deleteFile("$id.json")) { "Could not delete theme" } }
        catch(error:Exception) { if(active)runCatching { put(context,"settings","nativeTheme",id) };throw error }
    }
    @Synchronized fun themeIndexes(context:Context):List<Pair<String,String>> {
        initialize(context)
        val themes=root!!.directory("themes",false) ?: return emptyList()
        return themes.entries().filter { it.second && it.first.matches(Regex("theme-[a-f0-9-]+")) }.mapNotNull { (id,_) ->
            themes.directory(id,false)?.read("orbit-theme.json")?.toString(Charsets.UTF_8)?.let { id to it }
        }
    }
    fun saveTheme(context:Context,id:String,source:File,progress:(Int,Int)->Unit={_,_->}) {
        val target=synchronized(this) {
            initialize(context);check(accessError==null) { accessError!! }
            require(id.matches(Regex("theme-[a-f0-9-]+")))
            root!!.directory("themes",true)!!.directory(id,true)!!
        }
        val folders=mutableMapOf("" to target)
        val files=source.walkTopDown().filter { it.isFile }.sortedBy { it.name=="orbit-theme.json" }.toList()
        files.forEachIndexed { index,file ->
            val parts=file.relativeTo(source).invariantSeparatorsPath.split('/')
            require(parts.none { it in listOf(".","..","") || '/' in it || '\\' in it })
            var folder=target
            var path=""
            for(part in parts.dropLast(1)) {
                path=if(path.isEmpty())part else "$path/$part"
                folder=folders[path] ?: folder.directory(part,true)!!.also { folders[path]=it }
            }
            folder.write(parts.last(),file.readBytes())
            progress(index+1,files.size)
        }
    }
    @Synchronized fun restoreTheme(context:Context,id:String,paths:List<String>,destination:File) {
        initialize(context)
        require(id.matches(Regex("theme-[a-f0-9-]+")))
        val source=root!!.directory("themes",false)?.directory(id,false) ?: error("Theme folder is missing")
        for(path in paths+"orbit-theme.json") {
            require(EsDeXml.resolve("theme.xml",path)==path) { "Invalid theme asset path" }
            val file=File(destination,path)
            if(file.isFile)continue
            var folder=source
            val parts=path.split('/')
            for(part in parts.dropLast(1))folder=folder.directory(part,false) ?: error("Missing theme folder")
            file.parentFile!!.mkdirs()
            if(!file.isFile)file.writeBytes(folder.read(parts.last()) ?: error("Missing theme asset: $path"))
        }
    }
    fun location(context: Context): String {
        if(state==null || accessError!=null)initialize(context)
        return bootstrap(context).getString("uri", null) ?: "Internal app storage"
    }
    /** Use the chosen folder directly, verify copied files, then commit the new root. */
    @Synchronized fun migrate(context: Context, parentUri: Uri): String {
        initialize(context)
        val parent = TreeFolder(context.applicationContext, parentUri)
        val destination=parent
        val name=Docs.getTreeDocumentId(parentUri).substringAfterLast('/').substringAfterLast(':')
        val current=root as? TreeFolder
        val currentId=current?.let { if(Docs.isDocumentUri(context,it.uri))Docs.getDocumentId(it.uri) else Docs.getTreeDocumentId(it.uri) }
        val selectedId=Docs.getTreeDocumentId(parentUri)
        if(current?.uri?.authority==parentUri.authority && currentId==selectedId)return name
        check(destination.read("settings.json")==null) { "This folder already contains Orbit data. Select an empty folder to avoid overwriting it." }
        if (accessError == null) copy(root!!, destination, destination as TreeFolder)
        else seedProfiles(context, destination) // Recover cached settings when the old folder is inaccessible.
        val bytes = state!!.toString(2).toByteArray()
        destination.write("settings.json", bytes)
        check(destination.read("settings.json")!!.contentEquals(bytes)) { "Settings verification failed" }
        seedProfiles(context, destination)
        val uri = (destination as TreeFolder).uri
        check(bootstrap(context).edit().putString("uri", uri.toString()).commit()) { "Could not save folder selection" }
        root = destination; accessError = null;mediaRecordCache.clear();mediaDirectories.evictAll()
        EmulatorProfileRepository.invalidate()
        recovery(context).writeBytes(bytes)
        return name
    }
    private fun copy(source: DataFolder, target: DataFolder, excluded: TreeFolder) {
        for ((name, directory) in source.entries()) {
            if (directory) {
                val child = source.directory(name, false)!!
                if (child is TreeFolder && child.uri.authority == excluded.uri.authority && Docs.getDocumentId(child.uri) == (if(excluded.uri.pathSegments.contains("document"))Docs.getDocumentId(excluded.uri) else Docs.getTreeDocumentId(excluded.uri))) continue
                copy(child, target.directory(name, true)!!, excluded)
            }
            else {
                val bytes = source.read(name) ?: error("Unable to read $name")
                val existing=target.read(name)
                check(existing==null || existing.contentEquals(bytes)) { "Destination already contains a different $name; existing files kept" }
                if(existing==null)target.write(name, bytes)
                check(target.read(name)!!.contentEquals(bytes)) { "Copy verification failed for $name" }
            }
        }
    }
    private fun seedProfiles(context: Context, target: DataFolder) {
        val profiles = target.directory("systems", true)!!
        val upgradeProfiles = target.read("daijisho-profile-version.json") == null
        val existingNames=profiles.entries().filter { !it.second }.map { it.first }.toSet()
        context.assets.list("systems").orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            if (name !in existingNames) profiles.write(name, context.assets.open("systems/$name").use { it.readBytes() })
            else if (upgradeProfiles) {
                val existing=profiles.read(name) ?: error("Cannot read existing profile $name")
                val backup = target.directory("system-profile-backup", true)!!
                if (backup.read(name) == null) backup.write(name, existing)
                profiles.write(name, context.assets.open("systems/$name").use { it.readBytes() })
            }
        }
        if (upgradeProfiles) {
            profiles.read("duckstation.json")?.let { bytes ->
                val backup = target.directory("system-profile-backup", true)!!
                if (backup.read("duckstation.json") == null) backup.write("duckstation.json", bytes)
                profiles.write("duckstation.json", context.assets.open("systems/psx.json").use { it.readBytes() })
            }
            target.write("daijisho-profile-version.json", "{\"version\":1}".toByteArray())
        }
        if (target.read("cocoon-profile-version.json") == null) {
            val backup = target.directory("cocoon-profile-backup", true)!!
            context.assets.list("systems").orEmpty().filter { it.endsWith(".json") }.forEach { name ->
                profiles.read(name)?.let { existing -> if (backup.read(name) == null) backup.write(name, existing) }
                profiles.write(name, context.assets.open("systems/$name").use { it.readBytes() })
            }
            profiles.read("duckstation.json")?.let { bytes ->
                if (backup.read("duckstation.json") == null) backup.write("duckstation.json", bytes)
                profiles.write("duckstation.json", context.assets.open("systems/psx.json").use { it.readBytes() })
            }
            target.write("cocoon-profile-version.json", "{\"version\":1}".toByteArray())
        }
        if (target.read("emulator-names-version.json")?.toString(Charsets.UTF_8)?.let { runCatching { JSONObject(it).optInt("version") }.getOrDefault(0) } != 2) {
            profiles.entries().filter { !it.second && it.first.endsWith(".json", true) }.forEach { (name, _) ->
                val source = profiles.read(name)!!.toString(Charsets.UTF_8)
                runCatching { SharedSystemProfiles.withEmulatorNamesOnly(source) }.getOrNull()?.let { updated -> profiles.write(name, updated.toByteArray()) }
            }
            target.write("emulator-names-version.json", "{\"version\":2}".toByteArray())
        }
    }
}

private interface DataFolder {
    fun prepareRevisionListing()=Unit
    fun revision(name:String):String?=null
    fun writeFrom(name:String,source:File)
    fun fileUri(name: String): Uri?
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun deleteFile(name:String):Boolean
    fun entries(): List<Pair<String, Boolean>>
    fun directory(name: String, create: Boolean): DataFolder?
}
private class LocalFolder(private val file: File) : DataFolder {
    override fun deleteFile(name:String):Boolean {
        val target=File(file,name)
        require(target.canonicalFile.parentFile==file.canonicalFile) { "Invalid theme path" }
        return target.isFile && target.delete()
    }
    override fun revision(name:String)=File(file,name).takeIf { it.isFile && it.lastModified()>0 }?.let { "${it.lastModified()}/${it.length()}" }
    override fun writeFrom(name:String,source:File) {
        val target=File(file,name);check(!target.exists()) { "Destination already exists" }
        val temporary=File(file,".${java.util.UUID.randomUUID()}.tmp")
        try { source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } };check(temporary.renameTo(target)) { "Could not save imported file" } }
        finally { temporary.delete() }
    }
    override fun fileUri(name: String)=File(file,name).takeIf { it.isFile }?.let(Uri::fromFile)
    override fun read(name: String) = File(file, name).takeIf { it.exists() }?.readBytes()
    override fun write(name: String, bytes: ByteArray) {
        val target = File(file, name)
        val temp = File(file, "$name.tmp")
        temp.writeBytes(bytes); check(temp.renameTo(target)) { "Could not save $name" }
    }
    override fun entries() = (file.listFiles() ?: error("Cannot list user data")).map { it.name to it.isDirectory }
    override fun directory(name: String, create: Boolean): DataFolder? {
        val child = File(file, name)
        if (!child.exists() && create) check(child.mkdir()) { "Could not create $name" }
        return if (child.isDirectory) LocalFolder(child) else null
    }
}
private class TreeFolder(private val context: Context, val uri: Uri) : DataFolder {
    override fun deleteFile(name:String):Boolean {
        val child=children().firstOrNull { it.first==name && !it.third } ?: return false
        if(!Docs.deleteDocument(context.contentResolver,child.second))return false
        childCache?.remove(child);revisions.remove(name)
        return true
    }
    override fun writeFrom(name:String,source:File) {
        check(children().none { it.first==name }) { "Destination already exists" }
        val mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
        val target=Docs.createDocument(context.contentResolver,document(),mime,name) ?: error("Cannot create imported file")
        try {
            context.contentResolver.openOutputStream(target,"w")?.use { output -> source.inputStream().use { it.copyTo(output) } } ?: error("Cannot write imported file")
            childCache?.add(Triple(name,target,false))
        } catch(error:Exception) { runCatching { Docs.deleteDocument(context.contentResolver,target) };throw error }
    }
    private var childCache: MutableList<Triple<String, Uri, Boolean>>? = null
    private val revisions=mutableMapOf<String,String>()
    private var listRevisions=false
    override fun prepareRevisionListing() { listRevisions=true }
    override fun revision(name:String):String? { children();return revisions[name] }
    private fun document() = if (Docs.isDocumentUri(context, uri)) uri else Docs.buildDocumentUriUsingTree(uri, Docs.getTreeDocumentId(uri))
    private fun children(): List<Triple<String, Uri, Boolean>> {
        childCache?.let { return it }
        val result = mutableListOf<Triple<String, Uri, Boolean>>()
        val parent = document()
        val query = Docs.buildChildDocumentsUriUsingTree(uri, Docs.getDocumentId(parent))
        val columns=arrayOf(Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_MIME_TYPE)+
            if(listRevisions)arrayOf(Docs.Document.COLUMN_LAST_MODIFIED,Docs.Document.COLUMN_SIZE) else emptyArray()
        val cursor = context.contentResolver.query(query, columns, null, null, null) ?: error("Cannot read folder")
        cursor.use { while (it.moveToNext()) {
            result.add(Triple(it.getString(0), Docs.buildDocumentUriUsingTree(uri, it.getString(1)), it.getString(2) == Docs.Document.MIME_TYPE_DIR))
            if(listRevisions && it.columnCount>=5 && !it.isNull(3) && !it.isNull(4) && it.getLong(3)>0)revisions[it.getString(0)]="${it.getString(1)}/${it.getLong(3)}/${it.getLong(4)}"
        } }
        childCache = result
        return result
    }
    override fun fileUri(name: String)=children().firstOrNull { it.first==name && !it.third }?.second
    override fun read(name: String): ByteArray? {
        val child = children().firstOrNull { it.first == name && !it.third } ?: return null
        return context.contentResolver.openInputStream(child.second)?.use { it.readBytes() } ?: error("Cannot read $name")
    }
    override fun write(name: String, bytes: ByteArray) {
        val child = children().firstOrNull { it.first == name }
        check(child?.third != true) { "$name is a directory" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: if (extension == "json") "application/json" else "application/octet-stream"
        val target = child?.second ?: Docs.createDocument(context.contentResolver, document(), mime, name) ?: error("Cannot create $name")
        if (child == null) childCache?.add(Triple(name, target, false))
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("Cannot write $name")
        revisions.remove(name)
    }
    override fun entries() = children().map { it.first to it.third }
    override fun directory(name: String, create: Boolean): DataFolder? {
        val child = children().firstOrNull { it.first == name }
        if (child != null) { check(child.third) { "$name is not a directory" }; return TreeFolder(context, child.second) }
        if (!create) return null
        val target = Docs.createDocument(context.contentResolver, document(), Docs.Document.MIME_TYPE_DIR, name) ?: error("Cannot create $name")
        childCache?.add(Triple(name, target, true))
        return TreeFolder(context, target)
    }
}
