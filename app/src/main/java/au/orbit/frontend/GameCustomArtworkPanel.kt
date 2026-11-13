package au.orbit.frontend

import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** The game popup keeps both screens' artwork assignments on one page. */
internal class GameCustomArtworkPanel(
    private val activity: MainActivity,
    private val menu: EmulatorSettings.MenuWindow,
    private val system: SystemSpec,
    private val game: Game
) {
    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }

    private val group = GameCustomArtwork.group(system, game)
    private var selected = 0
    private data class ArtworkRow(val image: View, val delete: Button?)

    fun show() = menu.actions("Customisation · ${system.title}", listOf("Custom Media" to ::showImages))

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun showImages() {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val viewport = ScrollView(activity).apply {
            defaultFocusHighlightEnabled = false
            isSmoothScrollingEnabled = false
            addView(content)
        }
        val rows = mutableListOf<ArtworkRow>()
        for (screen in listOf("top", "bottom")) {
            val screenTitle = GameLaunchDisplay.label(screen)
            content.addView(label(screenTitle, 18f).apply {
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), dp(if (screen == "top") 4 else 14), 0, dp(4))
            })
            for (role in GameCustomArtwork.roles) {
                val row = artworkRow(screen, role, rows.size)
                rows.add(row)
                content.addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(row.image, LinearLayout.LayoutParams(0, -2, 1f))
                    row.delete?.let { addView(it, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
        fun focus(view: View) {
            view.requestFocus()
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        }
        menu.page("Custom Media · ${game.title}", viewport, back = ::show, handle = { key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                        focus(rows[(selected + step).coerceIn(rows.indices)].image)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val row = rows[selected]
                        val target = if (key == KeyEvent.KEYCODE_DPAD_LEFT) row.image else row.delete
                        target?.let(::focus)
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        val row = rows[selected]
                        (row.delete?.takeIf { it.hasFocus() } ?: row.image).performClick()
                    }
                    true
                }
                else -> false
            }
        })
        val revision = menu.revision
        val index = selected
        focus(rows[index].image)
        viewport.post {
            if (menu.dialog.isShowing && menu.revision == revision && rows[index].image.hasFocus()) focus(rows[index].image)
        }
    }

    private fun artworkRow(screen: String, role: String, index: Int): ArtworkRow {
        val title = if (role == GameCustomArtwork.HERO) "Hero image" else ScreenMediaSettings.label(role)
        val screenTitle = GameLaunchDisplay.label(screen)
        val file = GameCustomArtwork.file(group, screen, role)
        val preview = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (file != null) {
            fun showPreview() { preview.setImageBitmap(OrbitThemeArtwork.cachedImage(file)) }
            showPreview()
            OrbitThemeArtwork.request(activity, file, ::showPreview)
        }
        val choice = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isFocusableInTouchMode = true
            SelectionStyle.apply(this)
            contentDescription = "$screenTitle · $title"
            addView(preview, LinearLayout.LayoutParams(dp(100), dp(58)).apply { marginEnd = dp(12) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f))
                val description = when {
                    file != null -> "Custom image"
                    role == GameCustomArtwork.HERO -> "Choose a full-screen image"
                    else -> "Choose an image"
                }
                addView(label(description, 13f, OrbitThemes.current().muted))
            })
            setOnClickListener { selected = index; chooseImage(screen, role, title) }
        }
        val delete = file?.let {
            Button(activity).apply {
                text = "Delete"
                isAllCaps = false
                isFocusableInTouchMode = true
                contentDescription = "Delete $screenTitle $title"
                setTextColor(Color.WHITE)
                SelectionStyle.apply(this)
                setOnClickListener { selected = index; saveImage(screen, role) { null } }
            }
        }
        listOfNotNull(choice, delete).forEach { control ->
            control.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    selected = index
                    menu.selectCompanionOption(if (control === delete) "Delete $screenTitle $title" else "$screenTitle · $title")
                }
            }
        }
        return ArtworkRow(choice, delete)
    }

    private fun saveImage(screen: String, role: String, image: () -> String?) {
        val revision = menu.revision
        worker.execute {
            val result = runCatching { GameCustomArtwork.save(activity, group, screen, role, image()) }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                result.onSuccess {
                    activity.refreshGameMediaOptions()
                    if (menu.dialog.isShowing && menu.revision == revision) showImages()
                }.onFailure {
                    Toast.makeText(activity, it.message ?: "Could not save game artwork", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun chooseImage(screen: String, role: String, title: String) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Choose from device" to {
                activity.pickThemeImage { uri ->
                    if (uri != null) saveImage(screen, role) { OrbitThemeArtwork.importImage(activity, uri) }
                }
            },
            "Search artwork" to {
                val searchRole = if (role == GameCustomArtwork.HERO) "hero" else role
                ArtworkSearchPicker(activity, searchRole, game.title, setOf(screen)) { bytes ->
                    saveImage(screen, role) { OrbitThemeArtwork.storeImage(activity, bytes) }
                }.show()
            }
        )
        menu.actions("${GameLaunchDisplay.label(screen)} · $title", actions, ::showImages)
    }
}
