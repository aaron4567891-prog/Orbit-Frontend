package au.orbit.frontend

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.util.LruCache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal class AchievementBadgeGrid(
    private val menu: EmulatorSettings.MenuWindow,
    private val badges: List<RetroAchievements.Badge>,
    private val changeSet: () -> Unit,
    private val openBadge: (RetroAchievements.Badge) -> Unit
) {
    private companion object {
        val images = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        val grayscale = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    private val host = menu.activity
    private fun dp(value: Int) = (host.resources.displayMetrics.density * value).toInt()
    private val calls = mutableSetOf<Call>()
    private val grid = GridView(host).apply {
        columnWidth = dp(100)
        numColumns = GridView.AUTO_FIT
        verticalSpacing = dp(6)
        horizontalSpacing = dp(6)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        setSelector(android.R.color.transparent)
        defaultFocusHighlightEnabled = false
        isFocusableInTouchMode = true
    }
    private val detail = TextView(host).apply {
        setTextColor(Color.WHITE)
        minLines = 2
        maxLines = 3
        textSize = 14f
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private val change = Button(host).apply {
        text = "Change game match"
        isAllCaps = false
        SelectionStyle.apply(this)
        setOnClickListener { changeSet() }
    }

    fun show(title: String, back: () -> Unit) {
        val header = LinearLayout(host).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(completionBar("Unlocked", badges.count { it.earned }, Color.rgb(82, 169, 255)),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(completionBar("Hardcore", badges.count { it.hardcore }, Color.rgb(255, 204, 103)),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(change, LinearLayout.LayoutParams(-2, dp(42)))
        }
        val page = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(detail)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    calls.forEach(Call::cancel)
                    calls.clear()
                }
            })
        }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = badges.size
            override fun getItem(position: Int) = badges[position]
            override fun getItemId(position: Int) = badges[position].id.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ((convertView as? BadgeTile) ?: BadgeTile()).apply { bind(getItem(position)) }
        }
        grid.setOnItemClickListener { _, _, position, _ -> openBadge(badges[position]) }
        grid.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val badge = badges[position]
                detail.text = listOf(badge.progressSummary(), badge.description)
                    .filter(String::isNotBlank).joinToString("\n")
            }
        })
        menu.page(title, page, back = back) { key, event ->
            if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_UP &&
                grid.hasFocus() && grid.selectedItemPosition < grid.numColumns) {
                change.requestFocus()
                true
            } else if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN && change.hasFocus()) {
                grid.requestFocus()
                true
            } else if (key == KeyEvent.KEYCODE_BUTTON_A && (change.hasFocus() || grid.hasFocus())) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    if (change.hasFocus()) change.performClick()
                    else badges.getOrNull(grid.selectedItemPosition)?.let(openBadge)
                }
                true
            } else false
        }
        grid.requestFocus()
        grid.setSelection(0)
    }

    private fun completionBar(label: String, count: Int, color: Int) = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(host).apply {
            text = "$label $count/${badges.size}"
            textSize = 13f
            setTextColor(Color.WHITE)
        })
        addView(ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = badges.size
            progress = count
            progressTintList = ColorStateList.valueOf(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(6)))
    }

    @SuppressLint("ViewConstructor")
    private inner class BadgeTile : LinearLayout(host) {
        private val icon = ImageView(host).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val title = TextView(host).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private var request: Call? = null
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            SelectionStyle.apply(this)
            addView(icon, LayoutParams(dp(48), dp(48)))
            addView(title, LayoutParams(-1, dp(34)))
        }

        fun bind(badge: RetroAchievements.Badge) {
            title.text = badge.title
            contentDescription = "${badge.title}, ${badge.status}, ${badge.points} points"
            icon.colorFilter = if (badge.earned) null else grayscale
            icon.alpha = if (badge.earned) 1f else 0.50f
            title.setTextColor(if (badge.hardcore) Color.rgb(255, 204, 103) else Color.WHITE)
            val cached = badge.image.takeIf(String::isNotEmpty)?.let(images::get)
            icon.setImageBitmap(cached)
            if (cached == null) icon.setImageResource(if (badge.earned)
                android.R.drawable.btn_star_big_on else android.R.drawable.ic_lock_lock)
            request?.let { it.cancel(); calls.remove(it) }
            request = null
            if (cached != null || badge.image.isEmpty()) return
            val call = RetroAchievements.imageRequest(badge.image)
            request = call
            calls.add(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = applyImage(call, null)
                override fun onResponse(call: Call, response: Response) {
                    val bitmap = response.use {
                        if (it.isSuccessful) runCatching { BitmapFactory.decodeStream(it.body.byteStream()) }.getOrNull() else null
                    }
                    if (bitmap != null) images.put(badge.image, bitmap)
                    applyImage(call, bitmap)
                }
            })
        }

        private fun applyImage(call: Call, bitmap: Bitmap?) {
            host.runOnUiThread {
                calls.remove(call)
                if (request === call && !call.isCanceled() && bitmap != null) icon.setImageBitmap(bitmap)
            }
        }
    }
}
