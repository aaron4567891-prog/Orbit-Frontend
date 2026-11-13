package au.orbit.frontend

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Browses catalog images in memory before the user chooses to download a pack. */
internal class NeoAssetsPackGallery(
    private val activity: Activity,
    catalog: List<JSONObject>,
    private val exit: () -> Unit,
    private val download: (String) -> Unit
) {
    private companion object {
        val worker = Executors.newFixedThreadPool(2)
    }

    private data class Pack(
        val folder: String, val name: String, val author: String,
        val description: String, val previews: List<String>
    )

    private val packs = catalog.mapNotNull { item ->
        val folder = item.optString("folder")
        if (!folder.matches(Regex("[a-zA-Z0-9_-]+"))) return@mapNotNull null
        val previews = item.optJSONArray("backgrounds")?.let { backgrounds ->
            List(backgrounds.length()) { backgrounds.optString(it) }
        }.orEmpty()
            .filter { it.startsWith("packs/$folder/backgrounds/") }.distinct()
        Pack(folder, item.optString("name", folder), item.optString("author"), item.optString("description"), previews)
    }
    private val client = ScraperClient(activity.applicationContext, "NeoAssets")
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = mutableMapOf<String, MutableMap<ImageView, (Boolean) -> Unit>>()
    private val unavailable = mutableSetOf<String>()
    private var selectedPack = 0
    private var details: View? = null

    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val grid = GridView(activity).apply {
        numColumns = if (resources.configuration.screenWidthDp >= 720) 3 else 2
        horizontalSpacing = dp(10)
        verticalSpacing = dp(10)
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        selector = artworkOutline(activity)
        setDrawSelectorOnTop(true)
        adapter = object : BaseAdapter() {
            override fun getCount() = packs.size
            override fun getItem(position: Int) = packs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val card = convertView as? PackCard ?: PackCard()
                val pack = getItem(position)
                card.name.text = pack.name
                card.author.text = pack.author
                preview(card.image, pack.previews.firstOrNull())
                return card
            }
        }
        setOnItemClickListener { _, _, position, _ ->
            selectedPack = position
            showPack(packs[position])
        }
    }
    val initialFocus: View = if (packs.isEmpty()) view else grid

    init {
        if (packs.isEmpty()) {
            view.isFocusableInTouchMode = true
            view.addView(label("No artwork packs are available.", 16f))
        } else view.addView(grid, LinearLayout.LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
    }
    private fun button(title: String, click: () -> Unit) = Button(activity).apply {
        text = title
        isAllCaps = false
        isFocusableInTouchMode = true
        setTextColor(Color.WHITE)
        SelectionStyle.apply(this)
        setOnClickListener { click() }
    }

    private inner class PackCard : LinearLayout(activity) {
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val name = label("", 17f).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val author = label("", 13f, Color.rgb(148, 161, 180)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        init {
            orientation = VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            SelectionStyle.apply(this)
            layoutParams = AbsListView.LayoutParams(-1, dp(168))
            addView(image, LayoutParams(-1, 0, 1f))
            addView(name)
            addView(author)
        }
    }

    private fun showPack(pack: Pack) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        panel.addView(label(pack.name, 22f).apply { typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (pack.author.isNotBlank()) panel.addView(label("By ${pack.author}", 14f, Color.rgb(148, 161, 180)))
        if (pack.description.isNotBlank()) panel.addView(label(pack.description, 14f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        panel.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val counter = label("", 14f).apply { gravity = Gravity.CENTER }
        var position = 0
        fun showPreview() {
            val path = pack.previews.getOrNull(position)
            val count = "${position + 1} / ${pack.previews.size}"
            counter.text = if (path == null) "No previews available" else count
            image.contentDescription = if (path == null) null else "${pack.name} artwork preview $count"
            if (path != null) preview(image, path) { loaded ->
                if (!loaded) counter.text = "Preview unavailable · $count"
            }
        }
        val previous = button("‹ Prev") {
            position = (position + pack.previews.size - 1) % pack.previews.size
            showPreview()
        }
        val next = button("Next ›") {
            position = (position + 1) % pack.previews.size
            showPreview()
        }
        val downloadButton = button("Download pack") { download(pack.folder) }
        if (pack.previews.size > 1) actions.addView(previous)
        actions.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        if (pack.previews.size > 1) actions.addView(next)
        actions.addView(downloadButton)
        panel.addView(actions)
        details = panel
        grid.visibility = View.GONE
        view.addView(panel, LinearLayout.LayoutParams(-1, -1))
        showPreview()
        (if (pack.previews.size > 1) next else downloadButton).requestFocus()
    }

    fun back() {
        val panel = details
        if (panel == null) {
            exit()
        } else {
            view.removeView(panel)
            details = null
            grid.visibility = View.VISIBLE
            grid.requestFocus()
            grid.setSelection(selectedPack)
        }
    }

    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_A || !grid.hasFocus()) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
            val position = grid.selectedItemPosition.takeIf { it in packs.indices } ?: selectedPack
            grid.performItemClick(grid.selectedView, position, grid.getItemIdAtPosition(position))
        }
        return true
    }

    private fun preview(target: ImageView, path: String?, finished: (Boolean) -> Unit = {}) {
        target.tag = path
        val cached = path?.let(cache::get)
        target.setImageBitmap(cached)
        if (path == null || path in unavailable || cached != null) {
            finished(cached != null)
            return
        }
        pending[path]?.let { waiting -> waiting[target] = finished; return }
        pending[path] = mutableMapOf(target to finished)
        worker.execute {
            val bitmap = runCatching {
                val bytes = client.packPreview(path)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
            activity.runOnUiThread {
                val waiting = pending.remove(path).orEmpty()
                if (activity.isDestroyed) return@runOnUiThread
                if (bitmap == null) unavailable.add(path) else cache.put(path, bitmap)
                waiting.forEach { (image, callback) ->
                    if (image.tag == path) {
                        image.setImageBitmap(bitmap)
                        callback(bitmap != null)
                    }
                }
            }
        }
    }
}
