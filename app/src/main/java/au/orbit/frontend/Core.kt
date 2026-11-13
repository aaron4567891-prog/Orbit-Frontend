package au.orbit.frontend

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.view.Display

data class SystemSpec(val id: String, val title: String, val short: String, val subtitle: String, val extensions: Set<String>)
private val initialSystems = listOf(
    SystemSpec("psx", "PlayStation", "PS", "THE ORIGINAL PLAY", setOf("cue", "chd", "pbp", "iso")),
    SystemSpec("snes", "Super Nintendo", "SN", "16-BIT CLASSICS", setOf("sfc", "smc")),
    SystemSpec("gba", "Game Boy Advance", "GB", "POCKET-SIZED ADVENTURES", setOf("gba")),
    SystemSpec("nds", "Nintendo DS", "DS", "TWO SCREENS. MORE POSSIBILITIES.", setOf("nds")),
    SystemSpec("3ds", "Nintendo 3DS", "3D", "ANOTHER DIMENSION", setOf("3ds", "cci", "cxi")),
    SystemSpec("psp", "PlayStation Portable", "PP", "GREAT GAMES, ANYWHERE", setOf("iso", "cso", "chd"))
)
val systems: List<SystemSpec> get() = SystemCatalog.entries

object SystemCatalog {
    var entries: List<SystemSpec> = initialSystems
        private set
    private var initialized = false
    fun initialize(context: Context) {
        if (initialized) return
        load(context.assets.open("system-catalog.json").bufferedReader().use { it.readText() })
        initialized = true
    }
    internal fun load(source: String) {
        val array = org.json.JSONObject(source).getJSONArray("systems")
        val catalogue = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id").let { if (it == "n3ds") "3ds" else it }
            val ext = item.getJSONArray("extensions")
            val previous = initialSystems.find { it.id == id }
            SystemSpec(id, previous?.title ?: item.getString("title"), previous?.short ?: id.take(4).uppercase(),
                previous?.subtitle ?: "${item.getInt("players")} ANDROID LAUNCH PROFILES",
                (0 until ext.length()).map { ext.getString(it) }.toSet() + when(id) { "steam" -> setOf("steamappid");"psvita" -> setOf("dpt");"ps3" -> setOf("iso","ps3folder");else -> emptySet() })
        }
        require(catalogue.map { it.id }.distinct().size == catalogue.size) { "Duplicate system IDs" }
        val order = initialSystems.map { it.id }
        entries = catalogue.sortedWith(compareBy<SystemSpec> { order.indexOf(it.id).let { position -> if (position < 0) Int.MAX_VALUE else position } }.thenBy { it.title })
    }
}
data class Game(val title: String, val uri: Uri, val fileName: String = title,val directory:Boolean=false)

