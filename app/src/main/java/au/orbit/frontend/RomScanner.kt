package au.orbit.frontend

import java.util.concurrent.CancellationException

data class RomRecord(val title: String, val uri: String, val fileName: String,val directory:Boolean=false)
data class RomNode(val id: String, val name: String, val directory: Boolean, val uri: String)
interface RomSource {
    val label: String
    val rootId: String
    /** Opt in only when directory IDs guarantee identical listings across source roots. */
    val directoryCacheNamespace:String? get()=null
    fun children(id: String): List<RomNode>
    fun identity(node: RomNode): String
    fun directory(id:String):RomNode?=null
}
data class RomScanResult(val games: List<RomRecord>, val errors: List<String>)

object RomScanner {
    private val discExtensions = setOf("cue", "bin", "img", "iso", "chd", "pbp", "cso", "gdi", "ccd", "mds", "mdf", "nrg")
    private val discTag = Regex("[\\[(]\\s*(?:disc|disk|cd)\\s*[-_. ]*\\s*(?:[0-9]+|[ivx]+)(?:\\s+of\\s+[0-9]+)?\\s*[\\])]", RegexOption.IGNORE_CASE)
    private val discSuffix = Regex("[\\s._-]+(?:disc|disk|cd)[\\s._-]*(?:[0-9]+|[ivx]+)$", RegexOption.IGNORE_CASE)
    internal fun hasDiscMarker(name: String): Boolean = discTag.containsMatchIn(name) || discSuffix.containsMatchIn(name)
    private fun playlistName(name: String) = name.substringBeforeLast('.').replace(discTag, " ").replace(discSuffix, "")
        .trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun documentPath(record: RomRecord): String {
        val decoded = java.net.URLDecoder.decode(record.uri.replace("+", "%2B"), "UTF-8")
        // SAF trees may differ for the same document, so use the document identity.
        return if ("/document/" in decoded) decoded.substringBefore("/tree/") + "/document/" + decoded.substringAfter("/document/") else decoded
    }
    private fun parent(record: RomRecord): String = documentPath(record).substringBeforeLast('/')

