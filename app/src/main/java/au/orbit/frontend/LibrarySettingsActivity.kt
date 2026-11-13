package au.orbit.frontend

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LibrarySettingsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var busy = false
    private var selected = 0
    private var pendingSystem: String? = null
    private val systemOnly get() = intent.getBooleanExtra("systemOnly", false)
    private lateinit var repository: LibraryRepository
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var cancelButton: Button
    private val controls = mutableListOf<Button>()
    private val system get() = systems[selected]
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SystemCatalog.initialize(this)
        repository = LibraryRepository(this)
        selected = systems.indexOfFirst { it.id == (state?.getString("system") ?: intent.getStringExtra("system")) }.coerceAtLeast(0)
        pendingSystem = state?.getString("pending")
        setResult(RESULT_OK)
        render()
    }
    private fun label(value: String) = TextView(this).apply { text = value; textSize = 16f; setTextColor(Color.WHITE); setPadding(8, 12, 8, 12) }
    private fun action(value: String, block: () -> Unit): Button = Button(this).apply {
        SelectionStyle.apply(this); text = value; isAllCaps = false; isEnabled = !busy; setOnClickListener { block() }; controls.add(this)
    }
    private fun render() {
        controls.clear()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24); setBackgroundColor(Color.rgb(12, 17, 25)) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label(if (systemOnly) "${system.title} settings" else "Global ROM library"))
        content.addView(action("← Library") { finish() })
        if (!systemOnly) {
        content.addView(action("Choose emulator") { EmulatorSettings.show(this) })
        content.addView(label("Choose your main ROM folder once. All system folders inside it are detected automatically, including nested folders and short names such as gba, snes, psx and nds."))
        content.addView(action("Add ROM folder") { pick(111) })
        content.addView(action("Scan all games") { runScan(true) })
        repository.collectionRoots().forEach { uri ->
            content.addView(action("ROM folder: ${folderName(uri)} · Remove") {
                AlertDialog.Builder(this).setTitle("Stop discovering this folder?").setMessage("$uri\n\nExisting system assignments stay in the library; ROM files are kept.")
                    .setPositiveButton("Remove") { _, _ -> runCatching { repository.removeCollectionRoot(uri) }.onSuccess { render() }.onFailure { showError(it) } }.setNegativeButton("Cancel", null).show()
            })
        }
        }
        status = label(if (systemOnly) "Manage the emulator and ROM folders for ${system.title}." else "Ready. Add one or more global ROM folders, then scan all games.")
        content.addView(status)
        cancelButton = Button(this).apply { SelectionStyle.apply(this); text = "Cancel scan"; isEnabled = busy; setOnClickListener { cancelled.set(true); text = "Cancelling…"; isEnabled = false } }
        content.addView(cancelButton)
        if (systemOnly) {
        content.addView(action("Choose emulator for ${system.title}") { EmulatorSettings.showForSystem(this, system) })
        content.addView(label("${system.title} · ${repository.folders(system).size} ROM folders"))
        repository.folders(system).forEach { uri ->
            content.addView(action("Remove folder: ${folderName(uri)}") {
                AlertDialog.Builder(this).setTitle("Remove ROM folder?").setMessage("$uri\n\nOnly the library reference is removed. ROM files are kept.")
                    .setPositiveButton("Remove") { _, _ -> runCatching { repository.removeFolder(system, uri) }.onSuccess { render() }.onFailure { showError(it) } }.setNegativeButton("Cancel", null).show()
            })
        }
        content.addView(action("Add ROM folder for ${system.title}") { pendingSystem = system.id; pick(110) })
        content.addView(action("Scan ${system.title}") { runScan(false) })
        }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, -1)); setContentView(root)
    }
    private fun folderName(uri: Uri) = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrElse { runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(uri.toString()) }
    private fun pick(code: Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), code) }
    @Deprecated("Platform folder picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in listOf(110, 111) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            RomAccessManager.persistFolderAccess(this, data, uri)
            if (requestCode == 110) {
                val spec = systems.first { it.id == pendingSystem }
                repository.save(spec, uri)
                selected = systems.indexOf(spec); pendingSystem = null
            } else repository.addCollectionRoot(uri)
        }.onSuccess { render(); if (requestCode == 111) runScan(true) }.onFailure { showError(it) }
    }
    private fun progress(message: String) { runOnUiThread { if (!isDestroyed) status.text = message } }
    private fun runScan(all: Boolean) {
        if (busy) return
        val selectedSystem = system
        busy = true; cancelled.set(false); render()
        worker.execute {
            val notes = mutableListOf<String>()
            val summaries = mutableListOf<String>()
            var count = 0; var completed = 0
            val result = runCatching {
                if(all) {
                    notes.addAll(StartupScanner.scan(repository,{ cancelled.get() },{ spec,report ->
                        count+=report.games.size;completed++;summaries.add("${spec.title}: ${report.games.size} games")
                    }) { message,_,_ -> progress(message) })
                } else {
                    val spec=selectedSystem
                    checkCancelled()
                    progress(spec.title)
                    val scanned = repository.scanReport(spec, { cancelled.get() }) { games -> progress("${spec.title} · $games games") }
                    count += scanned.games.size; completed++
                    summaries.add("${spec.title}: ${scanned.games.size} games")
                    notes.addAll(scanned.errors)
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false; render()
                val title = if (cancelled.get() || result.exceptionOrNull() is java.util.concurrent.CancellationException) "Scan cancelled" else if (result.isFailure) "Scan stopped" else "Scan complete"
                status.text = "$title · $completed systems · $count games"
                val details = summaries + notes + listOfNotNull(result.exceptionOrNull()?.takeUnless { it is java.util.concurrent.CancellationException }?.message)
                AlertDialog.Builder(this).setTitle(title).setMessage("$completed systems scanned · $count games\n\n${details.take(40).joinToString("\n")}${if (details.size > 40) "\n… ${details.size - 40} more entries" else ""}").setPositiveButton("OK", null).show()
            }
        }
    }
    private fun checkCancelled() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException() }
    private fun showError(error: Throwable) { AlertDialog.Builder(this).setTitle("ROM folders").setMessage(error.message ?: "Folder unavailable").setPositiveButton("OK", null).show() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("system", system.id); out.putString("pending", pendingSystem) }
    override fun onStart() { super.onStart(); DualScreenCoordinator.attach(this) }
    override fun onResume() { super.onResume(); FrontendRefreshRate.apply(this) }
    override fun onStop() { DualScreenCoordinator.detach(this); super.onStop() }
    override fun onDestroy() { cancelled.set(true); worker.shutdownNow(); super.onDestroy() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
            if (busy) cancelled.set(true) else finish()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) { currentFocus?.performClick(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
