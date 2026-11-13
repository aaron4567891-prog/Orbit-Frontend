package au.orbit.frontend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView

/** Smoothly reveals a focused tile only when it crosses the viewport edge. */
class SystemCarousel(context: Context) : HorizontalScrollView(context) {
    private var focusScrolling = false
    private var animation: ValueAnimator? = null
    private var restoring = false
    private var pendingRestore: Pair<Int, () -> Unit>? = null
    init {
        defaultFocusHighlightEnabled = false
        isHorizontalScrollBarEnabled = false
        // Focus reveal must not start a second Android scroller over our animation.
        isSmoothScrollingEnabled = false
    }
    override fun requestChildFocus(child: View, focused: View) {
        focusScrolling = true
        try { super.requestChildFocus(child, focused) } finally { focusScrolling = false }
    }
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean) = false
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val cards = getChildAt(0) as? ViewGroup
            var focused = findFocus()
            while (focused != null && focused.parent !== cards) focused = focused.parent as? View
            if (cards != null && focused != null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // Android's focus search can skip an offscreen tile to the left.
                    // Choose the adjacent child directly in either direction.
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val index = (cards.indexOfChild(focused) + step).coerceIn(0, cards.childCount - 1)
                    val target = cards.getChildAt(index)
                    target.requestFocus()
                    reveal(target)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun scrollTo(x: Int, y: Int) {
        if (!focusScrolling && !restoring) super.scrollTo(x, y)
    }
    fun moveTo(left: Int) {
        animation?.cancel()
        animation = ValueAnimator.ofInt(scrollX, left).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }
    fun reveal(tile: View) {
        if (restoring) return
        animation?.cancel()
        val left = scrollX + paddingLeft
        val right = scrollX + width - paddingRight
        when {
            tile.left < left -> moveTo(tile.left - paddingLeft)
            tile.right > right -> moveTo(tile.right - width + paddingRight)
        }
    }
    fun restore(position: Int, focus: () -> Unit) {
        animation?.cancel()
        restoring = true
        pendingRestore = position to focus
        requestLayout()
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val restore = pendingRestore
        val position = restore?.first ?: scrollX
        restoring = true
        try {
            super.onLayout(changed, l, t, r, b)
            restore?.second?.invoke()
            // Apply after measuring but before drawing, including native focus/layout work.
            super.scrollTo(position, 0)
            pendingRestore = null
        } finally { restoring = false }
    }
    override fun onDetachedFromWindow() { animation?.cancel(); super.onDetachedFromWindow() }
}
