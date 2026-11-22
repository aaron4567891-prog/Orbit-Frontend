package au.orbit.frontend

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Fetch a catalogue and a small preview; only Use backgrounds changes the scrape selection. */
internal class SystemBackgroundPicker(private val activity: MainActivity, private val systems: List<SystemSpec>,
    private val selected: (SystemBackgroundSources.Pack?) -> Unit) {
    private val host get() = activity.popupContext()
    private val client = ScraperClient(activity.applicationContext, "NeoAssets")
    private companion object {
        val worker = Executors.newFixedThreadPool(2)
        val trees = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, org.json.JSONObject>>()
    }

    fun show() {
        choices("System background source", listOf("Orbit defaults · Curated system heroes", "ScreenScraper", "NeoAssets packs", "Iconic", "Art Book Next")) { index ->
            when (index) {
                0 -> preview(SystemBackgroundSources.defaults())
                1 -> selected(null)
                2 -> load("Loading NeoAssets packs…", { client.packs() }) { packs ->
                    if (packs.isEmpty()) message("No packs are available.")
                    else choices("NeoAssets pack", packs.map { it.optString("name", it.optString("folder")) }) { position ->
                        val folder = packs[position].getString("folder")
                        load("Loading pack preview…", { SystemBackgroundSources.neoPack(client.packDetails(folder)) }, ::preview)
                    }
                }
                3 -> theme(SystemBackgroundSources.iconic)
                4 -> theme(SystemBackgroundSources.artBook)
            }
        }
    }

    private fun theme(source: SystemBackgroundSources.ThemeSource) {
        choices("${source.name} style", source.styles.map { it.name }) { index ->
            load("Loading ${source.name} artwork…", {
                val now = android.os.SystemClock.elapsedRealtime()
                val tree = trees[source.repository]?.takeIf { now - it.first < 30 * 60 * 1000L }?.second
                    ?: client.backgroundJson(source.tree).also { trees[source.repository] = now to it }
                SystemBackgroundSources.themePack(source, source.styles[index], tree)
            }, ::preview)
        }
    }

    private fun preview(pack: SystemBackgroundSources.Pack) {
        val matches = systems.count { pack.image(it.id) != null }
        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val image = ImageView(host).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        panel.addView(image, LinearLayout.LayoutParams(-1, (220 * host.resources.displayMetrics.density).toInt()))
        panel.addView(TextView(host).apply {
            text = "By ${pack.author}\n$matches of ${systems.size} library systems covered.\n${pack.project}"
            autoLinkMask = android.text.util.Linkify.WEB_URLS
        })
        val status = TextView(host).apply { text = "Loading preview…" }
        panel.addView(status)
        val dialog = AlertDialog.Builder(host).setTitle(pack.name).setView(panel)
            .setPositiveButton("Use backgrounds") { _, _ -> selected(pack) }
            .setNeutralButton("Other sources") { _, _ -> show() }
            .setNegativeButton("Cancel", null).create()
        showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = matches > 0
        val url = pack.preview(systems.map { it.id })
        if (url == null) { status.text = "No compatible images are available."; return }
        worker.execute {
            val result = runCatching {
                val bytes = if(pack.bundled)SystemDefaultBackgrounds.bytes(activity.applicationContext,url.substringAfterLast('/'))
                    ?: error("Default artwork is unavailable") else client.backgroundBytes(url)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("Image could not be decoded")
            }
            activity.runOnUiThread {
                if (!activity.isDestroyed && dialog.isShowing) {
                    image.setImageBitmap(result.getOrNull())
                    status.text = if (result.isSuccess) {
                        val portrait = result.getOrNull()?.let { it.height > it.width } == true
                        if (portrait) "Portrait artwork · It will be cropped to fill a landscape screen."
                        else "Preview · The scrape applies matching backgrounds to your current theme."
                    }
                        else "Preview unavailable. You can still select this pack and try downloading its images."
                }
            }
        }
    }

    private fun choices(title: String, labels: List<String>, choose: (Int) -> Unit) {
        showDialog(AlertDialog.Builder(host).setTitle(title).setItems(labels.toTypedArray()) { _, index -> choose(index) }
            .setNegativeButton("Cancel", null).create())
    }

    private fun <T> load(title: String, fetch: () -> T, ready: (T) -> Unit) {
        val dialog = AlertDialog.Builder(host).setMessage(title).setNegativeButton("Cancel", null).create()
        showDialog(dialog)
        worker.execute {
            val result = runCatching(fetch)
            activity.runOnUiThread {
                if (!activity.isDestroyed && dialog.isShowing) {
                    dialog.dismiss()
                    result.onSuccess(ready).onFailure { message("Could not load this source. Check your connection and try again.") }
                }
            }
        }
    }

    private fun message(text: String) {
        showDialog(AlertDialog.Builder(host).setMessage(text).setPositiveButton("Close", null).create())
    }

    private fun showDialog(dialog: AlertDialog) {
        dialog.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dialog.dismiss()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        val list = dialog.listView
                        if (list?.hasFocus() == true && list.selectedItemPosition >= 0) {
                            val index = list.selectedItemPosition
                            list.performItemClick(list.selectedView, index, list.getItemIdAtPosition(index))
                        } else dialog.currentFocus?.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        dialog.styleArtworkWindow(host)
        dialog.show()
        activity.trackPopup(dialog)
        FrontendNavigationBar.apply(host, dialog.window)
    }
}