class LibraryRepository(private val context: Context) {
    companion object {
        private val reports=java.util.concurrent.ConcurrentHashMap<String,RomScanResult>()
    }
    private fun cacheKey(system:SystemSpec,paths:List<Uri>)=UserDataStore.location(context)+"\u0000"+system.id+"\u0000"+paths.joinToString("\u0000")
    fun folders(system: SystemSpec): List<Uri> = UserDataStore.strings(context, "library", system.id).map(Uri::parse)
    fun folder(system: SystemSpec): Uri? = folders(system).firstOrNull()
    fun save(system: SystemSpec, uri: Uri) = addFolders(system, listOf(uri))
    fun addFolders(system: SystemSpec, added: List<Uri>) {
        val old = folders(system)
        val values = (old + added).distinctBy { folderIdentity(it) }
        if (values == old) return
        UserDataStore.put(context, "library", system.id, org.json.JSONArray(values.map { it.toString() }))
    }
    fun removeFolder(system: SystemSpec, uri: Uri) {
        UserDataStore.put(context, "library", system.id, org.json.JSONArray(folders(system).filter { it != uri }.map { it.toString() }))
    }
    fun collectionRoots(): List<Uri> = UserDataStore.strings(context, "romCollections", "roots").map(Uri::parse)
    fun addCollectionRoot(uri: Uri) {
        UserDataStore.put(context, "romCollections", "roots", org.json.JSONArray((collectionRoots() + uri).distinctBy { folderIdentity(it) }.map { it.toString() }))
    }
    fun removeCollectionRoot(uri: Uri) {
        UserDataStore.put(context, "romCollections", "roots", org.json.JSONArray(collectionRoots().filter { it != uri }.map { it.toString() }))
    }
    private fun folderIdentity(uri: Uri) = "${uri.authority}:${if (Docs.isDocumentUri(context, uri)) Docs.getDocumentId(uri) else Docs.getTreeDocumentId(uri)}"
    fun discover(root: Uri, cancelled: () -> Boolean = { false }, progress: (String) -> Unit = {}): Pair<Map<SystemSpec, List<Uri>>, List<String>> {
        val source = DocumentRomSource(context, root)
        val rootName = source.rootId.substringAfterLast('/').substringAfterLast(':')
        val found = RomScanner.discover(source, rootName, systems, cancelled, progress)
        return found.folders.mapValues { (_, uris) -> uris.map { if (it.isBlank()) root else Uri.parse(it) } } to found.notes
    }
    fun scanReport(system: SystemSpec, cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): RomScanResult {
        val paths = folders(system)
        val sourceErrors = mutableListOf<String>()
        val sources = paths.mapNotNull { uri -> runCatching { DocumentRomSource(context, uri) }.onFailure { sourceErrors.add("$uri: ${it.message}") }.getOrNull() }
        val extensions = system.extensions + EmulatorProfileRepository(context).forSystem(system).flatMap { it.extensions }
        val result = ArcadeNames.apply(context, system, RomScanner.scan(sources, extensions, cancelled, progress,detectPs3Folders=system.id=="ps3").let { it.copy(errors = sourceErrors + it.errors) })
        if (cancelled()) throw java.util.concurrent.CancellationException()
        val json = org.json.JSONObject().put("folders", org.json.JSONArray(paths.map { it.toString() })).put("scannedAt", System.currentTimeMillis())
            .put("errors", org.json.JSONArray(result.errors)).put("games", org.json.JSONArray(result.games.map { org.json.JSONObject().put("title", it.title).put("uri", it.uri).put("fileName", it.fileName).put("directory",it.directory) }))
        UserDataStore.writeLibrary(context, system.id, json.toString())
        reports[cacheKey(system,paths)]=result
        return result
    }
    fun populatedSystems(reports: Map<SystemSpec, RomScanResult> = emptyMap()): List<SystemSpec> = systems.filter {
        folders(it).isNotEmpty() && (reports[it] ?: cached(it))?.games?.isNotEmpty() == true
    }
    fun cached(system: SystemSpec): RomScanResult? = runCatching {
        val currentPaths=folders(system)
        val key=cacheKey(system,currentPaths)
        reports[key]?.let { return it }
        val json = org.json.JSONObject(UserDataStore.readLibrary(context, system.id) ?: return null)
        val paths = json.getJSONArray("folders")
        if ((0 until paths.length()).map { paths.getString(it) } != currentPaths.map { it.toString() }) return null
        val games = json.getJSONArray("games"); val errors = json.optJSONArray("errors") ?: org.json.JSONArray()
        ArcadeNames.apply(context, system, RomScanResult(RomScanner.hidePlaylistDiscs((0 until games.length()).map { games.getJSONObject(it).let { game -> RomRecord(game.getString("title"), game.getString("uri"), game.getString("fileName"),game.optBoolean("directory",false)) } }), (0 until errors.length()).map { errors.getString(it) })).also { reports[key]=it }
    }.getOrNull()
}

