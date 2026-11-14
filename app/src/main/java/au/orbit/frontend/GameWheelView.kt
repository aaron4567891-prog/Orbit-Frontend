package au.orbit.frontend

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Scrolls game artwork along a straight wheel, emphasizing the game at its center. */
internal class GameWheelView(
    context: Context,
    private val games: List<Game>,
    private val vertical: Boolean,
    initial: Int,
    private val theme: OrbitThemes.Theme,
    private val bindArtwork: (FrameLayout, TextView, Game) -> Unit,
    private val selected: (Int) -> Unit,
    private val open: (Int) -> Unit
) : RecyclerView(context) {
    private val layout = LinearLayoutManager(context, if (vertical) VERTICAL else HORIZONTAL, false)
    private val snap = LinearSnapHelper()
    private val scrollEase = PathInterpolator(0.22f, 0.12f, 0.25f, 1f)
    private val stepEase = DecelerateInterpolator()
    private var itemExtent = 0
    private var cursor = initial.coerceIn(games.indices)
    private var touchScrolling = false
    private var stepInFlight = false
    private var confirmDownTime: Long? = null
    private val queuedSteps = ArrayDeque<Int>()
    private val advance = Runnable { advanceStep() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        layoutManager = layout
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        adapter = object : Adapter<ViewHolder>() {
            override fun getItemCount() = games.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
                val label = TextView(context).apply {
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setAutoSizeTextTypeUniformWithConfiguration(16, 36, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
                val cell = FrameLayout(context).apply {
                    addView(label, FrameLayout.LayoutParams(-1, -1))
                }
                return object : ViewHolder(cell) {}.apply {
                    cell.setOnClickListener {
                        val position = bindingAdapterPosition
                        if (position == NO_POSITION) return@setOnClickListener
                        if (position == cursor) open(position) else select(position)
                    }
                }
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                val cell = holder.itemView as FrameLayout
                cell.layoutParams = LayoutParams(if (vertical) -1 else itemExtent, if (vertical) itemExtent else -1)
                val label = cell.getChildAt(0) as TextView
                label.text = games[position].title
                bindArtwork(cell, label, games[position])
                cell.isSelected = position == cursor
                label.setTextColor(theme.text)
            }
        }
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    clearSteps()
                    touchScrolling = true
                } else if (newState == SCROLL_STATE_IDLE) {
                    if (touchScrolling) {
                        touchScrolling = false
                        followCenter()
                        postOnAnimation { if (scrollState == SCROLL_STATE_IDLE && !stepInFlight) center(cursor) }
                    } else if (stepInFlight) {
                        stepInFlight = false
                        followCenter()
                        postOnAnimation(advance)
                    }
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (touchScrolling || stepInFlight) followCenter()
                updateWheelPositions()
            }
        })
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val extent = MeasureSpec.getSize(if (vertical) heightSpec else widthSpec)
        if (extent > 0) {
            val nextExtent = (extent / if (vertical) 5 else 3).coerceAtLeast(dp(48)).coerceAtMost(extent)
            if (nextExtent != itemExtent) {
                itemExtent = nextExtent
                val inset = (extent - itemExtent) / 2
                val trailingInset = extent - itemExtent - inset
                if (vertical) setPadding(0, inset, 0, trailingInset)
                else setPaddingRelative(inset, 0, trailingInset, 0)
                adapter?.notifyDataSetChanged()
                layout.scrollToPositionWithOffset(cursor, 0)
            }
        }
        super.onMeasure(widthSpec, heightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateWheelPositions()
    }

    private val viewportCenter get() = (if (vertical) height else width) / 2
    private fun itemCenter(item: View) = if (vertical) (item.top + item.bottom) / 2 else (item.left + item.right) / 2

    private fun updateWheelPositions() {
        if (itemExtent <= 0) return
        for (index in 0 until childCount) {
            val item = getChildAt(index)
            val distance = abs(itemCenter(item) - viewportCenter).toFloat() / itemExtent
            val emphasis = (1f - distance * 0.30f).coerceIn(0.4f, 1f)
            val scale = 0.65f + 0.35f * emphasis
            item.scaleX = scale
            item.scaleY = scale
            item.alpha = emphasis
        }
    }

    private fun moveCursor(position: Int) {
        if (cursor == position) return
        val previous = cursor
        cursor = position
        layout.findViewByPosition(previous)?.isSelected = false
        layout.findViewByPosition(cursor)?.isSelected = true
        selected(cursor)
    }

    private fun followCenter() {
        val center = snap.findSnapView(layout) ?: return
        val position = layout.getPosition(center)
        if (position in games.indices) moveCursor(position)
    }

    fun select(position: Int, animate: Boolean = true) {
        clearSteps()
        touchScrolling = false
        stopScroll()
        val target = position.coerceIn(games.indices)
        if (animate) center(target)
        else {
            moveCursor(target)
            layout.scrollToPositionWithOffset(target, 0)
        }
    }

    private fun center(target: Int, controllerStep: Boolean = false) {
        val anchor = snap.findSnapView(layout)
        val anchorPosition = anchor?.let(layout::getPosition) ?: NO_POSITION
        if (anchor == null || anchorPosition == NO_POSITION || itemExtent <= 0) {
            moveCursor(target)
            layout.scrollToPositionWithOffset(target, 0)
            return
        }
        val item = layout.findViewByPosition(target)
        val direction = if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1
        val targetCenter = item?.let(::itemCenter)
            ?: (itemCenter(anchor) + (target - anchorPosition) * itemExtent * direction)
        val delta = targetCenter - viewportCenter
        if (delta == 0) {
            moveCursor(target)
            return
        }
        stepInFlight = true
        val duration = if (controllerStep) 145 else (abs(delta) * 185f / itemExtent).toInt().coerceIn(90, 280)
        smoothScrollBy(if (vertical) 0 else delta, if (vertical) delta else 0,
            if (controllerStep) stepEase else scrollEase, duration)
    }

    // Touch movement selects the item passing through the center. Release settles
    // on that item instead of flinging past several games.
    override fun fling(velocityX: Int, velocityY: Int) = false

    private fun advanceStep() {
        if (stepInFlight || touchScrolling || scrollState != SCROLL_STATE_IDLE || !isShown) return
        while (queuedSteps.isNotEmpty()) {
            val next = cursor + queuedSteps.removeFirst()
            if (next in games.indices) {
                center(next, controllerStep = true)
                if (stepInFlight) return
            }
        }
    }

    private fun clearSteps() {
        removeCallbacks(advance)
        queuedSteps.clear()
        stepInFlight = false
    }

    override fun onDetachedFromWindow() {
        clearSteps()
        touchScrolling = false
        confirmDownTime = null
        super.onDetachedFromWindow()
    }

    fun handle(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            if (vertical) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT ->
                if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) 1 else -1
            if (vertical) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1
            else -> 0
        }
        if (step != 0) {
            if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                if (event.repeatCount == 0 || queuedSteps.isEmpty()) queuedSteps.addLast(step)
                advanceStep()
            } else if (event.action == KeyEvent.ACTION_UP && event.isCanceled) {
                queuedSteps.clear()
            }
            return true
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    confirmDownTime = event.downTime.takeUnless { event.isCanceled }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val confirmed = event.downTime == confirmDownTime && !event.isCanceled
                    confirmDownTime = null
                    if (confirmed) open(cursor)
                }
                true
            }
            else -> false
        }
    }
}
