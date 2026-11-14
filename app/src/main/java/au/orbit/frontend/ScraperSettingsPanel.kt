package au.orbit.frontend

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scraping stays inside the settings detail pane, including its queue and progress. */
class ScraperSettingsPanel(private val activity:Activity,private val selectedSystems:()->List<SystemSpec>,private val visibleGames:(SystemSpec)->List<Game>,private val changed:()->Unit) {
    private var chooseSystems: () -> Unit = {}
    val view=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val worker=Executors.newSingleThreadExecutor()
    private val cancelled=AtomicBoolean(false)
    private var pool:java.util.concurrent.ExecutorService?=null
    private var closed=false
    private var busy=false
    private var backgroundProgress: ScrapeProgress.Job? = null
    private var provider="ScreenScraper"
    private var queuedGames: Int? = null
    private var problemGames = emptyList<String>()
    private val status=TextView(activity).apply { textSize=15f;setTextColor(Color.WHITE) }
    private data class GameProgress(val spec:SystemSpec,val game:Game,var phase:String="Searching",var mediaTotal:Int=0,var finished:Boolean=false,var issue:Boolean=false,var currentMedia:String?=null,var requested:List<String> = emptyList(),val saved:MutableMap<String,String> = linkedMapOf(),var thumbnail:android.graphics.Bitmap?=null,var thumbnailPath:String?=null)
    private data class ScrapeTarget(val spec: SystemSpec, val game: Game, val plan: ScrapePolicy.MediaPlan)
    private data class TaskResult(val slot:Int,val spec:SystemSpec,val game:Game,val result:Result<String>)
    private val progressRows=linkedMapOf<Int,GameProgress>()
    private var scrapedCount=0
    private var accountSlots:Int?=null
    private var workerSlots:Int?=null
    private var selectedTile=0
    private val tileSpacing = dp(8)
    private val gridHeight = (activity.resources.displayMetrics.heightPixels - dp(270)).coerceAtLeast(dp(248))
    private var gridRefreshPending = false
    private val previewWorker=Executors.newSingleThreadExecutor()
    private var popup:AlertDialog?=null
    private var popupGame:GameProgress?=null
    private var popupStatus:TextView?=null
    private var popupImage:ImageView?=null
    private var popupCaption:TextView?=null
    private var popupPath:String?=null
    private var previewIndex=0
    private var previewTicket=0
    private val totalLabel=TextView(activity).apply { textSize=24f;setTextColor(Color.WHITE);typeface=android.graphics.Typeface.DEFAULT_BOLD }
    private val slotLabel=TextView(activity).apply { textSize=14f;setTextColor(Color.rgb(148,161,180)) }
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
    private val start=Button(activity).apply {
        text="Start scraping";isFocusable=true;isFocusableInTouchMode=true;isAllCaps=false;textSize=17f;setTextColor(Color.WHITE);SelectionStyle.apply(this)
        setOnClickListener {
            if (busy) cancelScraping() else scrape()
        }
    }
    private val resultsButton = Button(activity).apply {
        text = "Show games with scrape issues"
        isAllCaps = false
        SelectionStyle.apply(this)
        setOnClickListener { showProblems() }
    }
    private val gridAdapter=object:BaseAdapter() {
        override fun getCount()=progressRows.size
        override fun getItem(position:Int)=progressRows.values.elementAt(position)
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:android.view.View?,parent:ViewGroup):android.view.View {
            val item=getItem(position)
            return LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;isFocusable=false
                setPadding(dp(5), dp(3), dp(5), dp(4))
                val dense = (workerSlots ?: 0) > 3
                val tileHeight = if (dense) (gridHeight - tileSpacing) / 2 else dp(205)
                layoutParams = AbsListView.LayoutParams(-1, tileHeight)
                background=android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT);cornerRadius=dp(12).toFloat()
                    setStroke(dp(2),Color.rgb(38,49,65))
                }
                addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(item.thumbnail)
                    contentDescription = "${item.game.title} artwork"
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(TextView(activity).apply {
                    text = item.game.title
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    includeFontPadding = false
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(-1, -2))
                val media = item.currentMedia?.takeUnless { item.finished }
                val phase = if (media == null) item.phase
                    else ScraperMediaTypes.categories.firstOrNull { it.folder == media }?.title ?: media
                addView(TextView(activity).apply {
                    text = if (item.mediaTotal > 0) "$phase · ${item.saved.size}/${item.mediaTotal} saved" else phase
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    includeFontPadding = false
                    ellipsize = android.text.TextUtils.TruncateAt.START
                    setTextColor(if (item.issue) Color.rgb(255,185,110) else Color.rgb(148,161,180))
                }, LinearLayout.LayoutParams(-1, -2))
                addView(scraperProgressBar(activity).apply {
                    max = item.mediaTotal.coerceAtLeast(1)
                    progress = item.saved.size
                }, LinearLayout.LayoutParams(-1, dp(10)))
            }
        }
    }
    private val grid=ResponsiveGridView(activity, 150).apply {
        horizontalSpacing=dp(12);verticalSpacing=tileSpacing;stretchMode=GridView.STRETCH_COLUMN_WIDTH
        isFocusable=true;isFocusableInTouchMode=true;defaultFocusHighlightEnabled=false
        selector=android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT);cornerRadius=dp(12).toFloat();setStroke(dp(2),Color.rgb(5,97,195)) }
        setDrawSelectorOnTop(true);adapter=gridAdapter
        setOnItemClickListener { _,_,position,_ ->
            selectTile(position);requestFocus()
            progressRows.values.elementAtOrNull(position)?.let(::showGamePopup)
        }
    }
    init {
        view.addView(totalLabel);view.addView(slotLabel);view.addView(start)
        view.addView(status)
        view.addView(resultsButton)
        view.addView(grid,LinearLayout.LayoutParams(-1,gridHeight))
        refreshLabels()
    }
    private fun refreshLabels() {
        if (!busy) {
            backgroundProgress?.finish()
            backgroundProgress = null
        }
        val stopping = busy && cancelled.get()
        start.text = when {
            stopping -> "Stopping…"
            busy -> "Cancel scraping"
            else -> "Start scraping"
        }
        start.isEnabled = !stopping
        resultsButton.visibility = if (!busy && problemGames.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        grid.visibility=if(progressRows.isEmpty())android.view.View.GONE else android.view.View.VISIBLE
        totalLabel.text="$scrapedCount / ${queuedGames ?: selectedSystems().sumOf { visibleGames(it).size }} scraped"
        val active=progressRows.values.count { !it.finished }
        slotLabel.text = accountSlots?.let { limit ->
            val limitLabel = if (provider == "ScreenScraper") "$limit account slots" else "$limit game slots"
            "$active / ${workerSlots ?: limit} running · $limitLabel"
        } ?: "Concurrency follows the selected provider"
        if (!gridRefreshPending) {
            gridRefreshPending = true
            grid.postOnAnimation {
                gridRefreshPending = false
                if (!closed) {
                    gridAdapter.notifyDataSetChanged()
                    if (progressRows.isNotEmpty()) grid.setSelection(selectedTile.coerceAtMost(progressRows.size - 1))
                }
            }
        }
        updateGamePopup()
    }
    private fun cancelScraping() {
        cancelled.set(true)
        popup?.dismiss()
        if (grid.hasFocus()) start.requestFocus()
        progressRows.clear()
        selectedTile = 0
        gridAdapter.notifyDataSetChanged()
        backgroundProgress?.finish()
        backgroundProgress = null
        status.text = "Stopping; completed artwork is kept…"
        refreshLabels()
    }

    private fun resetProgress() {
        scrapedCount=0;accountSlots=null;workerSlots=null;selectedTile=0
        queuedGames = null
        problemGames = emptyList()
        progressRows.clear()
        refreshLabels()
    }
    val navigationRows get()=listOf(start,resultsButton,grid)

    private fun showProblems() {
        val host = (activity as? MainActivity)?.popupContext() ?: activity
        val report = problemGames.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val dialog = AlertDialog.Builder(host).setTitle("Scrape issues · ${report.size}")
            .setItems(report.toTypedArray(), null).setPositiveButton("Close", null).create()
        dialog.styleArtworkWindow(host)
        dialog.setOnKeyListener { _, key, event ->
            if (key != android.view.KeyEvent.KEYCODE_BUTTON_B && key != android.view.KeyEvent.KEYCODE_BUTTON_A) false
            else {
                if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) dialog.dismiss()
                true
            }
        }
        dialog.show()
        (activity as? MainActivity)?.trackPopup(dialog)
        SelectionStyle.apply(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
    }
    fun ownsGrid(candidate:GridView)=candidate===grid
    fun tilePosition()=selectedTile
    fun selectTile(position:Int) {
        selectedTile=position.coerceIn(0,(grid.count-1).coerceAtLeast(0))
        grid.setSelection(selectedTile)
        grid.post {
            val child=grid.getChildAt(selectedTile-grid.firstVisiblePosition)
            child?.requestRectangleOnScreen(android.graphics.Rect(0,0,child.width,child.height),true)
        }
    }
    fun loadPacks(loaded: (Result<List<org.json.JSONObject>>) -> Unit) {
        if (busy) {
            loaded(Result.failure(IllegalStateException("Wait for the current scraper task to finish.")))
            return
        }
        busy=true;cancelled.set(false);status.text="Loading NeoAssets packs…";refreshLabels()
        worker.execute {
            val result=runCatching { ScraperClient(activity,"NeoAssets").packs() }
            activity.runOnUiThread {
                if(closed)return@runOnUiThread
                busy=false;refreshLabels()
                status.text = result.fold({ "" }, { it.message.orEmpty() })
                loaded(result)
            }
        }
    }
    fun downloadPack(folder:String) {
        if (busy) return
        busy=true;cancelled.set(false);status.text="Downloading artwork pack…";resetProgress()
        worker.execute {
            val result=runCatching {
                require(folder.matches(Regex("[a-zA-Z0-9_-]+")))
                val client=ScraperClient(activity,"NeoAssets");val pack=client.pack(folder)
                var completed=0
                for(file in ScraperClient.objects(pack.opt("files"))) {
                    if(cancelled.get())break
                    UserDataStore.writeScraped(activity,"system-packs/$folder",file.getString("file_name"),client.bytes(file.getString("url")))
                    completed++
                    val message="$completed pack files downloaded"
                    activity.runOnUiThread { if (!closed && !cancelled.get()) status.text=message }
                }
                if(!cancelled.get())UserDataStore.writeScraped(activity,"system-packs/$folder","pack.json",pack.toString(2).toByteArray())
                "$completed pack files saved.${if(cancelled.get()) " Cancelled." else ""}"
            }
            activity.runOnUiThread { if(!closed) { busy=false;status.text=result.getOrElse { it.message ?: "Pack download failed" };refreshLabels();changed() } }
        }
    }
    private fun scrape() {
        val targets=selectedSystems()
        if(targets.isEmpty()) { status.text="Scan your ROM folders first.";chooseSystems();return }
        backgroundProgress = ScrapeProgress.start()
        val mode = ScrapePolicy.mode(activity)
        val source = activity.getSharedPreferences("scraper_credentials", 0)
            .getString("scrape.provider", "ScreenScraper") ?: "ScreenScraper"
        val snapshot = targets.map { it to visibleGames(it) }
        busy = true
        cancelled.set(false)
        status.text = if (mode == "all") "Loading selected games…" else "Checking saved media…"
        resetProgress()
        worker.execute {
            val result = runCatching {
                val planMedia = ScrapePolicy.mediaPlanner(activity, mode, source)
                snapshot.flatMap { (spec, entries) ->
                    activity.gamesForScraping(entries).mapNotNull { game ->
                        if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                        val plan = planMedia(spec, game)
                        if (plan.skip) null else ScrapeTarget(spec, game, plan)
                    }
                }
            }
            activity.runOnUiThread {
                if(closed)return@runOnUiThread
                if (cancelled.get()) {
                    busy = false
                    status.text = "Cancelled"
                    refreshLabels()
                    return@runOnUiThread
                }
                result.onSuccess { entries ->
                    queuedGames = entries.size
                    if (entries.isEmpty()) {
                        busy = false
                        status.text = when {
                            snapshot.all { it.second.isEmpty() } -> "No scanned games in the selected systems."
                            mode == "missing" -> "No available missing media to scrape."
                            else -> "All selected games already have media."
                        }
                        refreshLabels()
                        changed()
                    } else scrapeLoaded(entries, source)
                }.onFailure { busy=false;status.text=it.message;refreshLabels() }
            }
        }
    }
    private fun scrapeLoaded(chosen: List<ScrapeTarget>, source: String) {
        val progressJob = backgroundProgress ?: return
        progressJob.update(0, chosen.size)
        var gamesWithSavedMedia = 0
        fun postProgress(update: () -> Unit) = activity.runOnUiThread {
            if (!closed && backgroundProgress === progressJob) update()
        }
        provider=source;status.text="Checking $source…";refreshLabels()
        worker.execute {
            val problems = mutableListOf<String>()
            val result=runCatching {
                if (cancelled.get()) return@runCatching "Cancelled; completed artwork kept."
                val accountAllowed=ScraperClient(activity,source).accountConcurrency()
                if (cancelled.get()) return@runCatching "Cancelled; completed artwork kept."
                val allowed = minOf(chosen.size, ScrapePolicy.parallelism(accountAllowed, ScrapePolicy.requestedConcurrency(activity)))
                val executor=Executors.newFixedThreadPool(allowed);pool=executor
                postProgress { accountSlots=accountAllowed;workerSlots=allowed;refreshLabels() }
                val completion=ExecutorCompletionService<TaskResult>(executor)
                var next=0;var completed=0;var success=0;var missing=0;var unavailable=0
                val errors=mutableListOf<String>()
                fun submit(slot:Int) {
                    val (spec, game, plan) = chosen[next++]
                    completion.submit(java.util.concurrent.Callable {
                        postProgress { progressRows[slot]=GameProgress(spec,game);refreshLabels() }
                        TaskResult(slot,spec,game,runCatching {
                            if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                            val match=ScraperClient(activity,source).searchGame(spec, game) ?: return@runCatching "No match"
                            if(cancelled.get() || Thread.currentThread().isInterrupted)throw InterruptedException()
                            postProgress { progressRows[slot]?.phase="Fetching media";refreshLabels() }
                            GameScraper.scrape(activity,source,spec,game,match,parallelGames=true,plan=plan,onMediaPlan={ types -> postProgress {
                                progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let {
                                    it.requested = types
                                    it.mediaTotal = types.size
                                }
                                refreshLabels()
                            } },onMediaSaved={ type,path -> postProgress {
                                progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { item ->
                                    if (item.saved.isEmpty()) progressJob.update(++gamesWithSavedMedia, chosen.size)
                                    item.saved[type] = path
                                    if (type != "videos" && type != "manuals") loadTileArtwork(item, path)
                                }
                                refreshLabels()
                            } },onMediaStarted={ type -> postProgress {
                                progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { it.currentMedia=type;it.phase="Downloading media" };refreshLabels()
                            } })
                        })
                    })
                }
                try {
                    repeat(allowed) { slot -> submit(slot) }
                    while(completed<chosen.size && !cancelled.get()) {
                        val future=completion.poll(250,TimeUnit.MILLISECONDS) ?: continue
                        val (slot,spec,game,outcome)=future.get();completed++
                        val noAvailableMedia = outcome.getOrNull()?.startsWith(GameScraper.NO_AVAILABLE_MEDIA) == true
                        outcome.onSuccess {
                            when {
                                it == "No match" -> missing++
                                noAvailableMedia -> unavailable++
                                it.startsWith("Saved 0") || it.contains("downloads failed") -> errors.add("${game.title}: $it")
                                else -> success++
                            }
                        }.onFailure { errors.add("${game.title}: ${it.message}") }
                        val progress="$completed/${chosen.size} · $success scraped · $missing not found · ${errors.size} issues · $allowed concurrent"
                        val issue=outcome.isFailure || outcome.getOrNull()=="No match" || outcome.getOrNull()?.contains("downloads failed")==true || outcome.getOrNull()?.startsWith("Saved 0")==true
                        val totalScraped=success
                        val phase=when { outcome.isFailure -> "Failed";outcome.getOrNull()=="No match" -> "Not found";noAvailableMedia -> "Media unavailable";issue -> "Media incomplete";else -> "Scraped" }
                        if (issue) {
                            val detail = outcome.exceptionOrNull()?.message ?: outcome.getOrNull()
                            problems.add("${game.title} · ${spec.title}\n$phase" +
                                (detail?.takeUnless { it == "No match" }?.let { ": $it" } ?: ""))
                        }
                        postProgress {
                            status.text=progress;scrapedCount=totalScraped
                            progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { it.finished=true;it.phase=phase;it.issue=issue }
                            refreshLabels()
                        }
                        if(next<chosen.size && !cancelled.get())submit(slot)
                    }
                } finally { executor.shutdownNow();pool=null }
                val unavailableSummary = if (unavailable == 0) "" else "$unavailable without available media · "
                "$success games scraped · $missing not found · $unavailableSummary${errors.size} issues.${if(cancelled.get()) " Cancelled; completed artwork kept." else ""}"
            }
            activity.runOnUiThread {
                if(closed)return@runOnUiThread
                problemGames = problems.toList()
                busy=false;progressRows.values.filter { !it.finished }.forEach { it.finished=true;it.phase="Stopped" };status.text=result.getOrElse {
                    if (cancelled.get()) "Cancelled; completed artwork kept." else it.message ?: "Scrape failed"
                };refreshLabels();changed()
            }
        }
    }
    private fun loadTileArtwork(item:GameProgress,path:String) {
        item.thumbnailPath=path
        previewWorker.execute {
            val bitmap=runCatching {
                val group="${item.spec.id}/${ScraperClient.gameKey(item.game.uri.toString())}"
                val uri=UserDataStore.scrapedUri(activity,group,path) ?: return@runCatching null
                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it,null,bounds) }
                var sample=1
                while(bounds.outWidth/sample>480 || bounds.outHeight/sample>480)sample*=2
                activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it,null,android.graphics.BitmapFactory.Options().apply { inSampleSize=sample }) }
            }.getOrNull()
            activity.runOnUiThread {
                if(!closed && item.thumbnailPath==path && progressRows.values.any { it===item } && bitmap!=null) {
                    item.thumbnail=bitmap;refreshLabels()
                }
            }
        }
    }
    private fun showGamePopup(item:GameProgress) {
        popup?.dismiss();popupGame=item;popupPath=null;previewIndex=0
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8)) }
        popupImage=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
        body.addView(popupImage,LinearLayout.LayoutParams(-1,dp(170)))
        popupCaption=TextView(activity).apply { textSize=14f;setTextColor(Color.WHITE);gravity=android.view.Gravity.CENTER }
        body.addView(popupCaption)
        val navigation=LinearLayout(activity)
        listOf("Previous" to -1,"Next" to 1).forEach { (title,direction) ->
            navigation.addView(Button(activity).apply { text=title;isAllCaps=false;SelectionStyle.apply(this);setOnClickListener { previewIndex+=direction;popupPath=null;updateGamePopup() } },LinearLayout.LayoutParams(0,dp(40),1f))
        }
        body.addView(navigation)
        popupStatus=TextView(activity).apply { textSize=14f;setTextColor(Color.WHITE);setPadding(0,dp(8),0,0) }
        body.addView(popupStatus)
        val dialog=AlertDialog.Builder((activity as? MainActivity)?.popupContext() ?: activity).setTitle(item.game.title).setView(body).setNegativeButton("Close",null).create()
        popup=dialog
        dialog.setOnDismissListener { if(popup===dialog) { popup=null;popupGame=null;previewTicket++;popupImage=null;popupCaption=null;popupStatus=null } }
        dialog.setOnKeyListener { _,key,event ->
            if(key==android.view.KeyEvent.KEYCODE_BUTTON_A) { if(event.action==android.view.KeyEvent.ACTION_DOWN && event.repeatCount==0)(dialog.currentFocus ?: dialog.getButton(AlertDialog.BUTTON_NEGATIVE))?.performClick();true }
            else if(key==android.view.KeyEvent.KEYCODE_BUTTON_B) { if(event.action==android.view.KeyEvent.ACTION_UP)dialog.dismiss();true }
            else if(key in listOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT,android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if(event.action==android.view.KeyEvent.ACTION_DOWN) { previewIndex+=if(key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1;popupPath=null;updateGamePopup() };true
            } else false
        }
        dialog.styleArtworkWindow(activity)
        dialog.show();(activity as? MainActivity)?.trackPopup(dialog)
        dialog.window?.setLayout((((activity as? MainActivity)?.popupContext() ?: activity).resources.displayMetrics.widthPixels*0.66f).toInt(),-2)
        SelectionStyle.apply(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
        FrontendNavigationBar.apply(activity, dialog.window)
        updateGamePopup()
    }
    private fun updateGamePopup() {
        val item=popupGame ?: return
        popupStatus?.text="${item.spec.title} · ${item.phase}\n"+item.requested.joinToString("\n") { type ->
            val title=ScraperMediaTypes.categories.firstOrNull { it.folder==type }?.title ?: type
            "${if(type in item.saved) "✓" else "…"} $title"
        }
        val images=item.saved.filterKeys { it!="videos" && it!="manuals" }.entries.toList()
        if(images.isEmpty()) { popupCaption?.text="Artwork appears here as it downloads";return }
        val image=images[Math.floorMod(previewIndex,images.size)]
        popupCaption?.text=(ScraperMediaTypes.categories.firstOrNull { it.folder==image.key }?.title ?: image.key)+" · ${Math.floorMod(previewIndex,images.size)+1}/${images.size}"
        if(popupPath==image.value)return
        popupPath=image.value;val ticket=++previewTicket
        previewWorker.execute {
            val bitmap=runCatching {
                val group="${item.spec.id}/${ScraperClient.gameKey(item.game.uri.toString())}"
                val uri=UserDataStore.scrapedUri(activity,group,image.value) ?: return@runCatching null
                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it,null,bounds) }
                var sample=1
                while(bounds.outWidth/sample>900 || bounds.outHeight/sample>900)sample*=2
                activity.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it,null,android.graphics.BitmapFactory.Options().apply { inSampleSize=sample }) }
            }.getOrNull()
            activity.runOnUiThread { if(!closed && previewTicket==ticket && popupGame===item)popupImage?.setImageBitmap(bitmap) }
        }
    }
    fun attach(parent:LinearLayout, chooseSystems: () -> Unit) {
        this.chooseSystems = chooseSystems
        (view.parent as? ViewGroup)?.removeView(view);parent.addView(view)
        refreshLabels()
    }
    fun destroy() {
        closed=true;popup?.dismiss();cancelled.set(true);pool?.shutdownNow();worker.shutdownNow();previewWorker.shutdownNow()
        backgroundProgress?.finish()
        backgroundProgress = null
    }
}
