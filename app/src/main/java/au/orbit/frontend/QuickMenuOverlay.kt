package au.orbit.frontend

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * The Y menus on the Systems and game screens. The selected item's logo or artwork lifts out of its
 * place towards the centre of the screen and the options slide up beneath it, in the theme's colours.
 */
internal class QuickMenuOverlay(
    private val activity: Activity,
    private val theme: OrbitThemes.Theme,
    /** Shown when there is no artwork to lift. */
    private val title: String,
    private val logo: Bitmap?,
    private val logoFilter: android.graphics.ColorFilter?,
    /** Where the logo sits before it lifts, in window coordinates. */
    private val from: RectF,
    initialActions: List<Action>,
    /** The view showing the artwork in its place; hidden while lifted so only one copy is ever seen. */
    private val source: View? = null,
    /** False keeps the artwork at its original size as it lifts (game artwork); true grows it (system logos). */
    private val grow: Boolean = true,
    private val closed: () -> Unit
) : View(activity) {
    class Action(val label: () -> String, val closes: Boolean = false, val run: () -> Unit)
    private class Page(val title: String?, val actions: List<Action>, val index: Int, val scroll: Int)

    private var actions = initialActions
    private var pageTitle: String? = null
    private val pages = ArrayDeque<Page>()
    private var scroll = 0
    private val maxVisible = 6
    private var index = 0
    private var progress = 0f
    private var closing = false
    private var animator: ValueAnimator? = null
    private val location = IntArray(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val logoRect = RectF()

    fun open() {
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        host.addView(this, FrameLayout.LayoutParams(-1, -1))
        elevation = 100f
        source?.alpha = 0f
        animateTo(1f, 240L)
    }

    /** Opens a further page inside the same panel; B returns to the page before it. */
    fun openPage(title: String, pageActions: List<Action>) {
        if (pageActions.isEmpty()) return
        pages.addLast(Page(pageTitle, actions, index, scroll))
        pageTitle = title; actions = pageActions; index = 0; scroll = 0
        invalidate()
    }

    /** Replaces the current page's rows in place (for drop-downs), keeping the highlighted row. */
    fun replaceActions(pageActions: List<Action>, keepIndex: Int = index) {
        if (pageActions.isEmpty()) return
        actions = pageActions
        index = keepIndex.coerceIn(actions.indices)
        // A collapsed drop-down shortens the list; keep the scroll within it.
        scroll = scroll.coerceIn(0, maxOf(0, actions.size - maxVisible))
        if (index < scroll) scroll = index
        if (index >= scroll + maxVisible) scroll = index - maxVisible + 1
        invalidate()
    }

    private fun back() {
        val page = pages.removeLastOrNull() ?: return dismiss()
        pageTitle = page.title; actions = page.actions; index = page.index; scroll = page.scroll
        invalidate()
    }

    /** Sends the artwork back to its place with the lift animation reversed, then closes. */
    fun dismiss() {
        if (closing) return
        closing = true
        animateTo(0f, 220L) { finish(null) }
    }

    /** Closes at once, for actions that leave this screen. */
    fun close(after: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        animator?.cancel()
        finish(after)
    }

    /** The original artwork returns in the same frame the lifted copy is removed, so nothing flashes. */
    private fun finish(after: (() -> Unit)?) {
        source?.alpha = 1f
        (parent as? ViewGroup)?.removeView(this)
        closed()
        after?.invoke()
    }

    private fun animateTo(target: Float, duration: Long, end: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    private fun move(step: Int) {
        index = (index + step + actions.size) % actions.size
        if (index < scroll) scroll = index
        if (index >= scroll + maxVisible) scroll = index - maxVisible + 1
        invalidate()
    }

    /** Takes every controller key while open: Up/Down choose, A selects, B goes back a page, Y closes. */
    fun handle(event: KeyEvent): Boolean {
        if (closing) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (up) choose(index)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (up) back()
            KeyEvent.KEYCODE_BUTTON_Y -> if (up) dismiss()
        }
        return true
    }

    private fun choose(position: Int) {
        val action = actions.getOrNull(position) ?: return
        if (action.closes) close(action.run) else { action.run(); invalidate() }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked != android.view.MotionEvent.ACTION_UP) return true
        val hit = itemRects().indexOfFirst { it.contains(event.x, event.y) }
        if (hit >= 0) { index = scroll + hit; choose(index) } else dismiss()
        return true
    }

    private val itemHeight get() = height * 0.075f
    private val headerHeight get() = if (pageTitle == null) 0f else itemHeight * 0.75f
    private val visibleCount get() = minOf(actions.size, maxVisible)

    private fun menuBounds(): RectF {
        val w = width.toFloat(); val h = height.toFloat()
        val menuWidth = w * if (w > h) 0.38f else 0.86f
        val top = h * if (w > h) 0.44f else 0.40f
        return RectF((w - menuWidth) / 2f, top, (w + menuWidth) / 2f,
            top + headerHeight + itemHeight * visibleCount + itemHeight * 0.3f)
    }

    /** The visible rows, from [scroll]. */
    private fun itemRects(): List<RectF> {
        val menu = menuBounds()
        return (0 until visibleCount).map { i ->
            RectF(menu.left + itemHeight * 0.15f, menu.top + headerHeight + itemHeight * (0.15f + i),
                menu.right - itemHeight * 0.15f, menu.top + headerHeight + itemHeight * (1.15f + i))
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(canvas: Canvas) {
        val p = progress
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(Color.argb((150 * p).toInt(), 0, 0, 0))

        // The logo lifts from its place in the view towards the centre, growing as it comes forward.
        getLocationInWindow(location)
        val start = RectF(from.left - location[0], from.top - location[1], from.right - location[0], from.bottom - location[1])
        val aspect = if (logo != null) logo.width / logo.height.toFloat() else 4f
        val targetHeight = if (grow) minOf(h * 0.2f, w * 0.44f / aspect) else start.height()
        val targetWidth = if (grow) targetHeight * aspect else start.width()
        // Above the menu, but never off the top of the screen for tall artwork.
        val targetCentreY = maxOf(menuBounds().top - h * 0.05f - targetHeight / 2f, targetHeight / 2f + h * 0.02f)
        val cx = lerp(start.centerX(), w / 2f, p); val cy = lerp(start.centerY(), targetCentreY, p)
        val lw = lerp(start.width(), targetWidth, p); val lh = lerp(start.height(), targetHeight, p)
        logoRect.set(cx - lw / 2f, cy - lh / 2f, cx + lw / 2f, cy + lh / 2f)

        // An accent glow behind the lifted logo.
        val glowRadius = maxOf(logoRect.width(), logoRect.height()) * 0.75f
        if (glowRadius > 1f) {
            paint.shader = RadialGradient(cx, cy, glowRadius,
                Color.argb((110 * p).toInt(), Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)),
                Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, glowRadius, paint)
            paint.shader = null
        }
        paint.alpha = 255
        if (logo != null) {
            paint.colorFilter = logoFilter
            canvas.drawBitmap(logo, null, logoRect, paint)
            paint.colorFilter = null
        } else {
            textPaint.color = theme.text
            textPaint.textSize = logoRect.height() * 0.6f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(title, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }

        // Options slide up beneath it on a panel in the theme's colours.
        val slide = (1f - p) * h * 0.06f
        canvas.save()
        canvas.translate(0f, slide)
        val menu = menuBounds()
        val radius = h * 0.022f
        paint.style = Paint.Style.FILL
        paint.color = theme.surface; paint.alpha = (235 * p).toInt()
        canvas.drawRoundRect(menu, radius, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (h * 0.003f).coerceAtLeast(1.5f)
        paint.color = theme.accent; paint.alpha = (170 * p).toInt()
        canvas.drawRoundRect(menu, radius, radius, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = h * 0.032f
        pageTitle?.let { header ->
            textPaint.color = theme.muted; textPaint.alpha = (255 * p).toInt()
            textPaint.textSize = h * 0.026f
            canvas.drawText(header, menu.left + h * 0.03f, menu.top + headerHeight * 0.72f, textPaint)
            textPaint.textSize = h * 0.032f
        }
        itemRects().forEachIndexed { row, item ->
            val i = scroll + row
            val action = actions.getOrNull(i) ?: return@forEachIndexed
            if (i == index) {
                paint.color = theme.accent; paint.alpha = (70 * p).toInt()
                canvas.drawRoundRect(item, radius * 0.7f, radius * 0.7f, paint)
                paint.style = Paint.Style.STROKE; paint.alpha = (230 * p).toInt()
                canvas.drawRoundRect(item, radius * 0.7f, radius * 0.7f, paint)
                paint.style = Paint.Style.FILL
            }
            textPaint.color = if (i == index) theme.text else theme.muted
            textPaint.alpha = (255 * p).toInt()
            val label = android.text.TextUtils.ellipsize(action.label(), textPaint, item.width() - h * 0.05f,
                android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, item.left + h * 0.025f, item.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        }
        canvas.restore()
    }
}
