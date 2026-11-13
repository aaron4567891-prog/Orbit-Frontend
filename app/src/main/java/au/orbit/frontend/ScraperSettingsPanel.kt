package au.orbit.frontend

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scraping stays inside the settings detail pane, including its queue and progress. */
class ScraperSettingsPanel(private val activity:Activity,private val selectedSystems:()->List<SystemSpec>,private val gameCount:(SystemSpec)->Int,private val changed:()->Unit) {
    private var chooseSystems: () -> Unit = {}
    val view=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val worker=Executors.newSingleThreadExecutor()
    private val cancelled=AtomicBoolean(false)
    private var pool:java.util.concurrent.ExecutorService?=null
    private var closed=false
    private var busy=false
    private var provider="ScreenScraper"
    private var games=emptyList<Game>()
    private var gameSystems=emptyList<SystemSpec>()
    private val selected=mutableSetOf<Int>()
    private val status=TextView(activity).apply { textSize=15f;setTextColor(Color.WHITE) }
    private data class GameProgress(val spec:SystemSpec,val game:Game,var phase:String="Searching",var mediaDone:Int=0,var mediaTotal:Int=0,var finished:Boolean=false,var issue:Boolean=false,var currentMedia:String?=null,var requested:List<String> = emptyList(),val saved:MutableMap<String,String> = linkedMapOf(),var thumbnail:android.graphics.Bitmap?=null,var thumbnailPath:String?=null)
    private data class TaskResult(val slot:Int,val spec:SystemSpec,val game:Game,val result:Result<String>)
    private val progressRows=linkedMapOf<Int,GameProgress>()
    private var scrapedCount=0
    private var accountSlots:Int?=null
    private var workerSlots:Int?=null
    private var selectedTile=0
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
            if(busy) { cancelled.set(true);status.text="Stopping; completed artwork is kept…" }
            else scrape()
        }
    }
    private val gridAdapter=object:BaseAdapter() {
        override fun getCount()=progressRows.size
        override fun getItem(position:Int)=progressRows.values.elementAt(position)
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:android.view.View?,parent:ViewGroup):android.view.View {
            val item=getItem(position)
            return LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;isFocusable=false
                setPadding(dp(12),dp(8),dp(12),dp(8));layoutParams=AbsListView.LayoutParams(-1,dp(205))
                background=android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT);cornerRadius=dp(12).toFloat()
                    setStroke(dp(2),Color.rgb(38,49,65))
                }
                addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER;setImageBitmap(item.thumbnail);contentDescription="${item.game.title} artwork" },LinearLayout.LayoutParams(-1,0,1f))
                addView(TextView(activity).apply { text=item.game.title;textSize=16f;gravity=android.view.Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(Color.WHITE) })
                addView(TextView(activity).apply { text=item.spec.title;textSize=12f;gravity=android.view.Gravity.CENTER;maxLines=1;setTextColor(Color.rgb(148,161,180)) })
                addView(TextView(activity).apply { text=if(!item.finished && item.currentMedia!=null)ScraperMediaTypes.categories.firstOrNull { it.folder==item.currentMedia }?.title ?: item.currentMedia else item.phase;textSize=14f;gravity=android.view.Gravity.CENTER;maxLines=1;setTextColor(if(item.issue)Color.rgb(255,185,110) else Color.WHITE) })
                if(!item.finished && item.mediaTotal>0)addView(TextView(activity).apply { text="${item.mediaDone} / ${item.mediaTotal} media";textSize=12f;gravity=android.view.Gravity.CENTER;setTextColor(Color.rgb(148,161,180)) })
                addView(ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate=!item.finished && item.mediaTotal==0
                    max=item.mediaTotal.coerceAtLeast(1);progress=if(item.finished)max else item.mediaDone
                },LinearLayout.LayoutParams(-1,dp(10)))
            }
        }
    }
    private val grid=GridView(activity).apply {
        numColumns=3;horizontalSpacing=dp(12);verticalSpacing=dp(12);stretchMode=GridView.STRETCH_COLUMN_WIDTH
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
        view.addView(grid,LinearLayout.LayoutParams(-1,(activity.resources.displayMetrics.heightPixels-dp(270)).coerceAtLeast(dp(220))))
        view.addView(status)
        refreshLabels()
    }
    private fun refreshLabels() {
        start.text=if(busy)"Cancel scraping" else "Start scraping"
        start.isEnabled=true
        grid.visibility=if(progressRows.isEmpty())android.view.View.GONE else android.view.View.VISIBLE
        totalLabel.text="$scrapedCount / ${if(selected.isEmpty())selectedSystems().sumOf(gameCount) else selected.size} scraped"
        val active=progressRows.values.count { !it.finished }
        slotLabel.text=accountSlots?.let { "$active / ${workerSlots ?: it} running · $it account slots" } ?: "Concurrency follows your account allowance"
        gridAdapter.notifyDataSetChanged()
        if(progressRows.isNotEmpty())grid.setSelection(selectedTile.coerceAtMost(progressRows.size-1))
        updateGamePopup()
    }
    private fun resetProgress() {
        scrapedCount=0;accountSlots=null;workerSlots=null;progressRows.clear()
        refreshLabels()
    }
    val navigationRows get()=listOf(start,grid)
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
        busy=true;status.text="Loading NeoAssets packs…";refreshLabels()
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
        busy=true;cancelled.set(false);status.text="Downloading artwork pack…";refreshLabels()
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
                    activity.runOnUiThread { if(!closed)status.text=message }
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
        busy=true;cancelled.set(false);status.text="Loading selected games…";refreshLabels()
        worker.execute {
            val result=runCatching {
                val library=LibraryRepository(activity)
                targets.flatMap { spec ->
                    if(closed || cancelled.get() || Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("Loading cancelled")
                    library.cached(spec)?.games.orEmpty().map { spec to Game(it.title,Uri.parse(it.uri),it.fileName) }
                }
            }
            activity.runOnUiThread {
                if(closed)return@runOnUiThread
                result.onSuccess { entries ->
                    gameSystems=entries.map { it.first };games=entries.map { it.second };selected.clear();selected.addAll(games.indices)
                    if(cancelled.get()) { busy=false;status.text="Cancelled";refreshLabels() }
                    else scrapeLoaded()
                }.onFailure { busy=false;status.text=it.message;refreshLabels() }
            }
        }
    }
    private fun scrapeLoaded() {
        val chosen=selected.sorted().filter { it in games.indices }
        if(chosen.isEmpty()) { busy=false;status.text="No scanned games in the selected systems.";refreshLabels();return }
        provider=activity.getSharedPreferences("scraper_credentials",0).getString("scrape.provider","ScreenScraper") ?: "ScreenScraper"
        val source=provider;resetProgress();busy=true;cancelled.set(false);status.text="Checking $source…";refreshLabels()
        worker.execute {
            val result=runCatching {
                val accountAllowed=ScraperClient(activity,source).accountConcurrency()
                val allowed=ScrapePolicy.parallelism(accountAllowed,ScrapePolicy.requestedConcurrency(activity))
                val executor=Executors.newFixedThreadPool(allowed);pool=executor
                activity.runOnUiThread { if(!closed) { accountSlots=accountAllowed;workerSlots=allowed;refreshLabels() } }
                val completion=ExecutorCompletionService<TaskResult>(executor)
                var next=0;var completed=0;var success=0;var missing=0;var skipped=0
                val errors=mutableListOf<String>()
                fun submit(slot:Int) {
                    val index=chosen[next++]
                    val game=games[index];val spec=gameSystems[index]
                    completion.submit(java.util.concurrent.Callable {
                        activity.runOnUiThread { if(!closed) { progressRows[slot]=GameProgress(spec,game);refreshLabels() } }
                        TaskResult(slot,spec,game,runCatching {
                            if(!ScrapePolicy.shouldScrape(activity,spec,game))return@runCatching "Skipped"
                            val match=ScraperClient(activity,source).search(game.title).firstOrNull() ?: return@runCatching "No match"
                            if(cancelled.get() || Thread.currentThread().isInterrupted)throw InterruptedException()
                            activity.runOnUiThread { if(!closed) { progressRows[slot]?.phase="Fetching media";refreshLabels() } }
                            GameScraper.scrape(activity,source,spec,game,match,parallelGames=true,onProgress={ done,total ->
                                activity.runOnUiThread { if(!closed) {
                                    progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { it.mediaDone=done;it.mediaTotal=total;it.phase="Media $done / $total" }
                                    refreshLabels()
                                } }
                            },onMediaPlan={ types -> activity.runOnUiThread { if(!closed) {
                                progressRows[slot]?.takeIf { it.game.uri==game.uri }?.requested=types;updateGamePopup()
                            } } },onMediaSaved={ type,path -> activity.runOnUiThread { if(!closed) {
                                progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { item ->
                                    item.saved[type]=path
                                    if(type!="videos" && type!="manuals")loadTileArtwork(item,path)
                                };updateGamePopup()
                            } } },onMediaStarted={ type -> activity.runOnUiThread { if(!closed) {
                                progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { it.currentMedia=type;it.phase="Downloading media" };refreshLabels()
                            } } })
                        })
                    })
                }
                try {
                    repeat(minOf(allowed,chosen.size)) { slot -> submit(slot) }
                    while(completed<chosen.size && !cancelled.get()) {
                        val future=completion.poll(250,TimeUnit.MILLISECONDS) ?: continue
                        val (slot,spec,game,outcome)=future.get();completed++
                        outcome.onSuccess { if(it=="Skipped")skipped++ else if(it=="No match")missing++ else if(it.startsWith("Saved 0") || it.contains("downloads failed"))errors.add("${game.title}: $it") else success++ }
                            .onFailure { errors.add("${game.title}: ${it.message}") }
                        val progress="$completed/${chosen.size} · $success scraped · $missing not found · $skipped skipped · ${errors.size} issues · $allowed concurrent"
                        val issue=outcome.isFailure || outcome.getOrNull()=="No match" || outcome.getOrNull()?.contains("downloads failed")==true || outcome.getOrNull()?.startsWith("Saved 0")==true
                        val totalScraped=success
                        val phase=when { outcome.isFailure -> "Failed";outcome.getOrNull()=="No match" -> "Not found";outcome.getOrNull()=="Skipped" -> "Skipped";issue -> "Media incomplete";else -> "Scraped" }
                        activity.runOnUiThread { if(!closed) {
                            status.text=progress;scrapedCount=totalScraped
                            progressRows[slot]?.takeIf { it.game.uri==game.uri }?.let { it.finished=true;it.phase=phase;it.issue=issue }
                            refreshLabels()
                        } }
                        if(next<chosen.size && !cancelled.get())submit(slot)
                    }
                } finally { executor.shutdownNow();pool=null }
                "$success games scraped · $missing not found · $skipped skipped · ${errors.size} issues.${if(cancelled.get()) " Cancelled; completed artwork kept." else ""}"+
                    errors.take(4).joinToString("\n",prefix=if(errors.isEmpty())"" else "\n")
            }
            activity.runOnUiThread {
                if(closed)return@runOnUiThread
                busy=false;progressRows.values.filter { !it.finished }.forEach { it.finished=true;it.phase="Stopped" };status.text=result.getOrElse { it.message ?: "Scrape failed" };refreshLabels();changed()
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
                    item.thumbnail=bitmap;gridAdapter.notifyDataSetChanged()
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
        if(!busy) { selected.clear();scrapedCount=0;progressRows.clear() }
        refreshLabels()
    }
    fun destroy() { closed=true;popup?.dismiss();cancelled.set(true);pool?.shutdownNow();worker.shutdownNow();previewWorker.shutdownNow() }
}
