package au.orbit.frontend

import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The release that opened this window belongs to the previous page. */
internal class ArtworkConfirmPress {
    private var pressedAt: Long? = null

    fun released(event: KeyEvent): Boolean = released(event.action, event.downTime, event.repeatCount, event.isCanceled)

    internal fun released(action: Int, downTime: Long, repeatCount: Int = 0, canceled: Boolean = false): Boolean {
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) pressedAt = downTime
        if (action != KeyEvent.ACTION_UP) return false
        val confirmed = pressedAt == downTime && !canceled
        pressedAt = null
        return confirmed
    }
}

internal fun artworkOutline(context: Context) = GradientDrawable().apply {
    val density = context.resources.displayMetrics.density
    setColor(Color.TRANSPARENT)
    setStroke((2 * density).toInt(), OrbitThemes.current().accent)
    cornerRadius = 6 * density
}

internal fun AlertDialog.styleArtworkWindow(context: Context) {
    window?.apply {
        setWindowAnimations(0)
        setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.rgb(19, 27, 39))
            cornerRadius = 24 * context.resources.displayMetrics.density
        })
        attributes = attributes.apply { dimAmount = 0.35f }
    }
}

internal class ArtworkSearchPicker(
    private val activity: MainActivity,
    private val role: String,
    private val initialQuery: String,
    private val screens: Set<String>,
    private val apply: (ByteArray) -> Unit
) {
    private val client = ArtworkSearchClient(activity.applicationContext)
    private val worker = Executors.newSingleThreadExecutor()
    private var job: Future<*>? = null
    private var generation = 0
    private var results = emptyList<ArtworkSearchClient.Result>()
    private val thumbnails = mutableMapOf<Int, Bitmap>()
    private var selected = -1
    private var downloading = false

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private inner class Tile : LinearLayout(activity) {
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(activity).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 12f
            gravity = Gravity.CENTER
        }
        init {
            orientation = VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            layoutParams = AbsListView.LayoutParams(-1, dp(118))
            addView(image, LayoutParams(-1, 0, 1f))
            addView(label, LayoutParams(-1, -2))
        }
    }

    fun show(embed: ((View, View, (KeyEvent) -> Boolean, () -> Unit) -> Unit)? = null, closePage: () -> Unit = {}) {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val entry = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val query = EditText(activity).apply {
            hint = "Enter a name or subject"
            setSingleLine(true)
            setText(initialQuery)
            setSelection(length())
            filters = arrayOf(InputFilter.LengthFilter(120))
            showSoftInputOnFocus = false
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val search = Button(activity).apply { text = "Search"; isAllCaps = false; SelectionStyle.apply(this) }
        entry.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
        entry.addView(search)
        panel.addView(entry)
        val status = TextView(activity).apply {
            text = "Enter a name and choose Search."
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(6))
        }
        panel.addView(status)
        val grid = GridView(activity).apply {
            numColumns = 3
            horizontalSpacing = dp(6)
            verticalSpacing = dp(6)
            defaultFocusHighlightEnabled = false
            selector = artworkOutline(activity)
            setDrawSelectorOnTop(true)
        }
        val gridSize = if (embed != null) LinearLayout.LayoutParams(-1, 0, 1f)
            else LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.28f).toInt())
        panel.addView(grid, gridSize)
        val actions = LinearLayout(activity).apply { gravity = Gravity.END }
        val sourcePage = Button(activity).apply { text = "View source"; isAllCaps = false; SelectionStyle.apply(this); isEnabled = false }
        val download = Button(activity).apply { text = "Download & apply"; isAllCaps = false; SelectionStyle.apply(this); isEnabled = false }
        actions.addView(sourcePage)
        actions.addView(download)
        panel.addView(actions)
        val keyboardHeightDp = activity.resources.configuration.screenHeightDp / 3
        val keyboard = CompactAppKeyboard(activity, query, keyHeightDp = (keyboardHeightDp / 4 - 2).coerceIn(22, 38)) {
            grid.visibility = View.VISIBLE
            actions.visibility = View.VISIBLE
            search.requestFocus()
        }
        panel.addView(keyboard)
        fun showKeyboard() {
            if (!query.isEnabled) return
            query.requestFocus()
            if (embed == null) grid.visibility = View.GONE
            actions.visibility = View.GONE
            keyboard.show()
        }
        query.setOnClickListener { showKeyboard() }

        val adapter = object : BaseAdapter() {
            override fun getCount() = results.size
            override fun getItem(position: Int) = results[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tile = convertView as? Tile ?: Tile()
                val result = getItem(position)
                tile.image.setImageBitmap(thumbnails[position])
                val label = listOfNotNull(result.dimensions?.label, result.title).joinToString(" · ")
                tile.label.text = label
                tile.contentDescription = label
                tile.background = if (position == selected) artworkOutline(activity) else null
                return tile
            }
        }
        grid.adapter = adapter
        val dialog = if (embed == null) AlertDialog.Builder(activity.popupContext()).setTitle("Search artwork")
            .setView(panel).setNegativeButton("Cancel", null).create() else null
        fun close() { if (dialog == null) closePage() else dialog.dismiss() }
        fun back() { if (keyboard.visibility == View.VISIBLE) keyboard.hide() else close() }
        fun cancelWork() {
            generation++
            job?.cancel(true)
            client.cancel()
        }
        fun onCurrent(ticket: Int, update: () -> Unit) {
            activity.runOnUiThread {
                if (ticket == generation && (dialog?.isShowing ?: panel.isAttachedToWindow) && !activity.isDestroyed) update()
            }
        }
        fun refreshActions() {
            val result = results.getOrNull(selected)
            download.isEnabled = result != null && !downloading
            sourcePage.isEnabled = result?.sourcePage != null && !downloading
            download.alpha = if (download.isEnabled) 1f else 0.5f
            sourcePage.alpha = if (sourcePage.isEnabled) 1f else 0.5f
        }
        fun clearResults() {
            selected = -1
            results = emptyList()
            thumbnails.clear()
            adapter.notifyDataSetChanged()
            refreshActions()
        }
        search.setOnClickListener {
            val term = query.text.toString().trim()
            if (term.isBlank()) {
                status.text = "Enter a name or subject to search."
                showKeyboard()
                return@setOnClickListener
            }
            if (keyboard.visibility == View.VISIBLE) keyboard.hide()
            cancelWork()
            clearResults()
            val targets = if (role == "hero" || role == "background") ArtworkDisplayFit.targets(activity, screens) else emptyList()
            val fitLabel = targets.takeIf { it.isNotEmpty() }?.joinToString(" + ") { it.label }
            val ticket = generation
            status.text = "Searching artwork sources…"
            job = worker.submit {
                val response = try { client.search(term, role, targets) } catch (error: Exception) {
                    onCurrent(ticket) { status.text = "Could not search artwork sources. Try again." }
                    return@submit
                }
                val found = response.images
                onCurrent(ticket) {
                    results = found
                    adapter.notifyDataSetChanged()
                    val unavailable = response.failedSources.joinToString(", ")
                    val summary = when {
                        found.isEmpty() && unavailable.isNotEmpty() -> "No images found. Some sources were unavailable."
                        found.isEmpty() -> "No images found. Try another name."
                        fitLabel != null -> "Best fit first · $fitLabel"
                        else -> "${found.size} images"
                    }
                    status.text = if (found.isNotEmpty() && unavailable.isNotEmpty()) "$summary · Unavailable: $unavailable" else summary
                    if (found.isNotEmpty() && keyboard.visibility != View.VISIBLE) {
                        grid.requestFocus()
                        grid.setSelection(0)
                    }
                }
                found.forEachIndexed { index, result ->
                    if (Thread.currentThread().isInterrupted) return@submit
                    val bitmap = runCatching { client.thumbnail(result) }.getOrNull() ?: return@forEachIndexed
                    onCurrent(ticket) {
                        thumbnails[index] = bitmap
                        (grid.getChildAt(index - grid.firstVisiblePosition) as? Tile)?.image?.setImageBitmap(bitmap)
                    }
                }
            }
        }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search.performClick(); true } else false
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            if (downloading) return@setOnItemClickListener
            val result = results.getOrNull(position) ?: return@setOnItemClickListener
            selected = position
            adapter.notifyDataSetChanged()
            status.text = listOfNotNull(result.title, result.dimensions?.label, result.provider, result.credit).filter(String::isNotBlank).joinToString(" · ")
            refreshActions()
        }
        sourcePage.setOnClickListener {
            val url = results.getOrNull(selected)?.sourcePage ?: return@setOnClickListener
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
                .onFailure { status.text = "Could not open the image source." }
        }
        download.setOnClickListener {
            val result = results.getOrNull(selected) ?: return@setOnClickListener
            cancelWork()
            val ticket = generation
            downloading = true
            search.isEnabled = false
            query.isEnabled = false
            refreshActions()
            status.text = "Downloading ${result.title}…"
            job = worker.submit {
                val bytes = runCatching { client.download(result) }
                onCurrent(ticket) {
                    bytes.onSuccess { close(); apply(it) }.onFailure {
                        downloading = false
                        search.isEnabled = true
                        query.isEnabled = true
                        refreshActions()
                        status.text = it.message ?: "Could not download this image."
                    }
                }
            }
        }
        val confirm = ArtworkConfirmPress()
        val handle: (KeyEvent) -> Boolean = { event ->
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (confirm.released(event)) {
                        when (val focus = dialog?.currentFocus ?: activity.currentFocus) {
                            grid -> if (grid.selectedItemPosition in results.indices) {
                                grid.performItemClick(grid.selectedView, grid.selectedItemPosition, grid.selectedItemId)
                            }
                            query -> showKeyboard()
                            else -> if (focus?.isEnabled == true) focus.performClick()
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
                    true
                }
                else -> false
            }
        }
        refreshActions()
        if (embed != null) {
            panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    cancelWork()
                    worker.shutdownNow()
                }
            })
            embed(panel, query, handle, ::back)
            return
        }
        checkNotNull(dialog)
        dialog.setOnKeyListener { _, _, event -> handle(event) }
        dialog.setOnDismissListener { cancelWork(); worker.shutdownNow() }
        val host = activity.popupContext()
        dialog.styleArtworkWindow(host)
        dialog.show()
        activity.trackPopup(dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Size it to the screen it is shown on, which may be the other screen.
        dialog.window?.setLayout((host.resources.displayMetrics.widthPixels * 0.92f).toInt(), -2)
        FrontendNavigationBar.apply(activity.popupContext(), dialog.window)
        query.requestFocus()
    }
}
