package au.orbit.frontend

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

/** The same controller-friendly search keyboard on both displays. */
class CompactAppKeyboard(private val activity:Activity,private val search:EditText,private val keyHeightDp:Int=38,
    private val finished:()->Unit):LinearLayout(activity) {
    private val blue=Color.rgb(5,97,195)
    private var numericKeys=false
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    init { orientation=VERTICAL;visibility=View.GONE }
    fun hide() {
        this.visibility = View.GONE
        finished()
    }
    fun show() {
        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        this.visibility = View.VISIBLE
        this.removeAllViews()
        val rows = if (numericKeys) listOf("1234567890", "-_.@/+:()", "&'!?%#=,") else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        fun addRow(labels: List<String>, action: (String) -> Unit) {
            val row = LinearLayout(activity)
            labels.forEach { label ->
                row.addView(Button(activity).apply {
                    text = label; isAllCaps = false; textSize = minOf(23f, keyHeightDp - 6f); setTextColor(Color.WHITE)
                    minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    setPadding(0, 0, 0, 0); backgroundTintList = null; defaultFocusHighlightEnabled = false
                    fun paint(focus: Boolean) { background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT); cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), if (focus) blue else Color.rgb(38,49,65))
                    } }
                    paint(false); setOnFocusChangeListener { _, focus -> paint(focus) }
                    setOnClickListener { action(label) }
                }, LinearLayout.LayoutParams(0, dp(keyHeightDp), 1f).apply { setMargins(dp(2), dp(2), dp(2), 0) })
            }
            this@CompactAppKeyboard.addView(row)
        }
        fun insert(value: String) {
            val start = search.selectionStart.coerceAtLeast(0)
            val end = search.selectionEnd.coerceAtLeast(start)
            search.text.replace(start, end, value)
            search.setSelection((start + value.length).coerceAtMost(search.text.length))
        }
        rows.forEach { keys -> addRow(keys.map(Char::toString)) { insert(it) } }
        addRow(listOf(if (numericKeys) "ABC" else "123", "Space", "⌫", "Clear", "Done")) { key ->
            when (key) {
                "ABC", "123" -> { numericKeys = !numericKeys; show() }
                "Space" -> insert(" ")
                "Clear" -> search.text.clear()
                "⌫" -> {
                    val end = search.selectionEnd.coerceAtLeast(0)
                    val start = search.selectionStart.coerceAtLeast(0)
                    if (end > start) search.text.delete(start, end)
                    else if (start > 0) search.text.delete(start - 1, start)
                }
                "Done" -> hide()
            }
        }
        post { (getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus() }
    }
}
