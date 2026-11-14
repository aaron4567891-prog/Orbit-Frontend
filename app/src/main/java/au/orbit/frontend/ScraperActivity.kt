package au.orbit.frontend

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.KeyEvent
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class ScraperActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var provider: String
    private lateinit var client: ScraperClient
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var busy = false
    private val batchCancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    private var batchPool:java.util.concurrent.ExecutorService?=null
    private val preferences get() = getSharedPreferences("scraper_credentials",0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = intent.getStringExtra("provider") ?: "ScreenScraper"
        client = ScraperClient(this,provider)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(28),dp(12),dp(28),dp(12));setBackgroundColor(Color.rgb(12,17,25)) }
        root.addView(label(provider,26f))
        status = label("",14f);root.addView(status)
        body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        when(intent.getStringExtra("action")) {
            "games" -> systems()
            "types" -> types()
            "packs" -> browsePacks()
            else -> home()
        }
    }
    override fun onResume() { super.onResume(); FrontendRefreshRate.apply(this) }
    override fun onDestroy() { batchCancelled.set(true);batchPool?.shutdownNow();worker.shutdownNow();super.onDestroy() }
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(keyCode==KeyEvent.KEYCODE_BUTTON_A && currentFocus !is EditText) {
            if(event.repeatCount==0) {
                val focus=currentFocus
                if(focus is ListView && focus.selectedItemPosition>=0)
                    focus.performItemClick(focus.selectedView,focus.selectedItemPosition,focus.selectedItemId)
                else focus?.performClick()
            }
            return true
        }
        if(keyCode==KeyEvent.KEYCODE_BUTTON_B) { finish();return true }
        return super.onKeyDown(keyCode,event)
    }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun rowBackground(focused:Boolean)=GradientDrawable().apply {
        setColor(Color.TRANSPARENT);cornerRadius=dp(16).toFloat()
        setStroke(dp(1),if(focused) Color.rgb(5,97,195) else Color.rgb(38,49,65))
    }
    private fun label(value:String,size:Float=16f) = TextView(this).apply { text=value;textSize=size;setTextColor(Color.WHITE);setPadding(dp(4),dp(5),dp(4),dp(5)) }
    private fun action(title:String,run:()->Unit) {
        body.addView(Button(this).apply {
            text=title;textSize=16f;setTextColor(Color.WHITE);isAllCaps=false;defaultFocusHighlightEnabled=false;stateListAnimator=null
            backgroundTintList=null;background=rowBackground(false);setPadding(dp(16),0,dp(16),0)
            setOnFocusChangeListener { view,focus -> view.background=rowBackground(focus) }
            setOnClickListener { if(!busy)run() }
        },LinearLayout.LayoutParams(-1,dp(58)).apply { topMargin=dp(8) })
    }
    private fun <T> work(message:String,task:()->T,done:(T)->Unit) {
        if(busy)return
        busy=true;status.text=message
        worker.execute {
            val result=runCatching(task)
            runOnUiThread {
                busy=false
                if(!isFinishing && !isDestroyed) result.onSuccess { status.text="";done(it) }.onFailure {
                    status.text=it.message ?: "The operation failed. Please try again."
                }
            }
        }
    }
    private fun home() {
        if(intent.hasExtra("action")) { finish();return }
        body.removeAllViews()
        if(provider=="NeoAssets") {
            body.addView(label("Game metadata, covers, screenshots, logos, fanart, videos and system artwork packs. Credits are saved with downloaded packs."))
            action("Scrape a game") { systems() }
            action("Browse artwork packs") { browsePacks() }
            action("Open NeoAssets website") { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://neoassets.dev"))) }
        } else {
            body.addView(label(if(provider=="ScreenScraper") "ScreenScraper requires developer credentials. Your personal account is optional but may be required by the service." else "Add your personal $provider API key to search and download game media."))
            action("Account / API credentials") { credentials() }
            action("Media types") { types() }
            action("Scrape a game") { systems() }
        }
        body.addView(label("Downloads are saved under media in your selected user data location:\n${UserDataStore.location(this)}",13f))
        action("Back") { finish() }
    }
    private fun credentials() {
        body.removeAllViews()
        val fields = if(provider=="ScreenScraper") listOf("developerId" to "Developer ID","developerPassword" to "Developer password","username" to "ScreenScraper username","password" to "ScreenScraper password") else listOf("apiKey" to "API key")
        val editors=fields.map { (key,title) ->
            body.addView(label(title))
            key to EditText(this).apply {
                isSingleLine=true;setTextColor(Color.WHITE)
                inputType=InputType.TYPE_CLASS_TEXT or if(key.contains("password",true)||key=="apiKey") InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(preferences.getString("$provider.$key",""));body.addView(this)
            }
        }
        action("Save") {
            preferences.edit().apply { editors.forEach { (key,field)->putString("$provider.$key",field.text.toString().trim()) } }.apply()
            home();status.text="Credentials saved on this device."
        }
        action("Back") { home() }
    }
    private fun types() {
        body.removeAllViews()
        val saved=preferences.getStringSet("media.categories",ScraperMediaTypes.categories.map { it.folder }.toSet()).orEmpty()
        val checks=ScraperMediaTypes.categories.map { category -> category.folder to CheckBox(this).apply {
            text=category.title;setTextColor(Color.WHITE);isChecked=category.folder in saved;body.addView(this)
        } }
        action("Save media types") {
            preferences.edit().putStringSet("media.categories",checks.filter { it.second.isChecked }.map { it.first }.toSet()).apply();home()
        }
        action("Back") { home() }
    }
    private fun systems() {
        work("Loading scanned systems…", { LibraryRepository(this).populatedSystems() }) { systems ->
            body.removeAllViews()
            if(systems.isEmpty())body.addView(label("Scan your ROM folders first."))
            systems.forEach { system -> action(system.title) { games(system) } }
            action("Back") { home() }
        }
    }
    private fun games(system:SystemSpec,message:String="") {
        work("Loading ${system.title}…", {
            val entries = LibraryRepository(this).cached(system)?.games.orEmpty()
            gamesForScraping(entries.map { Game(it.title, Uri.parse(it.uri), it.fileName, it.directory) })
        }) { games ->
            body.removeAllViews();body.addView(label(system.title,22f))
            if(provider=="ScreenScraper" && games.isNotEmpty())action("Scrape multiple games") { selectBatch(system,games) }
            val list=ListView(this).apply {
                adapter=object:ArrayAdapter<Game>(this@ScraperActivity,android.R.layout.simple_list_item_1,games) {
                    override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup):android.view.View =
                        (super.getView(position,convertView,parent) as TextView).apply { text=games[position].title;setTextColor(Color.WHITE) }
                }
                setOnItemClickListener { _,_,position,_ -> search(system,games[position]) }
            }
            body.addView(list,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*0.65f).toInt()))
            action("Back") { systems() }
            status.text=message
        }
    }
    private fun selectBatch(system:SystemSpec,games:List<Game>) {
        val selected=BooleanArray(games.size)
        val dialog=AlertDialog.Builder(this).setTitle("Select games to scrape")
            .setMultiChoiceItems(games.map { it.title }.toTypedArray(),selected) { _,index,checked -> selected[index]=checked }
            .setPositiveButton("Scrape selected",null).setNeutralButton("Select all",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                selected.indices.forEach { selected[it]=true;dialog.listView.setItemChecked(it,true) }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen=games.filterIndexed { index,_ -> selected[index] }
                if(chosen.isNotEmpty()) { dialog.dismiss();scrapeBatch(system,chosen) }
            }
        }
        dialog.show()
    }
    private fun scrapeBatch(system:SystemSpec,games:List<Game>) {
        body.removeAllViews();body.addView(label("Scraping ${games.size} games · ${system.title}",22f))
        val cancel=Button(this).apply { text="Cancel";SelectionStyle.apply(this);setOnClickListener { batchCancelled.set(true);text="Stopping…";isEnabled=false } }
        body.addView(cancel);batchCancelled.set(false)
        work("Checking ScreenScraper account…", {
            val allowed=ScrapePolicy.parallelism(ScraperClient(this,provider).accountConcurrency(),ScrapePolicy.requestedConcurrency(this))
            val pool=Executors.newFixedThreadPool(allowed);batchPool=pool
            val completion=java.util.concurrent.ExecutorCompletionService<Pair<Game,Result<String>>>(pool)
            var next=0;var finished=0;var successful=0;var unmatched=0;var skipped=0
            val failures=mutableListOf<String>()
            fun submit() {
                val game=games[next++]
                completion.submit(java.util.concurrent.Callable {
                    game to runCatching {
                        val plan = ScrapePolicy.mediaPlan(this, system, game, provider)
                        if (plan.skip) return@runCatching "Skipped"
                        val searchClient=ScraperClient(this,provider)
                        val match=searchClient.searchGame(system, game) ?: return@runCatching "No match"
                        if(batchCancelled.get() || Thread.currentThread().isInterrupted)throw InterruptedException()
                        GameScraper.scrape(this,provider,system,game,match,parallelGames=true,plan=plan)
                    }
                })
            }
            try {
                repeat(minOf(allowed,games.size)) { submit() }
                while(finished<games.size && !batchCancelled.get()) {
                    val future=completion.poll(250,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val (game,result)=future.get();finished++
                    result.onSuccess { if(it=="No match")unmatched++ else if(it=="Skipped" || it.startsWith(GameScraper.NO_AVAILABLE_MEDIA))skipped++ else if(it.startsWith("Saved 0") || it.contains("downloads failed"))failures.add("${game.title}: $it") else successful++ }
                        .onFailure { failures.add("${game.title}: ${it.message ?: "Scrape failed"}") }
                    runOnUiThread { if(!isDestroyed)status.text="$finished/${games.size} games · $successful scraped · $unmatched not found · $skipped skipped · ${failures.size} issues · $allowed concurrent" }
                    if(next<games.size && !batchCancelled.get())submit()
                }
            } finally { pool.shutdownNow();batchPool=null }
            "$successful games scraped · $unmatched not found · $skipped skipped · ${failures.size} issues.${if(batchCancelled.get()) " Cancelled; completed artwork kept." else ""}"+
                failures.take(5).joinToString("\n",prefix=if(failures.isEmpty())"" else "\n")
        }) { summary ->
            GameArtworkPanel.clearMediaCache();EsDeScene.clearMediaCache()
            games(system,summary)
        }
    }
    private fun search(system:SystemSpec,game:Game) {
        body.removeAllViews()
        body.addView(label("Search for ${game.title} · ${system.title}"))
        val initialTitle = ScreenScraperMatch.cleanTitle(game.title)
        val input=EditText(this).apply {
            setText(initialTitle)
            setTextColor(Color.WHITE);isSingleLine=true
        }
        body.addView(input)
        action("Search $provider") {
            val title=input.text.toString().trim()
            if(title.isNotBlank())work("Searching $provider…", {
                if (title == initialTitle) listOfNotNull(client.searchGame(system, game))
                else client.search(title, system, game.isPlaylist)
            }) { matches ->
                val match=matches.firstOrNull()
                if(match!=null) scrape(system,game,match)
                else {
                    body.removeAllViews();body.addView(label("No matches. Try a different game title."))
                    action("Search again") { search(system,game) }
                    action("Back") { games(system) }
                }
            }
        }
        action("Back") { games(system) }
    }
    private fun scrape(system:SystemSpec,game:Game,match:ScraperClient.Match) {
        work("Downloading ${match.title}…", {
            GameScraper.scrape(this,provider,system,game,match)
        }) { message -> games(system,message) }
    }
    private fun browsePacks() {
        work("Loading NeoAssets packs…", {client.packs()}) { packs ->
            body.removeAllViews()
            packs.forEach { pack ->
                action("${pack.optString("name")} · ${pack.optString("author")}") {
                    AlertDialog.Builder(this).setTitle(pack.optString("name")).setMessage(pack.optString("description")+"\n\nBy "+pack.optString("author"))
                        .setPositiveButton("Download") { _,_ -> downloadPack(pack.getString("folder")) }.setNegativeButton("Cancel",null).show()
                }
            }
            action("Back") { home() }
        }
    }
    private fun downloadPack(folder:String) {
        work("Downloading artwork pack…", {
            require(folder.matches(Regex("[a-zA-Z0-9_-]+")))
            val pack=client.pack(folder)
            var count=0;val errors=mutableListOf<String>()
            ScraperClient.objects(pack.opt("files")).forEach { file ->
                runCatching { UserDataStore.writeScraped(this,"system-packs/$folder",file.getString("file_name"),client.bytes(file.getString("url")));count++ }
                    .onFailure { errors.add(file.optString("file_name")) }
            }
            UserDataStore.writeScraped(this,"system-packs/$folder","pack.json",pack.toString(2).toByteArray())
            "Downloaded $count files to media/system-packs/$folder in your user data location.${if(errors.isEmpty()) "" else " ${errors.size} files failed; you can retry the pack."}"
        }) { status.text=it }
    }
}