class DocumentRomSource(private val context: Context, private val tree: Uri) : RomSource {
    override val directoryCacheNamespace=tree.authority
    override fun directory(id:String):RomNode? {
        val uri=Docs.buildDocumentUriUsingTree(tree,id)
        return context.contentResolver.query(uri,arrayOf(Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_MIME_TYPE),null,null,null)?.use {
            if(it.moveToFirst() && it.getString(1)==Docs.Document.MIME_TYPE_DIR)RomNode(id,it.getString(0),true,uri.toString()) else null
        }
    }
    override val label = tree.toString()
    override val rootId: String = if (Docs.isDocumentUri(context, tree)) Docs.getDocumentId(tree) else Docs.getTreeDocumentId(tree)
    override fun identity(node: RomNode) = "${tree.authority}:${node.id}"
    override fun children(id: String): List<RomNode> {
        val result = mutableListOf<RomNode>()
        val children = Docs.buildChildDocumentsUriUsingTree(tree, id)
        val cursor = context.contentResolver.query(children, arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE), null, null, null) ?: error("Cannot read folder")
        cursor.use { c -> while (c.moveToNext()) {
            val child = c.getString(0); val name = c.getString(1) ?: continue
            result.add(RomNode(child, name, c.getString(2) == Docs.Document.MIME_TYPE_DIR, Docs.buildDocumentUriUsingTree(tree, child).toString()))
        } }
        return result
    }
}

class DualScreenManager(private val context: Context) {
    fun displays(): List<Display> = context.getSystemService(DisplayManager::class.java).displays.filter { it.isValid }
    fun options(id: Int): android.os.Bundle = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
    fun displayForScreen(screen: String): Int {
        if (screen == "top") return Display.DEFAULT_DISPLAY
        return displays().firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.displayId
            ?: error("The bottom screen is unavailable.")
    }
}

// Session records launch requests only: third-party emulator lifecycle is not observable reliably.
object GameSessionManager {
    var lastGame: String? = null
    var displayName: String? = null
}

class EmulatorLaunchManager(private val activity: Activity) {
    fun launch(game: Game, displayId: Int, profile: EmulatorProfile) {
        check(profile.installed(activity)) { "${profile.name} is not installed or its launch activity is unavailable." }
        val launchGame=XboxDiscProvider.prepare(activity,game,profile.component.packageName)
        val intent = profile.intent(launchGame,activity)
        check(profile.launchDisplay || displayId == activity.windowManager.defaultDisplay.displayId) { "This profile does not support a selected display." }
        start(launchGame, displayId, intent)
    }
    fun launch(game: Game, displayId: Int, component: android.content.ComponentName) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(game.uri, "application/octet-stream")
            .setComponent(component).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.clipData = android.content.ClipData.newRawUri("ROM", game.uri)
        start(game, displayId, intent)
    }
    private fun start(game: Game, displayId: Int, intent: Intent) {
        RomAccessManager.prepare(activity, game, intent)
        check(activity.getSystemService(android.app.ActivityManager::class.java).isActivityStartAllowedOnDisplay(activity, displayId, intent)) { "Android does not allow this emulator on the selected display." }
        val emulatorPackage=intent.component?.packageName
        if(emulatorPackage in setOf("com.rfandango.haku_x","com.izzy2lost.x1box")) {
            // The stable disc provider handles stale preferences. Keep a short gap
            // before replacing the emulator task so the previous activity can stop.
            activity.window.decorView.postDelayed({
                if(!activity.isDestroyed && !activity.isFinishing)runCatching {
                    open(game,displayId,intent)
                }
                    .onFailure { error -> android.app.AlertDialog.Builder(activity).setTitle("Unable to launch")
                        .setMessage(error.message ?: "Emulator launch failed").setPositiveButton("OK",null).show() }
            },300)
            return
        }
        open(game,displayId,intent)
    }
    private fun open(game:Game,displayId:Int,intent:Intent) {
        val source=DualScreenCoordinator.launchSource(activity,displayId)
        source.startActivity(intent, DualScreenManager(source).options(displayId))
        DualScreenCoordinator.gameLaunched(activity,displayId)
        GameSessionManager.lastGame = game.title
        GameSessionManager.displayName = "Display $displayId"
    }
}
