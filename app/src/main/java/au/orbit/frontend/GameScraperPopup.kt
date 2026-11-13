package au.orbit.frontend

import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** A small game menu that keeps the collection and its cursor in place. */
class GameScraperPopup(
    private val menu: EmulatorSettings.MenuWindow,
    private val system: SystemSpec,
    private val game: Game,
    private val changed: () -> Unit
) {
    private val activity = menu.activity
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var closed = false
    var parentPage:(()->Unit)?=null

    fun show() {
        generation++
        val actions=mutableListOf<Pair<String,()->Unit>>(
            "Scrape game" to { providers() },
            "Choose emulator" to { EmulatorSettings.showForGame(menu, system, game, ::show) }
        )
        if(ImportedThemes.active(activity)==null && EsDeTheme.enabled(activity)) {
            val enabled=UserDataStore.int(activity,"colorfulMarquees",1)==1
            val listLogos=UserDataStore.int(activity,"colorfulListLogos",0)==1
            actions.add("Game list: ${if(listLogos) "Logos" else "Text"}" to {
                UserDataStore.put(activity,"settings","colorfulListLogos",if(listLogos)0 else 1)
                changed()
                show()
            })
            actions.add("Artwork logo: ${if(enabled) "On" else "Off"}" to {
                UserDataStore.put(activity,"settings","colorfulMarquees",if(enabled)0 else 1)
                changed()
                show()
            })
        }
        menu.actions("Game options",actions)
    }

    fun providers() {
        generation++
        menu.actions("Scrape game", listOf("ScreenScraper", "NeoAssets", "TheGamesDB", "SteamGridDB").map { provider ->
            provider to { search(provider, game.title.replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "").trim()) }
        }, parentPage ?: ::show)
    }

    private fun search(provider: String, query: String) {
        work("Searching $provider…", { ScraperClient(activity, provider).search(query) }, { edit(provider, query) }) { matches ->
            val match = matches.firstOrNull()
            if (match != null) {
                download(provider, match)
            } else {
                menu.actions("No matches", listOf("Edit search" to { edit(provider, query) }), ::providers)
            }
        }
    }

    private fun edit(provider: String, query: String) {
        generation++
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(activity).apply { setSingleLine(); setText(query); hint = "Game title" }
        val search = Button(activity).apply {
            text = "Search"
            setOnClickListener { input.text.toString().trim().takeIf { it.isNotBlank() }?.let { search(provider, it) } }
        }
        input.setOnEditorActionListener { _, _, _ -> search.performClick(); true }
        panel.addView(input); panel.addView(search)
        menu.page("Search $provider", panel, back = ::providers, handle = { key, event ->
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) search.performClick()
                true
            } else false
        })
        search.requestFocus()
    }

    private fun download(provider: String, match: ScraperClient.Match) {
        work("Downloading ${match.title}…", {
            GameScraper.scrape(activity, provider, system, game, match)
        }, ::providers, allowBack = false) { result ->
            changed()
            menu.page("Scrape complete", TextView(activity).apply {
                text = result; textSize = 16f
            }, "Done", parentPage ?: { menu.dialog.dismiss() })
        }
    }

    private fun <T> work(title: String, action: () -> T, back: () -> Unit, allowBack: Boolean = true, done: (T) -> Unit) {
        val ticket = ++generation
        // Do not allow a second download to race the same game's metadata writes.
        menu.page("Scrape game", TextView(activity).apply { text = title; textSize = 16f },
            if (allowBack) "Back" else "Close", if (allowBack) back else ({ menu.dialog.dismiss() }))
        worker.execute {
            val result = runCatching(action)
            activity.runOnUiThread {
                if (!closed && ticket == generation && !activity.isDestroyed && menu.dialog.isShowing) {
                    result.onSuccess(done).onFailure { error ->
                        menu.page("Scrape failed", TextView(activity).apply {
                            text = error.message ?: "Unable to scrape this game."; textSize = 16f
                        }, "Back", back)
                    }
                }
            }
        }
    }

    fun destroy() { closed = true; generation++; worker.shutdownNow() }
}
