package au.orbit.frontend

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/** A controller-friendly grid of square cover or console tiles for the 3DS theme. */
internal class SplitLibraryGrid(
    context: Context,
    private val theme: OrbitThemes.Theme,
    private val slots: SplitTileSlots,
    private val animationView: TransitionSettings.View,
    private val title: (Int) -> String,
    private val bind: (FrameLayout, TextView, Int, (Boolean) -> Unit) -> Unit,
    private val onSelected: (Int) -> Unit,
    initialSize: TileSize,
    initialArrangement: Arrangement,
    private val open: (Int) -> Unit,
    private val hold: () -> Unit,
    private val changeLayout: (Arrangement) -> Unit,
    private val verticalScroll: Boolean = false
) : RecyclerView(context) {
    enum class TileSize(val title: String, val fill: Float) {
        SMALL("Small", 0.64f), MEDIUM("Medium", 0.72f), LARGE("Large", 0.80f), EXTRA_LARGE("Extra Large", 0.96f);

        fun next() = entries[(ordinal + 1) % entries.size]
    }

    enum class Arrangement(val title: String, val rows: Int) {
        FOUR_ROWS("4 rows", 4), THREE_ROWS("3 rows", 3), TWO_ROWS("2 rows", 2),
        ONE_ROW("1 row", 1), SINGLE_TILE("Single Tile", 1);

        fun next() = entries[(ordinal + 1) % entries.size]

        fun columnWidth(width: Int, height: Int) =
            if (this == SINGLE_TILE) width else (height / rows).coerceIn(1, width)
    }

    companion object {
        val selectionBlue = Color.rgb(5, 97, 195)
        val defaultSize = TileSize.LARGE
        private const val TILE_TRANSITION_MS = 180
        /** Settings (0/1, off by default): tiles ripple in when a page opens; the outline glides. */
        const val TILE_CASCADE_KEY = "tileCascade"
        const val GLIDING_SELECTION_KEY = "glidingSelection"
        private const val GLIDE_MS = 130L
        private const val CASCADE_STEP_MS = 28L
        private const val CASCADE_MS = 170L
    }

    private val glideEnabled get() = TransitionSettings.flag(GLIDING_SELECTION_KEY, animationView)
    private var glideFrom: android.graphics.RectF? = null
    private var glideProgress = 1f
    private var glideAnimator: android.animation.ValueAnimator? = null
    private val glideEase = DecelerateInterpolator(1.5f)
    private val glideOuter = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; color = Color.WHITE
    }
    private val glideInner = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; color = selectionBlue
    }

    private fun tileRect(tile: View) = android.graphics.RectF(tile.x, tile.y, tile.x + tile.width * tile.scaleX, tile.y + tile.height * tile.scaleY)

    /** Where the gliding outline is drawn right now, in grid coordinates. */
    private fun glideRect(): android.graphics.RectF? {
        val target = cursorTile?.let(::tileRect) ?: return null
        val from = glideFrom ?: return target
        if (glideProgress >= 1f) return target
        val t = glideEase.getInterpolation(glideProgress)
        return android.graphics.RectF(
            from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t,
            from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t
        )
    }

    private fun startGlide(from: android.graphics.RectF?) {
        glideAnimator?.cancel()
        glideFrom = from
        if (from == null) { glideProgress = 1f; invalidate(); return }
        glideProgress = 0f
        glideAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLIDE_MS
            addUpdateListener { glideProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!glideEnabled || movingFrom == cursor) return
        val rect = glideRect() ?: return
        val radius = tileSize * 0.17f
        glideOuter.strokeWidth = dp(6).toFloat()
        glideInner.strokeWidth = dp(4).toFloat()
        val outer = android.graphics.RectF(rect).apply { inset(dp(3).toFloat(), dp(3).toFloat()) }
        val inner = android.graphics.RectF(rect).apply { inset(dp(3).toFloat(), dp(3).toFloat()) }
        canvas.drawRoundRect(outer, radius, radius, glideOuter)
        canvas.drawRoundRect(inner, radius, radius, glideInner)
    }

    /** Fades and grows the visible tiles in one after another, if the tile cascade is turned on. */
    fun cascadeIn() {
        if (!TransitionSettings.flag(TILE_CASCADE_KEY, animationView)) return
        // Hide the tiles before the first frame that shows this page, then ripple them in.
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val tiles = (0 until childCount).map(::getChildAt).sortedBy { getChildLayoutPosition(it) }
                tiles.forEachIndexed { order, tile ->
                    tile.animate().cancel()
                    tile.alpha = 0f; tile.scaleX = 0.88f; tile.scaleY = 0.88f
                    tile.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay(order * CASCADE_STEP_MS).setDuration(CASCADE_MS)
                        .setInterpolator(tileInterpolator).start()
                }
                return true
            }
        })
        invalidate()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private var tileSize = dp(76)
    private var columnWidth = dp(94)
    private var fittedColumns = 1
    private var rowPadding = 0
    private var extendedThrough = 0
    private var slotCount = 0
    var size = initialSize
        private set
    var arrangement = initialArrangement
        private set
    val arrangementLabel: String get() = when {
        !verticalScroll || arrangement == Arrangement.SINGLE_TILE -> arrangement.title
        arrangement.rows == 1 -> "1 column"
        else -> "${arrangement.rows} columns"
    }
    private var cursor = 0
    private var movingFrom: Int? = null
    val cursorPosition get() = cursor
    val cursorTile: View? get() = layout.findViewByPosition(cursor)
    val selectedLibraryIndex get() = slots.libraryIndex(cursor)
    val shortcutSelected get() = selectedLibraryIndex == null
    val emptyShortcutSelected get() = slots.isEmpty(cursor)
    val isMoving get() = movingFrom != null
    private val layout = GridLayoutManager(context, initialArrangement.rows, if (verticalScroll) VERTICAL else HORIZONTAL, false)
    private var singleTileLayout = initialArrangement == Arrangement.SINGLE_TILE
    private var swipingSingleTile = false
    private val singleTileSnap = object : PagerSnapHelper() {
        override fun findSnapView(layoutManager: LayoutManager): View? =
            if (singleTileLayout && !pinching) super.findSnapView(layoutManager) else null

        override fun findTargetSnapPosition(layoutManager: LayoutManager, velocityX: Int, velocityY: Int): Int =
            if (singleTileLayout && !pinching) super.findTargetSnapPosition(layoutManager, velocityX, velocityY) else NO_POSITION
    }
    private var pinching = false
    private var pinchSpan = 0f
    private var previousPinchTiles: Map<Int, TileFrame>? = null
    private val tileInterpolator = DecelerateInterpolator()
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchSpan = detector.currentSpan
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val span = detector.currentSpan
            val step = when {
                span > pinchSpan * 1.18f -> 1
                span < pinchSpan * 0.85f -> -1
                else -> return false
            }
            pinchSpan = span
            val next = Arrangement.entries.getOrNull(arrangement.ordinal + step) ?: return true
            changeLayout(next)
            return true
        }
    }).apply {
        isQuickScaleEnabled = false
        isStylusScaleEnabled = false
    }

    private class Tile(val cell: FrameLayout, val label: TextView) : ViewHolder(cell) {
        var boundPosition = NO_POSITION
    }

    private data class TileFrame(val centerX: Float, val centerY: Float, val size: Float)

    private val tiles = object : Adapter<Tile>() {
        override fun getItemCount() = slotCount
        override fun getItemViewType(position: Int) = if (slots.libraryIndex(position) != null) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Tile {
            val label = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(theme.background)
                textSize = 13f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
            val cell = FrameLayout(context).apply {
                setPadding(dp(5), dp(5), dp(5), dp(5))
                addView(label, FrameLayout.LayoutParams(-1, -1))
            }
            return Tile(cell, label).also { tile ->
                cell.setOnClickListener {
                    val position = tile.bindingAdapterPosition
                    if (position != NO_POSITION) {
                        select(position, animate = false, reveal = !isMoving && !slots.isEmpty(position))
                        openSelected()
                    }
                }
                cell.setOnLongClickListener {
                    val position = tile.bindingAdapterPosition
                    if (position == NO_POSITION) false else {
                        select(position, animate = false, reveal = false)
                        if (!isMoving) hold()
                        true
                    }
                }
                cell.isFocusable = false
            }
        }

        override fun onBindViewHolder(holder: Tile, position: Int) {
            holder.boundPosition = position
            val cell = holder.cell
            val label = holder.label
            cell.layoutParams = GridLayoutManager.LayoutParams(tileSize, tileSize).apply {
                val columnPadding = columnWidth - tileSize
                val horizontalPadding = if (verticalScroll) rowPadding else columnPadding
                val verticalPadding = if (verticalScroll) columnPadding else rowPadding
                leftMargin = horizontalPadding / 2
                rightMargin = horizontalPadding - leftMargin
                topMargin = verticalPadding / 2
                bottomMargin = verticalPadding - topMargin
            }
            highlight(cell, position)
            val index = slots.libraryIndex(position)
            if (index != null) {
                val name = title(index)
                label.text = name
                cell.contentDescription = name
                // Library tiles keep their filled style while artwork loads; switching from the
                // translucent placeholder to the solid tile reads as a bright flash on the dark page.
                styleTile(cell, filled = true)
                bind(cell, label, index) { }
            } else {
                bindShortcut(cell, label, position)
            }
        }

        override fun onViewAttachedToWindow(holder: Tile) {
            highlight(holder.cell, holder.boundPosition)
        }

        override fun onViewRecycled(holder: Tile) {
            holder.cell.setGameTileSelected(false)
            resetTileMotion(holder.cell)
        }
    }

    init {
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        isHorizontalScrollBarEnabled = false
        layoutDirection = LAYOUT_DIRECTION_LTR
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        layoutManager = layout
        itemAnimator = null
        updateSlotCount()
        adapter = tiles
        singleTileSnap.attachToRecyclerView(this)
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    SCROLL_STATE_DRAGGING -> swipingSingleTile = singleTileLayout
                    SCROLL_STATE_IDLE -> swipingSingleTile = false
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (swipingSingleTile && (dx != 0 || dy != 0)) {
                    val centered = singleTileSnap.findSnapView(layout)?.let(::getChildAdapterPosition)
                    if (centered != null && centered != NO_POSITION && centered != cursor) {
                        select(centered, reveal = false)
                    }
                }
                if (nearEnd()) post {
                    if (isAttachedToWindow && nearEnd()) extendTiles()
                }
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (width > 0 && height > 0) {
            val scrollExtent = if (verticalScroll) height else width
            val crossExtent = if (verticalScroll) width else height
            val spanSize = (crossExtent / arrangement.rows).coerceAtLeast(1)
            val singleTile = arrangement == Arrangement.SINGLE_TILE
            val nextColumnWidth = if (verticalScroll && !singleTile) minOf(spanSize, dp(160), scrollExtent)
                else arrangement.columnWidth(scrollExtent, crossExtent)
            val nextColumns = (scrollExtent + nextColumnWidth - 1) / nextColumnWidth
            val side = (minOf(spanSize, nextColumnWidth) * size.fill).roundToInt().coerceAtLeast(1)
            val nextPadding = spanSize - side
            val layoutChanged = columnWidth != nextColumnWidth || fittedColumns != nextColumns ||
                layout.spanCount != arrangement.rows || singleTileLayout != singleTile
            if (layoutChanged || tileSize != side || rowPadding != nextPadding) {
                val first = layout.findViewByPosition(layout.findFirstVisibleItemPosition())
                val anchor = if (layoutChanged) cursorTile ?: first else first
                val anchorPosition = anchor?.let(::getChildAdapterPosition) ?: NO_POSITION
                val start = anchor?.let { scrollStart(it) - (columnWidth - tileSize) / 2 }
                tileSize = side
                columnWidth = nextColumnWidth
                fittedColumns = nextColumns
                rowPadding = nextPadding
                singleTileLayout = singleTile
                layout.spanCount = arrangement.rows
                updateSlotCount()
                tiles.notifyDataSetChanged()
                if (layoutChanged && singleTile) {
                    layout.scrollToPositionWithOffset(cursor, 0)
                } else if (start != null && anchorPosition != NO_POSITION) {
                    val offset = if (layoutChanged && anchorPosition == cursor) start.coerceIn(0, scrollExtent - columnWidth) else start
                    layout.scrollToPositionWithOffset(anchorPosition, offset)
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val previous = previousPinchTiles ?: return
        previousPinchTiles = null
        val previousSize = previous.values.first().size
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            resetTileMotion(child)
            if (child.width == 0) continue
            val current = tileFrame(child)
            val old = previous[getChildLayoutPosition(child)]
                ?: current.copy(size = minOf(current.size, previousSize))
            val scale = old.size / current.size
            child.translationX = old.centerX - current.centerX
            child.translationY = old.centerY - current.centerY
            child.scaleX = scale
            child.scaleY = scale
            child.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(TILE_TRANSITION_MS.toLong()).setInterpolator(tileInterpolator).start()
        }
    }

    private fun tileFrame(tile: View) = TileFrame(
        tile.x + tile.width / 2f,
        tile.y + tile.height / 2f,
        tile.width * tile.scaleX
    )

    private fun resetTileMotion(tile: View) {
        tile.animate().cancel()
        tile.alpha = 1f
        tile.translationX = 0f
        tile.translationY = 0f
        tile.scaleX = 1f
        tile.scaleY = 1f
    }

    override fun onDetachedFromWindow() {
        previousPinchTiles = null
        for (index in 0 until childCount) resetTileMotion(getChildAt(index))
        super.onDetachedFromWindow()
    }

    private fun updateSlotCount() {
        val rows = layout.spanCount
        val occupied = maxOf(slots.slotCount, extendedThrough)
        val columns = (occupied + rows - 1) / rows
        slotCount = (columns + fittedColumns + 1) * rows
    }

    private fun nearEnd(): Boolean {
        val lastVisible = layout.findLastVisibleItemPosition()
        return lastVisible != NO_POSITION && lastVisible >= slotCount - 2 * layout.spanCount
    }

    private fun extendTiles(through: Int = slotCount) {
        val previousCount = slotCount
        extendedThrough = through
        updateSlotCount()
        tiles.notifyItemRangeInserted(previousCount, slotCount - previousCount)
    }

    private fun refreshSlots() {
        extendedThrough = maxOf(extendedThrough, cursor + 1)
        updateSlotCount()
        refreshTiles()
        select(cursor, animate = false, reveal = false)
    }

    fun refreshTiles() = tiles.notifyDataSetChanged()

    fun editShortcut() = slots.choose(cursor, ::refreshSlots)

    fun uninstallShortcut() = slots.uninstallApp(cursor)

    fun removeShortcut() {
        slots.remove(cursor)
        refreshSlots()
    }

    fun shortcutBrowse() = if (shortcutSelected) slots.browse(cursor) else null

    private fun styleTile(cell: FrameLayout, filled: Boolean) {
        cell.elevation = if (filled) dp(2).toFloat() else 0f
        cell.background = GradientDrawable().apply {
            setColor(if (filled) theme.text else theme.surface and 0x44FFFFFF)
            cornerRadius = tileSize * 0.17f
            setStroke(dp(1), if (filled) theme.muted else theme.muted and 0x38FFFFFF)
        }
    }

    private fun bindShortcut(cell: FrameLayout, label: TextView, slot: Int) {
        val app = slots.app(slot)
        val filled = app != null
        styleTile(cell, filled)
        val icon = (cell.getChildAt(1) as? ImageView) ?: ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            cell.addView(this, FrameLayout.LayoutParams(-1, -1).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }
        val drawable = app?.icon
        icon.setImageDrawable(drawable?.constantState?.newDrawable(resources) ?: drawable)
        icon.visibility = if (drawable == null) View.GONE else View.VISIBLE
        label.visibility = if (drawable == null) View.VISIBLE else View.INVISIBLE
        label.text = app?.label ?: "+"
        label.textSize = if (filled) 13f else 26f
        label.setTextColor(if (filled) theme.background else theme.muted)
        cell.contentDescription = app?.label ?: SplitTileSlots.ADD_LABEL
    }

    fun resize(next: TileSize) {
        stopScroll()
        size = next
        requestLayout()
    }

    fun arrange(next: Arrangement, animate: Boolean = false) {
        if (next == arrangement) return
        if (animate && previousPinchTiles == null && childCount > 0) {
            previousPinchTiles = buildMap {
                for (index in 0 until childCount) {
                    val tile = getChildAt(index)
                    val position = getChildLayoutPosition(tile)
                    if (position != NO_POSITION && tile.width > 0) put(position, tileFrame(tile))
                }
            }.takeUnless { it.isEmpty() }
        }
        stopScroll()
        arrangement = next
        requestLayout()
    }

    fun select(position: Int, animate: Boolean = true, reveal: Boolean = true) {
        if (!animate) stopScroll()
        if (position >= slotCount) extendTiles(position + 1)
        // The outline glides from where it is drawn now to the new tile.
        val glideStart = if (glideEnabled && animate) glideRect() else null
        val previous = cursor
        cursor = position.coerceIn(0, slotCount - 1)
        if (previous != cursor) {
            layout.findViewByPosition(previous)?.let { highlight(it, previous) }
            cursorTile?.let { highlight(it, cursor) }
            if (glideEnabled) startGlide(glideStart)
        } else if (!animate && glideEnabled) {
            startGlide(null)
        }
        if (reveal) revealCursor(animate)
        onSelected(cursor)
    }

    fun selectLibrary(index: Int) {
        slots.librarySlot(index)?.let { select(it, animate = false) }
    }

    fun reopen() {
        select(0, animate = false, reveal = false)
        if (cursorTile?.let(::scrollStart) != (columnWidth - tileSize) / 2) {
            layout.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun scrollStart(tile: View) = if (verticalScroll) tile.top else tile.left

    private fun revealCursor(animate: Boolean) {
        val first = layout.findFirstVisibleItemPosition()
        val anchor = layout.findViewByPosition(first)
        val viewport = if (verticalScroll) height else width
        if (anchor == null || viewport == 0) {
            layout.scrollToPositionWithOffset(cursor, 0)
            return
        }
        val rows = layout.spanCount
        val start = scrollStart(anchor) - (columnWidth - tileSize) / 2 + (cursor / rows - first / rows) * columnWidth
        val delta = when {
            start < 0 -> start
            start + columnWidth > viewport -> start + columnWidth - viewport
            else -> 0
        }
        if (delta == 0) return
        val dx = if (verticalScroll) 0 else delta
        val dy = if (verticalScroll) delta else 0
        if (animate) smoothScrollBy(dx, dy, null, TILE_TRANSITION_MS) else scrollBy(dx, dy)
    }

    fun beginMove() {
        if (emptyShortcutSelected || isMoving) return
        movingFrom = cursor
        cursorTile?.let { highlight(it, cursor) }
        Toast.makeText(context, "Choose a slot to place the tile. Press B to cancel.", Toast.LENGTH_SHORT).show()
    }

    fun cancelMove() {
        val from = movingFrom ?: return
        movingFrom = null
        select(from, animate = false)
    }

    fun openSelected() {
        val from = movingFrom
        if (from == null) {
            open(cursor)
        } else if (from == cursor) {
            cancelMove()
        } else {
            runCatching { slots.move(from, cursor) }.onSuccess {
                movingFrom = null
                refreshSlots()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Could not move tile", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pinching = false
            MotionEvent.ACTION_POINTER_DOWN -> if (!pinching) {
                pinching = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                stopScroll()
            }
        }
        pinch.onTouchEvent(event)
        return pinching || super.dispatchTouchEvent(event)
    }

    fun handleController(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (isMoving) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_Y -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) cancelMove()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> return true
            }
        }
        val rows = layout.spanCount
        val row = cursor % rows
        val step = if (verticalScroll) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (row > 0) -1 else 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (row < rows - 1) 1 else 0
            KeyEvent.KEYCODE_DPAD_UP -> -rows
            KeyEvent.KEYCODE_DPAD_DOWN -> rows
            else -> null
        } else when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -rows
            KeyEvent.KEYCODE_DPAD_RIGHT -> rows
            KeyEvent.KEYCODE_DPAD_UP -> if (row > 0) -1 else 0
            KeyEvent.KEYCODE_DPAD_DOWN -> if (row < rows - 1) 1 else 0
            else -> null
        }
        if (step != null) {
            if (event.action == KeyEvent.ACTION_DOWN && step != 0 && cursor + step >= 0 && !event.isCanceled) {
                select(cursor + step)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val confirm = if (isMoving || emptyShortcutSelected) event.action == KeyEvent.ACTION_UP
                else event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            if (confirm && !event.isCanceled) openSelected()
            return true
        }
        return false
    }

    private fun highlight(tile: View, position: Int) {
        tile.setGameTileSelected(position == cursor)
        fun outline(width: Int, color: Int) = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = tileSize * 0.17f
            setStroke(dp(width), color)
        }
        // With the gliding selection on, the grid draws the cursor outline itself (see dispatchDraw).
        val glidingCursor = glideEnabled && position == cursor && position != movingFrom
        tile.foreground = if (!glidingCursor && (position == cursor || position == movingFrom)) LayerDrawable(arrayOf(
            outline(6, Color.WHITE), outline(4, if (position == movingFrom) theme.accent else selectionBlue)
        )).apply {
            val inset = dp(1)
            setLayerInset(1, inset, inset, inset, inset)
        } else null
    }
}