    private fun playlistDiscPath(folder: String, entry: String): String? {
        val path = entry.trim().removePrefix("\uFEFF").trim().trim('"').replace('\\', '/')
        if (path.isEmpty() || path.startsWith('#') || path.startsWith('/') || ':' in path) return null
        val segments = folder.split('/').toMutableList()
        val rootSize = if ("/document/" in folder) folder.substringBefore("/document/").split('/').size + 2 else 3
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.size > rootSize) segments.removeAt(segments.lastIndex) else return null
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/").lowercase(java.util.Locale.ROOT)
    }

    fun hidePlaylistDiscs(records: List<RomRecord>, readPlaylist: (RomRecord) -> List<String> = { emptyList() }): List<RomRecord> {
        val playlists = records.filter { it.fileName.substringAfterLast('.').equals("m3u", true) }
        if (playlists.isEmpty()) return records
        val foldersByName = playlists.groupBy { playlistName(it.fileName) }
            .mapValues { (_, entries) -> entries.map(::parent) }
        val listedDiscs = playlists.flatMapTo(mutableSetOf()) { playlist ->
            val folder = parent(playlist)
            readPlaylist(playlist).mapNotNull { playlistDiscPath(folder, it) }
        }
        return records.filter { game ->
            if (game.fileName.substringAfterLast('.').lowercase(java.util.Locale.ROOT) !in discExtensions) true
            else {
                val path = documentPath(game)
                val folder = path.substringBeforeLast('/')
                path.lowercase(java.util.Locale.ROOT) !in listedDiscs &&
                    foldersByName[playlistName(game.fileName)].orEmpty().none { folder == it || folder.startsWith("$it/") }
            }
        }
    }
    data class Discovery(val folders: Map<SystemSpec, List<String>>, val notes: List<String>)
    fun discover(source: RomSource, rootName: String, catalogue: List<SystemSpec>, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}): Discovery {
        val matches = linkedMapOf<SystemSpec, MutableList<String>>()
        val notes = mutableListOf<String>()
        val own = matchFolder(rootName, catalogue)
        if (own != null) return Discovery(mapOf(own to listOf("")), emptyList())
        val pending = java.util.ArrayDeque<Pair<String, String>>().apply { add(source.rootId to rootName) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Scan cancelled")
            val (id, path) = pending.removeFirst()
            if (!visited.add(id)) continue
            progress(path)
            val nodes = try { source.children(id) } catch (error: Exception) { notes.add("$path: ${error.message}"); continue }
            val visible = nodes.filterNot { it.name.startsWith('.') }
            if (visible.any { !it.directory }) notes.add("Unassigned files in $path (no recognized system folder)")
            visible.filter { it.directory }.forEach { node ->
                val system = matchFolder(node.name, catalogue)
                if (system != null) matches.getOrPut(system) { mutableListOf() }.add(node.uri)
                else pending.add(node.id to "$path/${node.name}")
            }
        }
        return Discovery(matches, notes)
    }
    fun scan(sources: List<RomSource>, extensions: Set<String>, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {},detectPs3Folders:Boolean=false,
        readPlaylist: (RomRecord) -> List<String> = { emptyList() }): RomScanResult {
        val supported = if (extensions.any { it.lowercase() in discExtensions }) extensions + "m3u" else extensions
        val games = linkedMapOf<String, RomRecord>()
        val errors = mutableListOf<String>()
        var count = 0
        val visitedDirectories=mutableSetOf<String>()
        for (source in sources) {
            val pending = java.util.ArrayDeque<String>().apply { add(source.rootId) }
            val directories=mutableMapOf<String,RomNode>()
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Scan cancelled")
                val id = pending.removeFirst()
                if (!visited.add(id)) continue
                val directoryIdentity=source.directoryCacheNamespace?.let { "$it\u0000$id" }
                if(directoryIdentity!=null && directoryIdentity in visitedDirectories)continue
                val nodes = try { source.children(id) } catch (error: Exception) {
                    errors.add("${source.label}: ${error.message ?: "Folder unavailable"}"); continue
                }
                if(directoryIdentity!=null)visitedDirectories.add(directoryIdentity)
                if(detectPs3Folders && (nodes.any { it.directory && it.name.equals("PS3_GAME",true) } ||
                    (nodes.any { it.directory && it.name.equals("USRDIR",true) } && nodes.any { !it.directory && it.name.equals("PARAM.SFO",true) }))) {
                    val folder=directories[id] ?: source.directory(id)
                    if(folder!=null) {
                        games.putIfAbsent(source.identity(folder),RomRecord(folder.name,folder.uri,folder.name+".ps3folder",true))
                        progress(games.size)
                        continue
                    }
                }
                for (node in nodes) {
                    if (cancelled()) throw CancellationException("Scan cancelled")
                    if (node.name.startsWith('.')) continue
                    if (node.directory) { directories[node.id]=node;pending.add(node.id) }
                    else if (node.name.substringAfterLast('.', "").lowercase() in supported) {
                        games.putIfAbsent(source.identity(node), RomRecord(node.name.substringBeforeLast('.'), node.uri, node.name))
                        if (++count % 50 == 0) progress(games.size)
                    }
                }
            }
        }
        val visible = hidePlaylistDiscs(games.values.toList(), readPlaylist)
        progress(visible.size)
        return RomScanResult(visible.sortedWith(compareBy<RomRecord> { it.title.lowercase() }.thenBy { it.uri }), errors)
    }
    fun matchFolder(name: String, catalogue: List<SystemSpec>): SystemSpec? {
        fun normalize(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
        val normalized = normalize(name)
        val aliases = mapOf(
            "n3ds" to "3ds", "nintendo3ds" to "3ds", "ps1" to "psx", "psone" to "psx", "playstation" to "psx", "playstation1" to "psx", "sonyplaystation" to "psx",
            "ds" to "nds", "nintendods" to "nds", "sfc" to "snes", "superfamicom" to "snes", "supernintendo" to "snes",
            "gameboyadvance" to "gba", "gameboy" to "gb", "gameboycolor" to "gbc", "gameboycolour" to "gbc",
            "playstation2" to "ps2", "playstation3" to "ps3", "playstationportable" to "psp", "psvita" to "psvita",
            "nintendo64" to "n64", "gamecube" to "gc", "nintendogamecube" to "gc", "megadrive" to "megadrive", "sega32x" to "sega32x", "32x" to "sega32x")
        catalogue.firstOrNull { normalize(it.id) == normalized }?.let { return it }
        aliases[normalized]?.let { id -> catalogue.firstOrNull { it.id == id }?.let { return it } }
        return catalogue.filter { normalize(it.title) == normalized }.singleOrNull()
    }
}
