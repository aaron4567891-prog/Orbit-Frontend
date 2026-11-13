package au.orbit.frontend

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

/** Outline focus shared by standalone Orbit controls. */
object SelectionStyle {
    fun apply(view: View) {
        val density = view.resources.displayMetrics.density
        fun outline(selected: Boolean) = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = 10 * density
            setStroke((2 * density).toInt().coerceAtLeast(1), if(selected) Color.rgb(5,97,195) else Color.rgb(38,49,65))
        }
        view.backgroundTintList = null
        view.defaultFocusHighlightEnabled = false
        view.stateListAnimator = null
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), outline(true))
            addState(intArrayOf(android.R.attr.state_pressed), outline(true))
            addState(intArrayOf(android.R.attr.state_selected), outline(true))
            addState(intArrayOf(), outline(false))
        }
    }
}
