package au.orbit.frontend

import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.Executors

/** A small game menu that keeps the collection and its cursor in place. */
class GameScraperPopup(
    private val menu: EmulatorSettings.MenuWindow,
    private val system: SystemSpec,
    private val game: Game,
    private val changed: () -> Unit
) {
    private val activity = menu.activity
    private val group = GameCustomArtwork.group(system, game)
    private var closed = false
    var parentPage: (() -> Unit)? = null

    internal companion object {
        private val worker = Executors.newSingleThreadExecutor()
        private val activeGames = mutableSetOf<String>()
        private val artworkListeners = mutableMapOf<String, MutableSet<() -> Unit>>()
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun addArtworkListener(group: String, listener: () -> Unit) {
            artworkListeners.getOrPut(group) { mutableSetOf() }.add(listener)
        }

        fun removeArtworkListener(group: String, listener: () -> Unit) {
            artworkListeners[group]?.let {
                it.remove(listener)
                if (it.isEmpty()) artworkListeners.remove(group)
            }
        }
    }

    fun show() {
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
        menu.actions("Scrape game", listOf("ScreenScraper", "NeoAssets", "TheGamesDB", "SteamGridDB").map { provider ->
            provider to { search(provider, game.title) }
        }, parentPage ?: ::show)
    }

    private fun search(provider: String, query: String) {
        val context = activity.applicationContext
        if (!activeGames.add(group)) {
            Toast.makeText(context, "This game is already queued for scraping.", Toast.LENGTH_SHORT).show()
            return
        }
        (parentPage ?: { menu.dialog.dismiss() }).invoke()
        val revision = menu.revision
        Toast.makeText(context, "Scraping ${game.title} in the background.", Toast.LENGTH_SHORT).show()
        val progress = ScrapeProgress.start()
        worker.execute {
            val result = runCatching {
                val client = ScraperClient(context, provider)
                val match = if (query == game.title) client.searchGame(system, game)
                    else client.search(query, system, game.isPlaylist).firstOrNull()
                match?.let {
                    var total = 0
                    var saved = 0
                    GameScraper.scrape(context, provider, system, game, match,
                        onMediaPlan = { total = it.size },
                        onMediaSaved = { _, _ ->
                            val completed = ++saved
                            main.post { progress.update(completed, total) }
                        }
                    )
                }
            }
            main.post {
                activeGames.remove(group)
                progress.finish()
                if (result.getOrNull() != null) {
                    changed()
                    artworkListeners[group]?.toList()?.forEach { it() }
                } else if (result.isSuccess && !closed && menu.dialog.isShowing && menu.revision == revision) {
                    menu.actions("No matches", listOf("Edit search" to { edit(provider, query) }), ::providers)
                    return@post
                }
                val message = result.fold(
                    { it?.let { summary -> "${game.title}: $summary" } ?: "No matches for ${game.title}." },
                    { it.message ?: "Unable to scrape ${game.title}." }
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun edit(provider: String, query: String) {
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

    fun destroy() { closed = true }
}
