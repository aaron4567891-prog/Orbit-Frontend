package au.orbit.frontend

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.exp

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
    private val layout = object : LinearLayoutManager(context, if (vertical) VERTICAL else HORIZONTAL, false) {
        override fun calculateExtraLayoutSpace(state: State, extraLayoutSpace: IntArray) {
            // Prepare the next cell in both directions before it enters the viewport.
            extraLayoutSpace[0] = itemExtent
            extraLayoutSpace[1] = itemExtent
        }
    }
    private val snap = LinearSnapHelper()
    private val scrollEase = PathInterpolator(0.22f, 0.12f, 0.25f, 1f)
    private var itemExtent = 0
    private var cursor = initial.coerceIn(games.indices)
    private var touchScrolling = false
    private var stepInFlight = false
    private var confirmDownTime: Long? = null
    private var controllerTarget: Int? = null
    private var controllerPosition = 0f
    private var controllerVelocity = 0f
    private var lastFrameNanos = 0L
    private var pixelRemainder = 0f
    private val controllerFrame = android.view.Choreographer.FrameCallback(::moveControllerFrame)
    internal val isWheelMoving get() = controllerTarget != null || scrollState != SCROLL_STATE_IDLE

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
                    setAutoSizeTextTypeUniformWithPresetSizes(intArrayOf(24, 28, 32, 36),
                        android.util.TypedValue.COMPLEX_UNIT_SP)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(12), dp(3), dp(12), dp(3))
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
                clearSteps()
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
            val distance = abs(itemCenter(item) - viewportCenter - pixelRemainder) / itemExtent
            val emphasis = (1f - distance * 0.30f).coerceIn(0.4f, 1f)
            val scale = 0.65f + 0.35f * emphasis
            item.scaleX = scale
            item.scaleY = scale
            item.alpha = emphasis
            item.translationX = if (vertical) 0f else -pixelRemainder
            item.translationY = if (vertical) -pixelRemainder else 0f
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

    private val axisDirection get() = if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1

    private fun center(target: Int) {
        val anchor = snap.findSnapView(layout)
        val anchorPosition = anchor?.let(layout::getPosition) ?: NO_POSITION
        if (anchor == null || anchorPosition == NO_POSITION || itemExtent <= 0) {
            moveCursor(target)
            layout.scrollToPositionWithOffset(target, 0)
            return
        }
        val item = layout.findViewByPosition(target)
        val targetCenter = item?.let(::itemCenter)
            ?: (itemCenter(anchor) + (target - anchorPosition) * itemExtent * axisDirection)
        val delta = targetCenter - viewportCenter
        if (delta == 0) {
            moveCursor(target)
            return
        }
        stepInFlight = true
        val duration = (abs(delta) * 185f / itemExtent).toInt().coerceIn(90, 280)
        smoothScrollBy(if (vertical) 0 else delta, if (vertical) delta else 0, scrollEase, duration)
    }

    // Touch movement selects the item passing through the center. Release settles
    // on that item instead of flinging past several games.
    override fun fling(velocityX: Int, velocityY: Int) = false

    private fun stepController(step: Int, repeat: Boolean) {
        val pending = controllerTarget
        // Keep a held key from building up a backlog as the wheel catches up.
        if (repeat && pending != null && (pending - cursor) * step >= 2) return
        val target = ((pending ?: cursor) + step).coerceIn(games.indices)
        if (target == (pending ?: cursor)) return
        if (pending == null) {
            touchScrolling = false
            stepInFlight = false
            stopScroll()
            val anchor = snap.findSnapView(layout)
            if (anchor == null || layout.getPosition(anchor) !in games.indices || itemExtent <= 0) {
                select(target, animate = false)
                return
            }
            controllerPosition = layout.getPosition(anchor) +
                (viewportCenter - itemCenter(anchor)).toFloat() / itemExtent * axisDirection
            android.view.Choreographer.getInstance().postFrameCallback(controllerFrame)
        }
        controllerTarget = target
    }

    private fun moveControllerFrame(frameNanos: Long) {
        val target = controllerTarget ?: return
        if (!isShown) {
            clearSteps()
            return
        }
        val dt = if (lastFrameNanos == 0L) 1f / 90f
            else ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.032f)
        lastFrameNanos = frameNanos
        // Carry velocity through quick presses and reversals so the wheel does not
        // jump to a new speed when the target changes.
        val offset = controllerPosition - target
        val damping = 22f
        val decay = exp(-damping * dt)
        val slope = controllerVelocity + damping * offset
        val next = (target + (offset + slope * dt) * decay).coerceIn(0f, games.lastIndex.toFloat())
        controllerVelocity = (controllerVelocity - damping * slope * dt) * decay
        val distance = next - controllerPosition
        controllerPosition = next
        val pixels = distance * itemExtent * axisDirection + pixelRemainder
        val wholePixels = pixels.roundToInt()
        pixelRemainder = pixels - wholePixels
        if (wholePixels != 0) scrollBy(if (vertical) 0 else wholePixels, if (vertical) wholePixels else 0)
        moveCursor(controllerPosition.roundToInt().coerceIn(games.indices))
        if (abs(target - controllerPosition) * itemExtent > 0.25f || abs(controllerVelocity) > 0.02f) {
            if (wholePixels == 0) updateWheelPositions()
            android.view.Choreographer.getInstance().postFrameCallback(controllerFrame)
        } else {
            val child = layout.findViewByPosition(target)
            val correction = if (child == null) 0 else itemCenter(child) - viewportCenter
            clearSteps()
            if (correction != 0) scrollBy(if (vertical) 0 else correction, if (vertical) correction else 0)
            moveCursor(target)
            updateWheelPositions()
        }
    }

    private fun clearSteps() {
        android.view.Choreographer.getInstance().removeFrameCallback(controllerFrame)
        controllerTarget = null
        controllerVelocity = 0f
        lastFrameNanos = 0L
        pixelRemainder = 0f
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
                stepController(step, event.repeatCount > 0)
            } else if (event.action == KeyEvent.ACTION_UP && event.isCanceled) {
                if (controllerTarget != null) controllerTarget = cursor
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
