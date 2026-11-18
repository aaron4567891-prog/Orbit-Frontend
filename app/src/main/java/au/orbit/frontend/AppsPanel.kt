package au.orbit.frontend

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import java.util.concurrent.Executors

/** Native launcher activity discovery; includes games, emulators, utilities and system apps. */
class AppsPanel(private val activity: Activity, private val navigate: (Int) -> Unit, private val leave: () -> Unit, private val showNavigation:Boolean=true) {
    lateinit var view: LinearLayout
        private set
    private val blue = Color.rgb(5, 97, 195)
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var allApps = emptyList<InstalledApps.Entry>()
    private var visibleApps = emptyList<InstalledApps.Entry>()
    private lateinit var grid: GridView
    val initialFocus: View get() = grid
    fun companionSelection()=visibleApps.getOrNull(grid.selectedItemPosition)
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var compactKeyboard: CompactAppKeyboard
    private var displayId = 0
    init {
        build()
        InstalledApps.cached?.let { allApps = it; filter() }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun build(state: Bundle? = null) {
        displayId = state?.getInt("display") ?: UserDataStore.int(activity, "appDisplay", activity.windowManager.defaultDisplay.displayId)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(12), dp(28), dp(12)); setBackgroundColor(Color.rgb(12, 17, 25))
        }
        if(showNavigation)root.addView(NavigationTabs.view(activity, 1) { tab -> if (tab != 1) navigate(tab) })
        search = EditText(activity).apply {
            hint = "Search installed apps"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setSingleLine(); setText(state?.getString("query") ?: "")
            showSoftInputOnFocus = false
            setOnClickListener { showSearchKeyboard() }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        root.addView(search)
        status = TextView(activity).apply { setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)
        grid = GridView(activity).apply {
            numColumns = GridView.AUTO_FIT; columnWidth = dp(88); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            defaultFocusHighlightEnabled = false
            selector = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), blue)
            }
            horizontalSpacing = dp(12); verticalSpacing = dp(12)
            setOnItemClickListener { _, _, position, _ -> launch(visibleApps[position]) }
            setOnItemLongClickListener { _, _, position, _ -> showAppActions(position); true }
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long) { DualScreenCoordinator.refreshMedia() }
                override fun onNothingSelected(parent:AdapterView<*>?) { DualScreenCoordinator.refreshMedia() }
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        compactKeyboard = CompactAppKeyboard(activity,search) { grid.requestFocus() }
        root.addView(compactKeyboard)
        view = root
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    fun resume() {
        val displays = DualScreenManager(activity).displays()
        if (displays.none { it.displayId == displayId }) displayId = activity.windowManager.defaultDisplay.displayId
        val ticket = ++generation
        val previous = InstalledApps.cached
        if (previous != null) { if (allApps !== previous) { allApps = previous; filter() }; grid.requestFocus() }
        else { status.visibility = View.VISIBLE; status.text = "Loading installed apps…" }
        worker.execute {
            val result = runCatching {
                InstalledApps.load(activity.applicationContext, forceRefresh = true)
            }
            activity.runOnUiThread {
                if (ticket == generation && !activity.isDestroyed) {
                    result.onSuccess { entries -> if (allApps !== entries) { allApps = entries; filter() } }
                    result.exceptionOrNull()?.let { status.text = "Unable to load apps. Return to the library and try again." }
                    if (view.isAttachedToWindow && compactKeyboard.visibility != View.VISIBLE) grid.requestFocus()
                }
            }
        }
    }
    private fun filter() {
        val query = search.text.toString().trim()
        visibleApps = allApps.filter { it.label.contains(query, true) }
        status.text = if (visibleApps.isEmpty()) "No apps found" else "Hold an app or press Y for options"
        status.visibility = View.VISIBLE
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = visibleApps.size
            override fun getItem(position: Int) = visibleApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = getItem(position)
                return (convertView as? ImageView ?: ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = AbsListView.LayoutParams(-1, dp(88))
                }).apply {
                    if (tag !== app) {
                        setImageDrawable(app.icon.constantState?.newDrawable(activity.resources) ?: app.icon)
                        contentDescription = app.label
                        tag = app
                    }
                }
            }
        }
    }
    private fun hideSearchKeyboard() { compactKeyboard.hide() }
    private fun showAppActions(position: Int) {
        val app = visibleApps.getOrNull(position) ?: return
        val info = app.info.activityInfo
        AlertDialog.Builder(activity).setTitle(app.label)
            .setItems(arrayOf("Uninstall app")) { _, _ ->
                AppActions.uninstall(activity, ComponentName(info.packageName, info.name))
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showSearchKeyboard() { compactKeyboard.show() }
    private fun launch(app: InstalledApps.Entry) {
        runCatching {
            val info = app.info.activityInfo
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(info.packageName, info.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            check(activity.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(activity, displayId, intent)) { "Android does not allow activity app on display $displayId. Choose another app display." }
            activity.startActivity(intent, DualScreenManager(activity).options(displayId))
        }.onFailure { showError(it) }
    }
    private fun chooseDisplay() {
        val displays = DualScreenManager(activity).displays()
        AlertDialog.Builder(activity).setTitle("Android app display")
            .setSingleChoiceItems(displays.map { "${it.name} · ID ${it.displayId}" }.toTypedArray(), displays.indexOfFirst { it.displayId == displayId }) { dialog, index ->
                runCatching { UserDataStore.put(activity, "settings", "appDisplay", displays[index].displayId) }.onSuccess {
                    displayId = displays[index].displayId
                    dialog.dismiss()
                }.onFailure { showError(it) }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun showError(error: Throwable) { AlertDialog.Builder(activity).setTitle("Unable to open app").setMessage(error.message ?: "The app may have been removed or disabled.").setPositiveButton("OK", null).show() }
    fun destroy() { generation++; worker.shutdownNow() }
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y && grid.hasFocus()) {
            if (event.repeatCount == 0) showAppActions(grid.selectedItemPosition)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            if (event.repeatCount == 0) {
                search.setSelection(search.text.length)
                showSearchKeyboard()
            }
            return true
        }
        if (keyCode in listOf(KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BACK)) {
            if(event.repeatCount==0 && !event.isCanceled) {
                if (compactKeyboard.visibility == View.VISIBLE) hideSearchKeyboard() else leave()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A && compactKeyboard.hasFocus()) {
            if (event.repeatCount == 0) activity.currentFocus?.performClick()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A && grid.hasFocus()) {
            val position = grid.selectedItemPosition
            if (position in visibleApps.indices) launch(visibleApps[position])
            return true
        }
        return false
    }
}
