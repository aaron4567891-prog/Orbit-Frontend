package au.orbit.frontend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.json.JSONObject

/** Top-screen artwork assignments in Game Settings. */
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
    private var updatingArtwork = false
    private data class ArtworkRow(
        val image: View,
        val remove: Button,
        val scrapedPreview: Pair<String, (Bitmap?) -> Unit>?
    )

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int = Color.WHITE) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    fun showImages(back: () -> Unit) {
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val viewport = ScrollView(activity).apply {
            defaultFocusHighlightEnabled = false
            isSmoothScrollingEnabled = true
            addView(content)
        }
        val rows = mutableListOf<ArtworkRow>()
        content.addView(label(GameLaunchDisplay.label("top"), 18f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), 0, dp(4))
        })
        val screen = OrbitThemeArtwork.artworkScreen(activity, "top")
        val hidden = GameCustomArtwork.removed(group, screen)
        for (role in GameCustomArtwork.roles) {
            val row = artworkRow("top", role, rows.size, role in hidden) { showImages(back) }
            rows.add(row)
            content.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(row.image, LinearLayout.LayoutParams(0, -2, 1f))
                addView(row.remove, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        val removeAll = Button(activity).apply {
            text = "Remove all"
            isAllCaps = false
            isFocusableInTouchMode = true
            SelectionStyle.apply(this)
            setOnClickListener {
                selected = rows.size
                changeArtwork({ showImages(back) }) {
                    GameCustomArtwork.delete(activity, group, screen)
                }
            }
            setOnFocusChangeListener { _, focused -> if (focused) selected = rows.size }
        }
        content.addView(removeAll, LinearLayout.LayoutParams(-1, dp(48)))
        val artworkControls = rows.map { it.image } + removeAll
        fun focus(view: View, immediate: Boolean = false) {
            view.requestFocus()
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), immediate)
        }
        menu.page("Custom Media · ${game.title}", viewport, back = back, handle = { key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val step = if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                        focus(artworkControls[(selected + step).coerceIn(artworkControls.indices)])
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        rows.getOrNull(selected)?.let { row ->
                            focus(if (key == KeyEvent.KEYCODE_DPAD_LEFT) row.image else row.remove)
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        val target = rows.getOrNull(selected)?.remove?.takeIf { it.hasFocus() || !rows[selected].image.isClickable }
                            ?: artworkControls[selected]
                        target.performClick()
                    }
                    true
                }
                else -> false
            }
        })
        val revision = menu.revision
        val index = selected
        val previewTargets = rows.mapNotNull { it.scrapedPreview }
        val loadPreviews: () -> Unit = load@ {
            if (previewTargets.isEmpty()) return@load
            worker.execute {
                val images = runCatching { scrapedPreviews(previewTargets.map { it.first }.toSet()) }
                    .getOrDefault(emptyMap())
                activity.runOnUiThread {
                    if (menu.dialog.isShowing && menu.revision == revision) {
                        previewTargets.forEach { (category, show) -> show(images[category]) }
                    }
                }
            }
        }
        viewport.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                GameScraperPopup.addArtworkListener(group, loadPreviews)
                loadPreviews()
            }
            override fun onViewDetachedFromWindow(view: View) {
                GameScraperPopup.removeArtworkListener(group, loadPreviews)
            }
        })
        if (viewport.isAttachedToWindow) {
            GameScraperPopup.addArtworkListener(group, loadPreviews)
            loadPreviews()
        }
        val initial = artworkControls[index]
        focus(initial, immediate = true)
        viewport.post {
            if (menu.dialog.isShowing && menu.revision == revision && initial.hasFocus()) focus(initial, immediate = true)
        }
    }

    private fun artworkRow(physicalScreen: String, role: String, index: Int, removed: Boolean, refresh: () -> Unit): ArtworkRow {
        val title = when (role) {
            GameCustomArtwork.HERO -> "Hero image"
            "covers" -> "2D box art"
            ScraperMediaTypes.boxArt3D.folder -> ScraperMediaTypes.boxArt3D.title
            else -> ScreenMediaSettings.label(role)
        }
        val screenTitle = GameLaunchDisplay.label(physicalScreen)
        val screen = OrbitThemeArtwork.artworkScreen(activity, physicalScreen)
        val canReplace = role !in setOf("videos", ScraperMediaTypes.boxArt3D.folder)
        val file = GameCustomArtwork.file(group, screen, role)
        val preview = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        if (file != null && !removed) {
            fun showPreview() { preview.setImageBitmap(OrbitThemeArtwork.cachedImage(file)) }
            showPreview()
            OrbitThemeArtwork.request(activity, file, ::showPreview)
        }
        val status = label(when {
            removed -> "Removed"
            file != null -> "Custom image"
            role == "videos" -> "Scraped video"
            !canReplace -> "No scraped image"
            else -> "Choose an image"
        }, 13f, OrbitThemes.current().muted)
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
                addView(status)
            })
            if (canReplace) setOnClickListener { selected = index; chooseImage(screen, screenTitle, role, title, refresh) }
        }
        val remove = Button(activity).apply {
            text = "Remove"
            isAllCaps = false
            isFocusableInTouchMode = true
            contentDescription = "$text $title"
            setTextColor(Color.WHITE)
            SelectionStyle.apply(this)
            setOnClickListener {
                selected = index
                changeArtwork(refresh) {
                    GameCustomArtwork.delete(activity, group, screen, role)
                }
            }
        }
        listOf(choice, remove).forEach { control ->
            control.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    selected = index
                    menu.selectCompanionOption(if (control === remove) "${remove.text} $screenTitle $title" else "$screenTitle · $title")
                }
            }
        }
        val showScraped: ((Bitmap?) -> Unit)? = if (file != null || removed) null else { bitmap ->
            preview.setImageBitmap(bitmap)
            status.text = when {
                bitmap != null && canReplace -> "Scraped image · Change"
                bitmap != null -> "Scraped image"
                canReplace -> "Choose an image"
                else -> "No scraped image"
            }
        }
        val category = if (role == GameCustomArtwork.HERO) ScreenMediaSettings.background(screen) else role
        val scrapedPreview = showScraped?.takeIf { category !in setOf("videos", "manuals", "theme", "off") }
            ?.let { category to it }
        return ArtworkRow(choice, remove, scrapedPreview)
    }

    private fun scrapedPreviews(categories: Set<String>): Map<String, Bitmap> {
        val files = UserDataStore.readScrapedRecords(activity, group).flatMap { bytes ->
            runCatching {
                val record = JSONObject(bytes.toString(Charsets.UTF_8))
                if (ScrapedMediaPlatform.matches(record, system.id)) ScraperClient.objects(record.opt("files"))
                else emptyList()
            }
                .getOrDefault(emptyList()).asSequence()
        }.groupBy({ it.optString("type") }, { it.optString("file") })
        return ScraperMediaTypes.categories.filter { it.folder in categories }.mapNotNull { category ->
            val bitmap = category.types.asSequence().flatMap { files[it].orEmpty().asSequence() }
                .filter { it.isNotBlank() }.firstNotNullOfOrNull { path ->
                    runCatching {
                        val bytes = UserDataStore.readScraped(activity, group, path) ?: return@runCatching null
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 360) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                            inSampleSize = sample
                        })
                    }.getOrNull()
                }
            bitmap?.let { category.folder to it }
        }.toMap()
    }

    private fun saveImage(screen: String, role: String, refresh: () -> Unit, image: () -> String?) =
        changeArtwork(refresh) { GameCustomArtwork.save(activity, group, screen, role, image()) }

    private fun changeArtwork(refresh: () -> Unit, change: () -> Unit) {
        if (updatingArtwork) return
        updatingArtwork = true
        val revision = menu.revision
        worker.execute {
            val result = runCatching(change)
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                updatingArtwork = false
                activity.refreshGameArtwork()
                if (menu.dialog.isShowing && menu.revision == revision) refresh()
                result.onFailure {
                    Toast.makeText(activity, it.message ?: "Could not update game artwork", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun chooseImage(screen: String, screenTitle: String, role: String, title: String, refresh: () -> Unit) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Choose from device" to {
                activity.pickThemeImage { uri ->
                    if (uri != null) saveImage(screen, role, refresh) { OrbitThemeArtwork.importImage(activity, uri) }
                }
            },
            "Search artwork" to {
                val searchRole = if (role == GameCustomArtwork.HERO) "hero" else role
                ArtworkSearchPicker(activity, searchRole, game.title, setOf(screen)) { bytes ->
                    saveImage(screen, role, refresh) { OrbitThemeArtwork.storeImage(activity, bytes) }
                }.show()
            }
        )
        menu.actions("$screenTitle · $title", actions, refresh)
    }
}
