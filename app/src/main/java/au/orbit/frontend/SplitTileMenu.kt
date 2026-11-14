package au.orbit.frontend

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.PathShape
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Tile actions overlay the selected cell while the grid keeps its size and position. */
internal class SplitTileMenu(
    context: Context,
    private val theme: OrbitThemes.Theme,
    private val grid: SplitLibraryGrid,
    private val inGames: Boolean,
    private val launchScreen: () -> String,
    private val chooseScreen: (String) -> Unit,
    private val launchOnce: (String) -> Unit,
    private val resizeTiles: () -> Unit,
    private val changeLayout: () -> Unit,
    private val tileArtworkLabel: () -> String,
    private val toggleTileArtwork: () -> Unit,
    private val discSpinEnabled: () -> Boolean,
    private val toggleDiscSpin: () -> Unit,
    private val openSettings: () -> Unit
) : FrameLayout(context) {
    companion object { const val TAG = "orbit-split-tile-menu" }

    private enum class Page { ACTIONS, EDIT, DEFAULT_SCREEN, LAUNCH_SCREEN }

    private val gap = dp(7)
    private val mainWidth = dp(156)
    private val choicesWidth = dp(104)
    private val rowHeight = dp(42)
    private val panelPadding = dp(5)
    private val open = action("Open") {
        if (canChooseLaunchScreen) showPage(Page.LAUNCH_SCREEN) else launch()
    }
    private val edit = action("Edit", android.R.drawable.ic_menu_edit, ::editTile)
    private val move = action("Move", android.R.drawable.ic_menu_directions) {
        close()
        grid.beginMove()
    }
    private val remove = action("Remove shortcut", android.R.drawable.ic_menu_delete, ::removeShortcut)
    private val settingsButton = action(if (inGames) "Game Settings" else "System Settings", android.R.drawable.ic_menu_manage, ::settings)
    private val screen = action("Launch screen", android.R.drawable.ic_menu_view) { showPage(Page.DEFAULT_SCREEN) }
    private val tiles = action("Tiles", android.R.drawable.ic_menu_sort_by_size) {
        resizeTiles()
        updateLabels()
    }
    private val arrangement = action("Layout", android.R.drawable.ic_menu_agenda) {
        changeLayout()
        updateLabels()
    }
    private val tileArtwork = if (inGames) action("Tile artwork", android.R.drawable.ic_menu_gallery) {
        toggleTileArtwork()
        updateLabels()
    } else null
    private val spinDiscs = if (inGames) action("Spin discs", android.R.drawable.ic_menu_rotate) {
        toggleDiscSpin()
        updateLabels()
    } else null
    private val top = action("Top") { selectScreen("top") }
    private val bottom = action("Bottom") { selectScreen("bottom") }
    private val mainButtons = listOf(open, edit, move, remove)
    private val editButtons = listOfNotNull(settingsButton, screen, tiles, arrangement, tileArtwork, spinDiscs)
    private val choices = listOf(top, bottom)
    private val actionsPanel = panel(mainButtons)
    private val editPanel = panel(editButtons)
    private val choicesPanel = panel(choices)
    private var page = Page.ACTIONS
    private var focusTarget: Button? = null
    var isOpen = false
        private set
    val shortcutSelected get() = grid.shortcutSelected
    private val canChooseLaunchScreen get() = !grid.emptyShortcutSelected && (inGames || shortcutSelected)
    fun shortcutBrowse() = grid.shortcutBrowse()
    private val reposition = ViewTreeObserver.OnPreDrawListener { positionMenus(); true }

    private val choosingScreen get() = page == Page.DEFAULT_SCREEN || page == Page.LAUNCH_SCREEN
    private val editing get() = page == Page.EDIT || page == Page.DEFAULT_SCREEN
    private val activeButtons get() = when {
        choosingScreen -> choices
        editing -> editButtons
        else -> mainButtons.filter { it.visibility == VISIBLE }
    }

    init {
        tag = TAG
        addView(grid, LayoutParams(-1, -1))
        addView(actionsPanel, LayoutParams(mainWidth, panelHeight(mainButtons.size), Gravity.TOP or Gravity.LEFT))
        addView(editPanel, LayoutParams(mainWidth, panelHeight(editButtons.size), Gravity.TOP or Gravity.LEFT))
        addView(choicesPanel, LayoutParams(choicesWidth, panelHeight(choices.size), Gravity.TOP or Gravity.LEFT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun panelHeight(count: Int) = rowHeight * count + panelPadding * 2

    private fun panel(buttons: List<Button>) = ScrollView(context).apply {
        visibility = INVISIBLE
        elevation = dp(5).toFloat()
        isVerticalScrollBarEnabled = false
        background = GradientDrawable().apply {
            setColor(theme.text)
            cornerRadius = dp(15).toFloat()
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            buttons.forEach { addView(it, LinearLayout.LayoutParams(-1, rowHeight)) }
        }, FrameLayout.LayoutParams(-1, -2))
    }

    private fun action(label: String, icon: Int = 0, click: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        maxLines = 2
        minWidth = 0
        minimumWidth = 0
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(9), 0, dp(9), 0)
        compoundDrawablePadding = dp(8)
        setTextColor(theme.background)
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        backgroundTintList = null
        stateListAnimator = null
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusOutline())
            addState(intArrayOf(android.R.attr.state_pressed), focusOutline())
            addState(intArrayOf(), focusOutline(Color.TRANSPARENT))
        }
        leadingIcon(this, icon)
        setOnClickListener { click() }
    }

    private fun focusOutline(stroke: Int = SplitLibraryGrid.selectionBlue) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), stroke)
    }

    private fun leadingIcon(button: Button, resource: Int) {
        val icon = if (resource == 0) null else context.getDrawable(resource)?.mutate()?.apply {
            setBounds(0, 0, dp(21), dp(21))
            setTint(SplitLibraryGrid.selectionBlue)
        }
        button.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun tick() = ShapeDrawable(PathShape(Path().apply {
        moveTo(2f, 8f)
        lineTo(6f, 12f)
        lineTo(14f, 4f)
    }, 16f, 16f)).apply {
        intrinsicWidth = dp(16)
        intrinsicHeight = dp(16)
        paint.color = SplitLibraryGrid.selectionBlue
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    private fun updateLabels() {
        val empty = grid.emptyShortcutSelected
        open.text = when {
            empty -> "Add app or game"
            canChooseLaunchScreen -> "Launch"
            else -> "Open"
        }
        leadingIcon(open, if (empty) android.R.drawable.ic_input_add else android.R.drawable.ic_media_play)
        edit.visibility = if (empty) GONE else VISIBLE
        move.visibility = if (empty) GONE else VISIBLE
        remove.visibility = if (shortcutSelected && !empty) VISIBLE else GONE
        if (empty) return

        val saved = launchScreen()
        if (!shortcutSelected) {
            screen.text = "Launch screen · ${if (saved == "top") "Top" else "Bottom"}"
            tiles.text = "Tiles · ${grid.size.title}"
            arrangement.text = "Layout · ${grid.arrangementLabel}"
            tileArtwork?.text = "Tile artwork · ${tileArtworkLabel()}"
            spinDiscs?.text = "Spin discs · ${if (discSpinEnabled()) "On" else "Off"}"
        }
        for ((name, button) in listOf("top" to top, "bottom" to bottom)) {
            val selected = !shortcutSelected && name == saved
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, if (selected) tick() else null, null)
            button.contentDescription = "${button.text} screen${if (selected) ", saved default" else ""}"
        }
    }

    fun show() {
        if (grid.isMoving || visibleAnchor() == null) return
        grid.stopScroll()
        if (!isOpen) {
            isOpen = true
            viewTreeObserver.addOnPreDrawListener(reposition)
        }
        showPage(Page.ACTIONS, open)
    }

    private fun showPage(next: Page, focus: Button? = null) {
        page = next
        updateLabels()
        focusTarget = focus ?: if (choosingScreen && launchScreen() == "bottom") bottom else activeButtons.first()
        invalidate()
    }

    fun onY() = when {
        !isOpen -> show()
        choosingScreen -> back()
        page == Page.ACTIONS && edit.hasFocus() -> editTile()
        page == Page.ACTIONS && open.hasFocus() && canChooseLaunchScreen -> showPage(Page.LAUNCH_SCREEN)
        page == Page.EDIT && screen.hasFocus() -> showPage(Page.DEFAULT_SCREEN)
        else -> back()
    }

    private fun editTile() {
        if (shortcutSelected) {
            close()
            grid.editShortcut()
        } else showPage(Page.EDIT)
    }

    private fun removeShortcut() {
        runCatching { grid.removeShortcut() }
            .onSuccess { close() }
            .onFailure { Toast.makeText(context, it.message ?: "Could not remove shortcut", Toast.LENGTH_LONG).show() }
    }

    private fun selectScreen(value: String) {
        if (page == Page.LAUNCH_SCREEN) {
            close()
            launchOnce(value)
        } else {
            chooseScreen(value)
            updateLabels()
        }
    }

    private fun back() = when (page) {
        Page.ACTIONS -> close()
        Page.EDIT -> showPage(Page.ACTIONS, edit)
        Page.DEFAULT_SCREEN -> showPage(Page.EDIT, screen)
        Page.LAUNCH_SCREEN -> showPage(Page.ACTIONS, open)
    }

    fun close() {
        if (!isOpen) return
        hide()
        grid.requestFocus()
    }

    private fun hide() {
        isOpen = false
        page = Page.ACTIONS
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(reposition)
        actionsPanel.visibility = INVISIBLE
        editPanel.visibility = INVISIBLE
        choicesPanel.visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        if (isOpen) hide()
        super.onDetachedFromWindow()
    }

    private fun visibleAnchor() = grid.cursorTile?.takeIf { it.right > 0 && it.left < grid.width }

    private fun cornerPosition(edge: Int, panelSize: Int, viewportSize: Int, overlap: Int = dp(16)): Int {
        val lastStart = (viewportSize - panelSize).coerceAtLeast(0)
        val outward = edge - overlap
        val inward = edge + overlap - panelSize
        return (if (outward <= lastStart) outward else inward).coerceIn(0, lastStart)
    }

    private fun cornerTop(edge: Int, panelHeight: Int) =
        (cornerPosition(edge, panelHeight, height, overlap = dp(8)) - dp(20)).coerceAtLeast(0)

    private fun positionMenus() {
        val tile = visibleAnchor()
        if (tile == null) {
            close()
            return
        }
        if (width == 0 || height == 0) return
        val primary = if (editing) editPanel else actionsPanel
        val row = if (editing) screen else open
        val firstWidth = minOf(mainWidth, width)
        val count = if (editing) editButtons.size else mainButtons.count { it.visibility == VISIBLE }
        val primaryHeight = minOf(panelHeight(count), height)
        val anchorRight = grid.left + tile.right
        val anchorBottom = grid.top + tile.bottom
        val x = cornerPosition(anchorRight, firstWidth, width)
        val y = cornerTop(anchorBottom, primaryHeight)
        val rightSpace = width - x - firstWidth - gap
        val leftSpace = x - gap
        val onRight = rightSpace >= choicesWidth || rightSpace >= leftSpace
        val choiceSpace = if (onRight) rightSpace else leftSpace
        val beside = choosingScreen && choiceSpace >= dp(76)
        (if (editing) actionsPanel else editPanel).visibility = INVISIBLE
        if (!choosingScreen || beside) {
            position(primary, x, y, firstWidth, primaryHeight)
        } else primary.visibility = INVISIBLE
        if (choosingScreen) {
            val choiceWidth = minOf(choicesWidth, if (beside) choiceSpace else width)
            val choiceHeight = minOf(panelHeight(choices.size), height)
            val choiceX = when {
                !beside -> cornerPosition(anchorRight, choiceWidth, width)
                onRight -> x + firstWidth + gap
                else -> x - gap - choiceWidth
            }
            val choiceY = if (beside) primary.translationY.toInt() + row.top - primary.scrollY
                else cornerTop(anchorBottom, choiceHeight)
            position(choicesPanel, choiceX, choiceY, choiceWidth, choiceHeight)
        } else choicesPanel.visibility = INVISIBLE
        val activePanel = if (choosingScreen) choicesPanel else primary
        if (activePanel.visibility == VISIBLE) {
            val focus = focusTarget ?: activeButtons.first().takeUnless { activePanel.hasFocus() }
            if (focus?.requestFocus() == true) focusTarget = null
        }
    }

    private fun position(panel: ScrollView, x: Int, y: Int, width: Int, desiredHeight: Int) {
        val height = minOf(desiredHeight, this.height)
        val params = panel.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            panel.layoutParams = params
        }
        panel.translationX = x.toFloat()
        panel.translationY = y.coerceIn(0, this.height - height).toFloat()
        panel.visibility = if (panel.width == width && panel.height == height) VISIBLE else INVISIBLE
    }

    fun launch() {
        close()
        if (grid.emptyShortcutSelected) grid.editShortcut() else grid.openSelected()
    }

    private fun settings() {
        close()
        openSettings()
    }

    fun handle(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val buttons = activeButtons
                val selected = buttons.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                buttons[(selected + step).coerceIn(buttons.indices)].requestFocus()
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && page != Page.ACTIONS) back()
                else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    when {
                        page == Page.ACTIONS && edit.hasFocus() -> editTile()
                        page == Page.ACTIONS && open.hasFocus() && canChooseLaunchScreen -> showPage(Page.LAUNCH_SCREEN)
                        page == Page.EDIT && screen.hasFocus() -> showPage(Page.DEFAULT_SCREEN)
                    }
                }
            }
            true
        }
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                activeButtons.firstOrNull { it.hasFocus() }?.performClick()
            }
            true
        }
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
            true
        }
        KeyEvent.KEYCODE_BUTTON_SELECT -> {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (shortcutSelected) editTile() else settings()
            }
            true
        }
        else -> false
    }
}
