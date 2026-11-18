package au.orbit.frontend

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*

/** System-wide emulator preferences, independent of the currently browsed collection. */
object EmulatorSettings {
    interface PageHost {
        val activity: Activity
        val isTabbed: Boolean
        val smoothSelection: Boolean get() = false
        fun selectCompanionOption(label: String)
        fun showPage(title: String, content: android.view.View, backLabel: String = "Back",
            back: () -> Unit, handle: (Int, KeyEvent) -> Boolean = { _, _ -> false })
    }
    private val menus=mutableListOf<java.lang.ref.WeakReference<MenuWindow>>()
    private fun register(window:MenuWindow) {
        menus.removeAll { it.get()==null || it.get()===window }
        menus.add(java.lang.ref.WeakReference(window))
    }
    fun companionBrowse(activity:Activity):CompanionBrowse? = menus.asReversed().asSequence().mapNotNull { it.get() }
        .firstOrNull { it.activity===activity && it.dialog.isShowing }?.browseSelection()
    fun menuVisible(activity:Activity)=companionBrowse(activity)!=null
    /** The focused row owns its highlight; there is no separate ListView selector to drift. */
    private class Rows(
        private val activity: Activity,
        private val styled: Boolean = false,
        private val smoothSelection: Boolean = false,
        private val onSelection: (String) -> Unit
    ) {
        val view = ScrollView(activity).apply {
            isSmoothScrollingEnabled = false
            isFocusableInTouchMode = true
            // The rows draw their own selection. Android's container focus overlay
            // otherwise washes out the entire page when this viewport receives focus.
            defaultFocusHighlightEnabled = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val buttons = mutableListOf<Button>()
        private var cursor = 0
        val selectedIndex get()=cursor
        var onLongPress:((Int)->Unit)?=null
        private var revealAnimation: android.animation.ValueAnimator? = null

        private fun paint(index: Int) {
            (buttons[index].background as GradientDrawable)
                .setStroke(dp(2), if (index == cursor) Color.rgb(5, 97, 195) else Color.rgb(38,49,65))
        }

        init {
            view.addView(content)
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) revealAnimation?.cancel()
                false
            }
            view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = Unit
                override fun onViewDetachedFromWindow(view: android.view.View) {
                    revealAnimation?.cancel()
                }
            })
        }
        fun populate(labels: List<String>, choose: (Int) -> Unit) {
            revealAnimation?.cancel()
            content.removeAllViews(); buttons.clear()
            cursor = 0
            labels.forEachIndexed { index, label ->
                val row = Button(activity).apply {
                    text = label; isAllCaps = false; gravity = android.view.Gravity.CENTER_VERTICAL
                    textSize = 16f; setTextColor(Color.WHITE)
                    backgroundTintList = null
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        if (styled) cornerRadius = dp(12).toFloat()
                    }
                    defaultFocusHighlightEnabled = false
                    isFocusableInTouchMode = true
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    setOnClickListener { focus(index);choose(index) }
                    setOnLongClickListener { onLongPress?.invoke(index);onLongPress!=null }
                    setOnKeyListener { _, key, event ->
                        when (key) {
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    val next = (index + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(buttons.indices)
                                    if (key == KeyEvent.KEYCODE_DPAD_UP && index == 0) false
                                    else { focus(next, animate = true); true }
                                } else true
                            }
                            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) performClick()
                                true
                            }
                            else -> false
                        }
                    }
                }
                buttons.add(row)
                paint(index)
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
            }
        }

        fun focus(index: Int, animate: Boolean = false) {
            val row = buttons.getOrNull(index) ?: return
            val previous = cursor
            cursor = index
            view.contentDescription = row.text
            if (!view.hasFocus()) view.requestFocus()
            if (previous != index) {
                paint(previous)
                paint(index)
            }
            if (view.isAttachedToWindow) onSelection(row.text.toString())
            if (row.height == 0 || view.height == 0) {
                row.post { if (view.isAttachedToWindow && cursor == index) reveal(row, animate = false) }
            } else {
                reveal(row, animate && smoothSelection)
            }
        }

        private fun reveal(row: Button, animate: Boolean) {
            val start = view.scrollY
            val target = when {
                row.top < start -> row.top
                row.bottom > start + view.height -> row.bottom - view.height
                else -> start
            }
            revealAnimation?.cancel()
            if (start == target) return
            if (!animate) {
                view.scrollTo(0, target)
                return
            }
            revealAnimation = android.animation.ValueAnimator.ofInt(start, target).apply {
                duration = 120
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { view.scrollTo(0, it.animatedValue as Int) }
                start()
            }
        }
        fun updateLabels(labels: List<String>) {
            labels.forEachIndexed { index, label -> buttons.getOrNull(index)?.let { row ->
                if(row.text.toString()!=label)row.text=label
            } }
            view.contentDescription=buttons.getOrNull(cursor)?.text
        }
        fun handle(key: Int, event: KeyEvent): Boolean {
            if (buttons.isEmpty()) return false
            return when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                        val next = (cursor + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(buttons.indices)
                        if (next != cursor) focus(next, animate = true)
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) buttons[cursor].performClick()
                    true
                }
                else -> false
            }
        }
        private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    }
    class MenuWindow(
        override val activity: Activity,
        private val compact: Boolean = false,
        private val fullScreen: Boolean = false,
        override val smoothSelection: Boolean = false
    ) : PageHost {
        private val browse=MenuBrowseState()
        override fun selectCompanionOption(label:String) { browse.select(label);DualScreenCoordinator.refreshMedia() }
        fun browseSelection():CompanionBrowse {
            val icon=when(browse.icon) {
                MenuBrowseState.Icon.SETTINGS -> R.drawable.ic_companion_settings
                MenuBrowseState.Icon.ARTWORK -> R.drawable.ic_companion_artwork
                MenuBrowseState.Icon.EMULATOR -> R.drawable.ic_companion_emulator
                MenuBrowseState.Icon.STORAGE -> R.drawable.ic_companion_storage
                MenuBrowseState.Icon.DISPLAY -> R.drawable.ic_companion_display
                MenuBrowseState.Icon.SEARCH -> R.drawable.ic_companion_search
            }
            return CompanionBrowse(browse.key,browse.title,"Orbit menu",iconResource=icon)
        }
        var onShoulder: ((Int) -> Unit)? = null
        override var isTabbed=false
            private set
        private var tabsView:android.view.View?=null
        private var sectionTitle:TextView?=null
        private var tabButtons=emptyList<TextView>()
        private var tabbedLayoutApplied = false
        fun tabs(title:String,subtitle:String,labels:List<String>,selected:Int,compactTabs:Boolean=false,choose:(Int)->Unit) {
            browse.scope(title)
            isTabbed=true
            val dp={ n:Int -> (n*activity.resources.displayMetrics.density).toInt() }
            val header=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(16),dp(22),dp(8))
            }
            val heading=LinearLayout(activity).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
            val titles=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            titles.addView(TextView(activity).apply { text=title;textSize=23f;setTextColor(Color.WHITE) })
            titles.addView(TextView(activity).apply { text=subtitle;textSize=14f;setTextColor(Color.rgb(148,161,180));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END })
            heading.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(Button(activity).apply { text="×";textSize=25f;setTextColor(Color.rgb(5,97,195));backgroundTintList=null;setBackgroundColor(Color.TRANSPARENT);setOnClickListener { dialog.dismiss() } },LinearLayout.LayoutParams(dp(48),dp(48)))
            header.addView(heading)
            dialog.setCustomTitle(header)
            val tabRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                val inset = dp(if (compactTabs) 2 else 8)
                setPadding(inset, 0, inset, dp(12))
            }
            tabsView = if (compactTabs) HorizontalScrollView(activity).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                isFocusable = false
                addView(tabRow, FrameLayout.LayoutParams(-2, -2))
            } else tabRow
            tabButtons=labels.mapIndexed { index,label ->
                TextView(activity).apply {
                    text=label;textSize=17f;gravity=android.view.Gravity.CENTER;isFocusable=false
                    if (compactTabs) isSingleLine = true
                    val inset = dp(if (compactTabs) 4 else 10)
                    setPadding(inset, dp(12), inset, dp(12))
                    setOnClickListener { choose(index) }
                    tabRow.addView(this,LinearLayout.LayoutParams(if (compactTabs) -2 else 0,-2,1f))
                }
            }
            sectionTitle=TextView(activity).apply { textSize=13f;setTextColor(Color.rgb(148,161,180));setPadding(dp(12),0,dp(12),dp(8)) }
            selectTab(selected)
        }
        fun selectTab(selected:Int) {
            tabButtons.forEachIndexed { index,tab ->
                tab.setTextColor(if(index==selected)Color.rgb(5,97,195) else Color.rgb(148,161,180))
                tab.background=GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius=8*activity.resources.displayMetrics.density
                    setStroke(if(index==selected)(2*activity.resources.displayMetrics.density).toInt() else 0,Color.rgb(5,97,195))
                }
            }
            if (tabsView !is HorizontalScrollView) return
            val tab = tabButtons.getOrNull(selected) ?: return
            tab.post {
                if (tabButtons.getOrNull(selected) === tab && tab.currentTextColor == Color.rgb(5,97,195)) {
                    tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), true)
                }
            }
        }
        fun clearTabs(title:String) {
            browse.scope(title)
            isTabbed=false;tabsView=null;sectionTitle=null;tabButtons=emptyList();onShoulder=null
            tabbedLayoutApplied = false
            dialog.setCustomTitle(null);dialog.setTitle(title)
        }
        private val frame = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val dialog = AlertDialog.Builder(activity,if(fullScreen)android.R.style.Theme_Material_NoActionBar_Fullscreen else 0)
            .setTitle("Orbit menu").setView(frame).setNegativeButton("Back", null).create()
        var revision=0
            private set
        private var goBack: () -> Unit = { dialog.dismiss() }
        private var keys: (Int, KeyEvent) -> Boolean = { _, _ -> false }
        private var handledBackDown: Long? = null
        init {
            dialog.setOnDismissListener { DualScreenCoordinator.refreshMedia() }
            dialog.setOnKeyListener { _, key, event ->
                if(key==KeyEvent.KEYCODE_BUTTON_START && activity is MainActivity && activity.handleStartShortcut(event))return@setOnKeyListener true
                if(fullScreen && (key==KeyEvent.KEYCODE_BUTTON_B || key==KeyEvent.KEYCODE_BACK)) {
                    if(event.action==KeyEvent.ACTION_DOWN && keys(key,event))handledBackDown=event.downTime
                    if(event.action==KeyEvent.ACTION_UP) {
                        if(!event.isCanceled && handledBackDown!=event.downTime)goBack()
                        handledBackDown=null
                    }
                    return@setOnKeyListener true
                }
                val direction = NavigationTabs.direction(key)
                if (direction != 0 && onShoulder != null) {
                    if (event.action == KeyEvent.ACTION_DOWN) onShoulder?.invoke(direction)
                    return@setOnKeyListener true
                }
                when (key) {
                    KeyEvent.KEYCODE_BUTTON_START -> { if (event.action == KeyEvent.ACTION_UP) dialog.dismiss(); true }
                    KeyEvent.KEYCODE_BUTTON_B -> { if (event.action == KeyEvent.ACTION_UP) goBack(); true }
                    else -> keys(key, event)
                }
            }
        }
        fun page(title: String, content: android.view.View, backLabel: String = "Back", back: () -> Unit = { dialog.dismiss() }, handle: (Int, KeyEvent) -> Boolean = { _, _ -> false }) {
            browse.page(title);register(this)
            revision++
            goBack = back; keys = handle
            if (isTabbed) {
                val tabs = checkNotNull(tabsView)
                val heading = checkNotNull(sectionTitle)
                heading.text = title
                if (tabs.parent !== frame || heading.parent !== frame) {
                    frame.removeAllViews()
                    frame.addView(tabs)
                    frame.addView(heading)
                } else if (frame.getChildAt(2) !== content && frame.childCount > 2) {
                    frame.removeViewAt(2)
                }
                if (content.parent !== frame) frame.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                dialog.setTitle(title)
                frame.removeAllViews()
                frame.addView(content, LinearLayout.LayoutParams(-1, -1))
            }
            if (!dialog.isShowing) {
                dialog.window?.apply {
                    if(fullScreen) {
                        setBackgroundDrawable(ColorDrawable(Color.rgb(12,17,25)))
                        setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
                        if(Build.VERSION.SDK_INT>=30) {
                            setDecorFitsSystemWindows(false)
                            attributes=attributes.apply { setFitInsetsTypes(0) }
                        }
                        decorView.setBackgroundColor(Color.rgb(12,17,25))
                        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    }
                    setWindowAnimations(0)
                }
                dialog.show()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                frame.layoutParams = frame.layoutParams.apply {
                    height = when {
                        fullScreen -> WindowManager.LayoutParams.MATCH_PARENT
                        compact -> minOf((120 * activity.resources.displayMetrics.density).toInt(), (activity.resources.displayMetrics.heightPixels * 0.32f).toInt())
                        else -> height(activity)
                    }
                }
            }
            if (isTabbed && !tabbedLayoutApplied) {
                tabbedLayoutApplied = true
                frame.setPadding((16*activity.resources.displayMetrics.density).toInt(),0,(16*activity.resources.displayMetrics.density).toInt(),0)
                if(!fullScreen) {
                    frame.layoutParams=frame.layoutParams.apply { height=(activity.resources.displayMetrics.heightPixels*0.60f).toInt() }
                    dialog.window?.apply {
                        setLayout((activity.resources.displayMetrics.widthPixels*0.94f).toInt(),WindowManager.LayoutParams.WRAP_CONTENT)
                        setBackgroundDrawable(GradientDrawable().apply { setColor(Color.rgb(19,27,39));cornerRadius=24*activity.resources.displayMetrics.density })
                        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply { dimAmount=0.35f }
                    }
                }
            }
            if (compact && !isTabbed) dialog.window?.apply {
                setLayout(minOf((320 * activity.resources.displayMetrics.density).toInt(), (activity.resources.displayMetrics.widthPixels * 0.48f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                visibility = if (backLabel.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
                text = backLabel
                setOnClickListener { goBack() }
            }
            FrontendNavigationBar.apply(activity,dialog.window)
            if(fullScreen && Build.VERSION.SDK_INT>=30)dialog.window?.insetsController?.hide(WindowInsets.Type.systemBars())
            DualScreenCoordinator.refreshMedia()
        }
        override fun showPage(title: String, content: android.view.View, backLabel: String,
            back: () -> Unit, handle: (Int, KeyEvent) -> Boolean) = page(title, content, backLabel, back, handle)

        fun liveActions(title: String, labels: List<() -> String>, choose: (Int) -> Unit, back: () -> Unit,
            initialSelection: Int = 0) {
            val rows = Rows(activity, isTabbed, smoothSelection, ::selectCompanionOption)
            rows.populate(labels.map { it() }) { index ->
                val expected=revision
                choose(index)
                if(dialog.isShowing && revision==expected) {
                    rows.updateLabels(labels.map { it() })
                    selectCompanionOption(labels[index]())
                }
            }
            page(title, rows.view, "← Back", back, rows::handle)
            rows.focus(initialSelection)
        }
        fun actions(title: String, actions: List<Pair<String, () -> Unit>>, back: (() -> Unit)? = null) {
            val rows = Rows(activity, isTabbed, smoothSelection, ::selectCompanionOption)
            rows.populate(actions.map { it.first }) { actions[it].second() }
            page(title, rows.view, if (back == null) "Close" else "← Back", back ?: { dialog.dismiss() }, rows::handle)
            if (compact && !isTabbed) {
                frame.layoutParams = frame.layoutParams.apply {
                    height = (minOf(actions.size * 52, 156) * activity.resources.displayMetrics.density).toInt()
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = android.view.View.GONE
            }
            rows.focus(0)
        }
    }
    private fun height(activity: Activity) = minOf((300 * activity.resources.displayMetrics.density).toInt(), (activity.resources.displayMetrics.heightPixels * 0.45f).toInt())
    fun show(activity: Activity, changed: () -> Unit = {}) {
        val window = MenuWindow(activity)
        window.page("Choose emulator", TextView(activity).apply { text = "Loading scanned systems…" }, "Close")
        Thread {
            val result = runCatching { LibraryRepository(activity).populatedSystems() }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing && window.dialog.isShowing) {
                    result.onSuccess { showIn(window, it, changed) }.onFailure {
                        window.page("Unable to load systems", TextView(activity).apply { text = it.message }, "Close")
                    }
                }
            }
        }.start()
    }
    fun showForSystem(activity: Activity, system: SystemSpec) {
        val window = MenuWindow(activity)
        showPlayers(window, system, {}, { window.dialog.dismiss() })
    }
    fun showForSystemIn(window: MenuWindow, system: SystemSpec, changed: () -> Unit, back: () -> Unit) {
        showPlayers(window, system, changed, back)
    }
    fun showForGame(window: MenuWindow, system: SystemSpec, game: Game, back: () -> Unit = { window.dialog.dismiss() }) {
        showPlayers(window, system, {}, back, game)
    }
    fun showIn(window: MenuWindow, catalogue: List<SystemSpec>, changed: () -> Unit = {}, back: () -> Unit = { window.dialog.dismiss() }) =
        showIn(window as PageHost, catalogue, changed, back)

    internal fun showIn(window: PageHost, catalogue: List<SystemSpec>, changed: () -> Unit = {}, back: () -> Unit) {
        val activity = window.activity
        val sorted = catalogue.sortedBy { it.title.lowercase() }
        if (sorted.isEmpty()) {
            window.showPage("No games found", TextView(activity).apply { text = "Add ROM folders and scan in ROM library to see your systems here." }, back = back)
            return
        }
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(activity).apply { hint = "Search systems"; setSingleLine() }
        val rows = Rows(activity,onSelection=window::selectCompanionOption)
        panel.addView(search); panel.addView(rows.view, LinearLayout.LayoutParams(-1, 0, 1f))
        fun filter(query: String) {
            val matches = sorted.filter { it.title.contains(query, true) || it.id.contains(query, true) }
            rows.populate(matches.map { it.title }) { index ->
                val scroll = rows.view.scrollY
                showPlayers(window, matches[index], changed, {
                    window.showPage("Choose system for emulator", panel, back = back, handle = rows::handle)
                    rows.focus(index); rows.view.post { rows.view.scrollTo(0, scroll) }
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        filter(""); window.showPage("Choose system for emulator", panel, back = back, handle = rows::handle)
        rows.view.post { rows.focus(0) }
    }
    private fun showPlayers(window: PageHost, system: SystemSpec, changed: () -> Unit, back: () -> Unit, game: Game? = null, showHidden:Boolean=false) {
        val activity=window.activity
        val repository=EmulatorProfileRepository(activity)
        val installed = InstalledEmulators.available(activity, repository.forSystem(system), showHidden)
            .associateWith { it.installed(activity) }
        val profiles = installed.keys.sortedWith(
            compareBy<EmulatorProfile> { "LIBRETRO" in it.stringExtras }.thenBy { !installed.getValue(it) }
        )
        var selected=if(game==null)repository.selected(system)?.id else repository.gameOverride(system,game)?.id
        val rows=Rows(activity, window.isTabbed, smoothSelection = window.smoothSelection || game != null,
            onSelection = window::selectCompanionOption)
        val offset=if(game==null)0 else 1
        fun refresh() { showPlayers(window,system,changed,back,game,showHidden) }
        fun toggleHidden(index:Int) {
            val profile=profiles.getOrNull(index-offset) ?: return
            runCatching { InstalledEmulators.hide(activity,profile,!InstalledEmulators.hidden(activity,profile)) }
                .onSuccess { refresh() }.onFailure { Toast.makeText(activity,"Could not save visibility",Toast.LENGTH_SHORT).show() }
        }
        fun labels()=(if(game==null)emptyList() else listOf("${if(selected==null) "✓  " else ""}Use system default")) +
            profiles.map { "${if(it.id==selected) "✓  " else ""}${it.name}${if(!installed.getValue(it)) " · Not installed" else ""}${if(InstalledEmulators.hidden(activity,it)) " · Hidden" else ""}" } +
            listOf(if(showHidden) "Hide hidden entries" else "Show hidden entries")
        rows.populate(labels()) { index ->
            if(index==profiles.size+offset) {
                showPlayers(window,system,changed,back,game,!showHidden)
            } else {
                val profile=profiles.getOrNull(index-offset)
                if(profile!=null && !profile.installed(activity)) {
                    Toast.makeText(activity,"Install ${profile.name} first",Toast.LENGTH_SHORT).show()
                    return@populate
                }
                runCatching {
                    if(game==null)repository.select(system,profile) else repository.selectForGame(system,game,profile)
                }.onSuccess {
                    selected=profile?.id;rows.updateLabels(labels());rows.focus(index);changed()
                }.onFailure { Toast.makeText(activity,"Unable to save emulator: ${it.message}",Toast.LENGTH_LONG).show() }
            }
        }
        rows.onLongPress=::toggleHidden
        val panel=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        panel.addView(TextView(activity).apply {
            text="Y to ${if(showHidden) "hide or restore" else "hide"}"
            textSize=12f;setTextColor(Color.rgb(148,161,180));setPadding(20,8,20,12)
        })
        if(profiles.isEmpty())panel.addView(TextView(activity).apply { text="No visible emulators for this system.";setTextColor(Color.WHITE);setPadding(20,12,20,12) })
        panel.addView(rows.view,LinearLayout.LayoutParams(-1,0,1f))
        window.showPage(if(game==null) "${system.title} emulator" else "${game.title} · Emulator",panel,if(game==null) "← Back" else "Close",back) { key,event ->
            if(key==KeyEvent.KEYCODE_BUTTON_Y) {
                if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)toggleHidden(rows.selectedIndex)
                true
            } else rows.handle(key,event)
        }
        rows.view.post { rows.focus((profiles.indexOfFirst { it.id==selected }+offset).coerceAtLeast(0)) }
    }
}
