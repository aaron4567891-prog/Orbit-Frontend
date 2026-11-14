package au.orbit.frontend

import android.content.Context
import android.util.TypedValue
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
    private val artworkScale: Float,
    initial: Int,
    private val theme: OrbitThemes.Theme,
    private val bindArtwork: (FrameLayout, TextView, Game, ((() -> Unit) -> Unit)?) -> Unit,
    private val selected: (Int) -> Unit,
    private val settled: () -> Unit,
    private val motionChanged: (Boolean) -> Unit,
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
    private var moving = false
    private var stepInFlight = false
    private var confirmDownTime: Long? = null
    private var controllerTarget: Int? = null
    private var controllerPosition = 0f
    private var controllerVelocity = 0f
    private var lastFrameNanos = 0L
    private var pixelRemainder = 0f
    private val controllerFrame = android.view.Choreographer.FrameCallback(::moveControllerFrame)
    private val artworkUpdates = LinkedHashMap<View, () -> Unit>()
    private val applyNextArtwork = object : Runnable {
        override fun run() {
            val next = artworkUpdates.entries.firstOrNull() ?: return
            val view = next.key
            val apply = next.value
            artworkUpdates.remove(view)
            apply()
            if (artworkUpdates.isNotEmpty()) postOnAnimation(this)
        }
    }

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
                val label = object : TextView(context) {
                    private var lastMaximum = 0

                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        val width = MeasureSpec.getSize(widthSpec) - compoundPaddingLeft - compoundPaddingRight
                        val height = MeasureSpec.getSize(heightSpec) - compoundPaddingTop - compoundPaddingBottom
                        if (width > 0 && height > 0) {
                            // Fit the title into the current artwork slot. Long names can wrap
                            // or use a smaller size without changing the slot's dimensions.
                            val maximum = minOf(height * 0.85f, width * 0.36f).roundToInt().coerceAtLeast(2)
                            if (maximum != lastMaximum) {
                                lastMaximum = maximum
                                val minimum = (maximum * 0.45f).roundToInt().coerceAtLeast(1)
                                setAutoSizeTextTypeUniformWithConfiguration(
                                    minimum, maximum, maxOf(1, (maximum - minimum) / 10), TypedValue.COMPLEX_UNIT_PX
                                )
                            }
                        }
                        super.onMeasure(widthSpec, heightSpec)
                    }
                }.apply {
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(16, 6, 16, 6) // Match the wheel artwork's inset.
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
                artworkUpdates.remove(cell)
                val width = if (vertical) -1 else itemExtent
                val height = if (vertical) itemExtent else -1
                val params = cell.layoutParams
                if (params == null || params.width != width || params.height != height) {
                    cell.layoutParams = LayoutParams(width, height)
                }
                val label = cell.getChildAt(0) as TextView
                label.text = games[position].title
                bindArtwork(cell, label, games[position], { apply ->
                    if (moving && cell.isAttachedToWindow) {
                        if (artworkUpdates.isEmpty()) {
                            removeCallbacks(applyNextArtwork)
                            postOnAnimation(applyNextArtwork)
                        }
                        artworkUpdates[cell] = apply
                    } else apply()
                })
                cell.isSelected = position == cursor
                label.setTextColor(theme.text)
            }
        }
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) {
                    clearSteps()
                    touchScrolling = true
                    setMoving(true)
                } else if (newState == SCROLL_STATE_IDLE) {
                    if (touchScrolling) {
                        touchScrolling = false
                        followCenter()
                        postOnAnimation {
                            if (scrollState == SCROLL_STATE_IDLE && controllerTarget == null && !stepInFlight) center(cursor)
                        }
                    } else if (stepInFlight) {
                        stepInFlight = false
                        followCenter()
                        finishMotion()
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
            val visibleSlots = if (vertical) 5 else 3
            val nextExtent = (extent * artworkScale / visibleSlots).roundToInt()
                .coerceAtLeast(dp(48)).coerceAtMost(extent)
            if (nextExtent != itemExtent) {
                clearSteps()
                setMoving(false)
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
        val viewport = if (vertical) height else width
        val center = viewportCenter
        for (index in 0 until childCount) {
            val item = getChildAt(index)
            val position = itemCenter(item) - pixelRemainder
            if (position < -itemExtent / 2f || position > viewport + itemExtent / 2f) continue
            val distance = abs(position - center) / itemExtent
            val emphasis = (1f - distance * 0.30f).coerceIn(0.4f, 1f)
            val scale = 0.65f + 0.35f * emphasis
            item.scaleX = scale
            item.scaleY = scale
            item.alpha = emphasis
            if (vertical) item.translationY = -pixelRemainder else item.translationX = -pixelRemainder
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
            finishMotion()
        }
    }

    private val axisDirection get() = if (!vertical && layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1

    private fun center(target: Int) {
        val anchor = snap.findSnapView(layout)
        val anchorPosition = anchor?.let(layout::getPosition) ?: NO_POSITION
        if (anchor == null || anchorPosition == NO_POSITION || itemExtent <= 0) {
            moveCursor(target)
            layout.scrollToPositionWithOffset(target, 0)
            finishMotion()
            return
        }
        val item = layout.findViewByPosition(target)
        val targetCenter = item?.let(::itemCenter)
            ?: (itemCenter(anchor) + (target - anchorPosition) * itemExtent * axisDirection)
        val delta = targetCenter - viewportCenter
        if (delta == 0) {
            moveCursor(target)
            finishMotion()
            return
        }
        stepInFlight = true
        setMoving(true)
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
        setMoving(true)
    }

    private fun moveControllerFrame(frameNanos: Long) {
        val target = controllerTarget ?: return
        if (!isShown) {
            endMotion()
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
            finishMotion()
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

    private fun setMoving(value: Boolean) {
        if (moving == value) return
        moving = value
        motionChanged(value)
    }

    private fun finishMotion() {
        settled()
        setMoving(false)
    }

    fun endMotion() {
        clearSteps()
        touchScrolling = false
        stopScroll()
        setMoving(false)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(applyNextArtwork)
        // Cached holders can be reused when the wheel is shown again.
        artworkUpdates.values.forEach { it() }
        artworkUpdates.clear()
        endMotion()
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
