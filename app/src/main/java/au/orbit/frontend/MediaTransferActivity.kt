package au.orbit.frontend

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MediaTransferActivity:Activity() {
    private lateinit var panel: MediaTransferPanel

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SystemCatalog.initialize(this)
        UserDataStore.initialize(this)
        FrontendRefreshRate.apply(this)
        val ids = intent.getStringArrayListExtra("populatedSystems")?.toSet()
        panel = MediaTransferPanel(this, ids?.let { savedIds -> { systems.filter { it.id in savedIds } } }, ::finish, {
            setResult(RESULT_OK)
        }) { picker -> startActivityForResult(picker, 151) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { panel.back() }
        }
        setContentView(panel.view)
        panel.initialFocus?.requestFocus()
    }

    @Deprecated("Platform picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 151 && resultCode == RESULT_OK && data != null) panel.folderSelected(data)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) { panel.back(); return true }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() { panel.destroy(); super.onDestroy() }
}

internal class MediaTransferPanel(
    private val activity: Activity,
    private val availableSystems: (() -> List<SystemSpec>)?,
    private val exit: () -> Unit,
    private val changed: () -> Unit,
    private val pickFolder: (Intent) -> Unit
) {
    val view = FrameLayout(activity)
    var initialFocus: View? = null
        private set
    private val sources=listOf("neostation" to "NeoStation","neostation-personal" to "NeoStation Personal","es-de" to "ES-DE")
    private var source=0
    private var move=false
    private var importSystem:SystemSpec?=null
    private var tree:Uri?=null
    private var plan:MediaImportPlan.Plan?=null
    private var busy=false
    private var message="Choose the source frontend and its media folder, then preview the transfer."
    private lateinit var status:TextView
    private val controls=mutableListOf<Button>()
    private var selectedControl="source"
    private var restoringFocus=false
    private var scroll:ScrollView?=null
    private val worker=Executors.newSingleThreadExecutor()
    private val cancelled=AtomicBoolean(false)
    private var lastProgress=0L
    private val prefs get()=activity.getSharedPreferences("media_transfer",0)
    private val contentResolver get() = activity.contentResolver
    private var closed = false
    private val active get() = !closed && !activity.isDestroyed && !activity.isFinishing
    private var choosing = false
    init {
        source=sources.indexOfFirst { it.first==prefs.getString("source",null) }.coerceAtLeast(0)
        tree=prefs.getString(sources[source].first,null)?.let(Uri::parse)
        render()
    }
    private fun label(value:String,size:Float=16f)=TextView(activity).apply { text=value;textSize=size;setTextColor(Color.WHITE);setPadding(0,12,0,12) }
    private fun button(key:String,value:String,availableWhileBusy:Boolean=false,action:()->Unit)=Button(activity).apply {
        tag=key;text=value;isAllCaps=false;textSize=17f;setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.rgb(130,140,155),Color.WHITE)))
        backgroundTintList=null;defaultFocusHighlightEnabled=false;stateListAnimator=null;elevation=0f
        isFocusableInTouchMode=true
        fun outline(selected:Boolean)=android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT);cornerRadius=10*resources.displayMetrics.density
            setStroke((2*resources.displayMetrics.density).toInt(),if(selected)Color.rgb(5,97,195) else Color.rgb(38,49,65))
        }
        background=android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),outline(true))
            addState(intArrayOf(android.R.attr.state_pressed),outline(true))
            addState(intArrayOf(),outline(false))
        }
        isEnabled=!busy || availableWhileBusy
        setOnFocusChangeListener { _,focused -> if(focused && !restoringFocus && !busy)selectedControl=key }
        setOnClickListener { if(!busy || availableWhileBusy) { if(!busy)selectedControl=key;requestFocus();action() } };controls.add(this)
    }
    private fun render() {
        choosing = false
        val wantedControl=selectedControl
        val previousScroll=scroll?.scrollY ?: 0
        restoringFocus=true
        controls.clear()
        val density=activity.resources.displayMetrics.density
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding((24*density).toInt(),(12*density).toInt(),(24*density).toInt(),(20*density).toInt());setBackgroundColor(Color.rgb(12,17,25)) }
        body.addView(label("Media transfer",26f))
        body.addView(button("source","Source: ${sources[source].second}") {
            source=(source+1)%sources.size;prefs.edit().putString("source",sources[source].first).apply()
            tree=prefs.getString(sources[source].first,null)?.let(Uri::parse);plan=null;message="Select a folder and preview the transfer.";render()
        })
        body.addView(button("scope","Systems: ${importSystem?.title ?: "All systems"}") { chooseImportSystem() })
        body.addView(button("mode",if(move) "Mode: Move to Orbit" else "Mode: Copy to Orbit") { move=!move;plan=null;message="Preview again to check this transfer mode.";render() })
        body.addView(label(if(move) "Move removes only matched source files after their Orbit copies and records are verified. Unmatched files stay in place."
            else "Copy keeps all original media in the source frontend's folder.",15f))
        body.addView(button("folder",if(tree==null)"Choose source media folder" else "Change source media folder") {
            val defaultFolder=when(sources[source].first) { "es-de" -> "ES-DE/downloaded_media";"neostation-personal" -> "Neostation 2/media";else -> "Neostation/media" }
            val initial=tree?.takeIf { saved -> runCatching {
                val document=android.provider.DocumentsContract.buildDocumentUriUsingTree(saved,android.provider.DocumentsContract.getTreeDocumentId(saved))
                contentResolver.query(document,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use { it.moveToFirst() }==true
            }.getOrDefault(false) } ?: android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:$defaultFolder")
            pickFolder(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,initial)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        })
        body.addView(label(tree?.let { runCatching { android.provider.DocumentsContract.getTreeDocumentId(it) }.getOrDefault(it.toString()) } ?: "Select the frontend's data folder, media folder or a system's media folder.",14f))
        body.addView(label("Destination: ${UserDataStore.location(activity)}/media\nGames must already be scanned in Orbit. Existing artwork is kept; imported files fill gaps.",14f))
        status=label(message,16f);body.addView(status)
        val preview=plan
        if(preview!=null) {
            val knownBytes=preview.matches.map { it.source.size.coerceAtLeast(0) }.sum()
            val volume=if(knownBytes>=1024L*1024*1024)String.format(java.util.Locale.ROOT,"%.1f GB",knownBytes/(1024.0*1024*1024))
                else if(knownBytes>=1024*1024)String.format(java.util.Locale.ROOT,"%.1f MB",knownBytes/(1024.0*1024)) else "${knownBytes/1024} KB"
            body.addView(label("${preview.matches.size} matched files · ${preview.matches.map { it.system.id+it.game.uri }.distinct().size} games · $volume\n${preview.unmatched} unmatched · ${preview.ambiguous} ambiguous · ${preview.unsupported} unsupported files",17f))
            val grouped=preview.matches.groupBy { it.system.title }
            grouped.entries.take(12).forEach { body.addView(label("${it.key}: ${it.value.size} files",14f)) }
            if(grouped.size>12)body.addView(label("+ ${grouped.size-12} more systems",14f))
        }
        body.addView(button("preview","Preview transfer") { preview() }.apply { isEnabled=!busy && tree!=null })
        body.addView(button("transfer",if(move) "Move matched media" else "Copy matched media") { transfer() }.apply { isEnabled=!busy && preview?.matches?.isNotEmpty()==true })
        body.addView(button("back",if(busy)"Cancel transfer" else "Back",true) { back() })
        val viewport=ScrollView(activity).apply { isFillViewport=true;defaultFocusHighlightEnabled=false;isSmoothScrollingEnabled=false;isVerticalScrollBarEnabled=false;addView(body) }
        initialFocus=controls.firstOrNull { it.tag==wantedControl && it.isEnabled } ?: controls.firstOrNull { it.isEnabled }
        val hadFocus = view.hasFocus()
        if (hadFocus) {
            view.isFocusableInTouchMode = true
            view.requestFocus()
        }
        scroll=viewport
        view.removeAllViews()
        view.addView(viewport)
        if (hadFocus) initialFocus?.requestFocus()
        viewport.post {
            if (scroll !== viewport || closed) return@post
            val target=controls.firstOrNull { it.tag==wantedControl && it.isEnabled } ?: controls.firstOrNull { it.isEnabled }
            if (view.hasFocus()) target?.requestFocus()
            viewport.scrollTo(0,previousScroll)
            target?.requestRectangleOnScreen(android.graphics.Rect(0,0,target.width,target.height),true)
            selectedControl=wantedControl;restoringFocus=false
        }
    }
    private var systemChoices:List<SystemSpec>? = null
    private fun showChoices(title: String, choices: List<Pair<String, () -> Unit>>, description: String? = null) {
        choosing = true
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 17, 25))
            addView(label(title, 24f))
            description?.let { addView(label(it)) }
        }
        controls.clear()
        choices.forEachIndexed { index, (name, action) ->
            body.addView(button("choice/$index", name, availableWhileBusy = true, action = action))
        }
        val viewport = ScrollView(activity).apply { defaultFocusHighlightEnabled = false; addView(body) }
        scroll = viewport
        view.removeAllViews()
        view.addView(viewport)
        initialFocus = controls.firstOrNull()
        initialFocus?.requestFocus()
    }
    private fun chooseImportSystem() {
        fun show(choices:List<SystemSpec>) {
            if(!active)return
            val options = (listOf<SystemSpec?>(null) + choices).map { spec ->
                val label = spec?.title ?: "All systems"
                (if (spec?.id == importSystem?.id) "✓  $label" else label) to {
                    importSystem = spec
                    plan = null
                    message = "$label selected. Preview to check matching media."
                    selectedControl = "scope"
                    render()
                }
            }
            showChoices("Import media for", options)
        }
        (availableSystems?.invoke()?.sortedBy { it.title } ?: systemChoices)?.let { show(it);return }
        showChoices("Import media for", listOf("Cancel" to { back() }), "Loading systems…")
        // SAF reads and library JSON parsing can take seconds on large collections.
        // Keep them off the UI thread so the popup stays responsive.
        worker.execute {
            val result=runCatching {
                val library=LibraryRepository(activity)
                systems.filter { library.cached(it)?.games?.isNotEmpty()==true }.sortedBy { it.title }
            }
            activity.runOnUiThread {
                if(!active)return@runOnUiThread
                result.onSuccess { systemChoices=it }
                if(choosing)result.onSuccess { show(it) }.onFailure { error(it.message ?: "Could not load systems") }
            }
        }
    }
    private fun progress(value:String) {
        val now=android.os.SystemClock.elapsedRealtime();if(now-lastProgress<150)return;lastProgress=now
        activity.runOnUiThread { if(active)status.text=value }
    }
    private fun preview() {
        val selected=tree ?: return
        val grant=contentResolver.persistedUriPermissions.firstOrNull { it.uri==selected }
        if(move && grant?.isWritePermission!=true) { error("Move needs write access to the source folder. Choose it again or use Copy.");return }
        busy=true;cancelled.set(false);message="Checking scanned games and source media…";render()
        worker.execute {
            val result=runCatching { MediaTransfer.scan(activity,selected,sources[source].first,cancelled::get,::progress,importSystem) }
            activity.runOnUiThread { if(active) {
                busy=false
                result.onSuccess { plan=it;message=if(it.matches.isEmpty())"No media matched scanned games. Check the folder and scan your ROM library first." else "Preview ready. Only matched media will be transferred." }
                    .onFailure { plan=null;message=if(it is java.util.concurrent.CancellationException)"Preview cancelled." else it.message ?: "Could not read source media" }
                render()
            } }
        }
    }
    private fun transfer() {
        val selected=plan ?: return;val sourceId=sources[source].first;val moveFiles=move
        busy=true;cancelled.set(false);message="Copying and verifying media…"
        // Keep the current hierarchy and focus while the transfer starts.
        // Replacing it briefly focused the first row and scrolled away from this button.
        status.text=message
        controls.forEach { control ->
            if(control.tag=="back")control.text="Cancel transfer"
            else if(!control.hasFocus())control.isEnabled=false
        }
        worker.execute {
            val result=runCatching { MediaTransfer.transfer(activity,selected,sourceId,moveFiles,cancelled::get,::progress) }
            activity.runOnUiThread { if(active) {
                busy=false;plan=null
                result.onSuccess { message=it.message(moveFiles);if(it.imported>0)changed() }
                    .onFailure { message=it.message ?: "Transfer failed. Unverified source files were kept.";changed() }
                render()
            } }
        }
    }
    private fun error(value:String) { showChoices("Media transfer", listOf("OK" to { render() }), value) }
    fun back() {
        when {
            choosing -> { if (!busy) selectedControl = "scope"; render() }
            !busy -> exit()
            else -> showChoices("Cancel transfer?", listOf(
                "Continue" to { render() },
                "Cancel transfer" to { cancelled.set(true); message = "Cancelling…"; render() }
            ), "Completed imports are kept. The current file will finish safely before stopping.")
        }
    }
    fun folderSelected(data: Intent) {
        val uri=data.data ?: return
        runCatching {
            val flags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0) { "Folder did not grant read access" }
            if(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION!=0)
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            else contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            require(!UserDataStore.overlapsUserData(activity,uri)) { "Choose the source frontend's folder, outside Orbit's user data." }
            tree=uri;plan=null;prefs.edit().putString(sources[source].first,uri.toString()).apply()
            message="Folder selected. Preview to match media to your scanned games.";render()
        }.onFailure { error(it.message ?: "Could not select folder") }
    }
    fun destroy() { closed=true;cancelled.set(true);worker.shutdownNow() }
}
