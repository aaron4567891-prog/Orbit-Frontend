package au.orbit.frontend

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.*

object NavigationTabs {
    fun direction(key: Int) = when (key) {
        KeyEvent.KEYCODE_BUTTON_L1 -> -1
        KeyEvent.KEYCODE_BUTTON_R1 -> 1
        else -> 0
    }
    fun openMain(activity: Activity, tab: Int) {
        activity.startActivity(Intent(activity, MainActivity::class.java).putExtra("navigationTab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        activity.overridePendingTransition(0, 0)
    }
    fun openApps(activity: Activity) {
        activity.startActivity(Intent(activity, AppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        activity.overridePendingTransition(0, 0)
    }
    fun select(view:android.view.View,active:Int) {
        val row=(view as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout ?: return
        val density=view.resources.displayMetrics.density
        for(index in 0 until row.childCount) {
            val button=row.getChildAt(index) as? Button ?: continue
            button.setTextColor(if(index==active)Color.rgb(5,97,195) else Color.WHITE)
            button.background=GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius=10*density
                setStroke(density.toInt().coerceAtLeast(1),if(index==active)Color.rgb(5,97,195) else Color.rgb(38,49,65))
            }
        }
    }
    fun view(activity: Activity, active: Int, choose: (Int) -> Unit): android.view.View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; defaultFocusHighlightEnabled = false
            isSmoothScrollingEnabled = false
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf("Systems", "Android apps", "Settings", "Themes", "Scraping").forEachIndexed { index, label ->
            row.addView(Button(activity).apply {
                text = label; isAllCaps = false; textSize = 14f
                isFocusable = false
                isFocusableInTouchMode = false
                setTextColor(if (active == index) Color.rgb(5, 97, 195) else Color.WHITE)
                backgroundTintList = null; defaultFocusHighlightEnabled = false
                fun paint(focused: Boolean) {
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), if (focused || active == index) Color.rgb(5, 97, 195) else Color.rgb(38,49,65))
                    }
                }
                paint(false); setOnFocusChangeListener { _, focused -> paint(focused) }
                setOnClickListener { choose(index) }
            }, LinearLayout.LayoutParams(dp(140), dp(40)).apply { setMargins(0, 0, dp(8), 0) })
        }
        scroll.addView(row)
        return scroll
    }
}
