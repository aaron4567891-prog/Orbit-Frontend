package au.orbit.frontend

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Reads portable scraped media on a worker; scrolling never waits for storage or image decoding. */
class GameArtworkPanel(private val context: Activity, private val coverOnly: Boolean = false, private val theme: EsDeTheme.Model? = null, audioPriority: Int = 1, private val mediaMode: String? = null, private val videosEnabled: () -> Boolean = { true }) {
    var backgroundMediaChanged:((Map<String,Bitmap>,android.net.Uri?)->Unit)?=null
    var backgroundMediaPaused:(()->Unit)?=null
    private val variant=theme?.variant.orEmpty()
    private val metadataLayout=variant.contains("metadata")
    private val nativeMediaLayout=theme==null && mediaMode==null
    private val screenMediaLayout=theme==null && (mediaMode==null || mediaMode=="screen")
    private val artworkScreen get()=OrbitThemeArtwork.artworkScreen(context)
    private data class Artwork(val marquee: Bitmap?, val cover: Bitmap?, val screenshot: Bitmap?,val secondaryArt:Bitmap?=null,val description:String="",val details:String="",val videoUri:android.net.Uri?=null,val fanart:Bitmap?=null,val media:Map<String,Bitmap> = emptyMap())
    private val worker = Executors.newSingleThreadExecutor()
    private val previewWorker = Executors.newSingleThreadExecutor()
    private var adjacentLoad: Future<*>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var job: Future<*>? = null
    @Volatile private var generation = 0
    companion object {
        fun clearMediaCache()=cache.evictAll()
        // Reopening a system reuses its decoded art, including known missing-art results.
        private val cache = object : LruCache<String, Artwork>(16 * 1024 * 1024) {
            override fun sizeOf(key:String,value:Artwork)=maxOf(65536,
                (listOfNotNull(value.marquee,value.cover,value.screenshot,value.secondaryArt,value.fanart)+value.media.values).distinct().sumOf { it.allocationByteCount })
        }
    }
    private var currentGroup:String?=null
    private var earlyBackgroundGroup: String? = null
    private var currentCacheKey:String?=null
    private var currentArtwork:Artwork?=null
    private var previewPaused=true
    private var displayWhenReady=true
    var manualVideoActive=false
        private set
    private var fullScreenVideo:FullScreenGameVideo?=null
    private var foregroundVideoUri:android.net.Uri?=null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun image() = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; isFocusable = false }
    private val marquee = image()
    private val cover = image()
    private val video=LibraryVideoPreview(context, audioPriority = audioPriority)
    private val bottomScreen = OrbitThemeArtwork.screen(context) == "bottom"
    private val primaryArea=FrameLayout(context).apply {
        addView(cover,FrameLayout.LayoutParams(-1,-1))
        if(!screenMediaLayout)addView(video,FrameLayout.LayoutParams(-1,-1))
    }
    private val screenshot = image()
    private val secondaryArt=image()
    private val screenImages=ScreenMediaSettings.categories.filter { it.folder!="videos" }.associate { it.folder to image() }
    private val screenRows=if(screenMediaLayout)GameMediaGrid(context,screenImages,video) else null
    private fun layoutScreenMedia(screen:String=artworkScreen,videoBackground:Boolean=ScreenMediaSettings.background(screen)=="videos") {
        screenRows?.show(ScreenMediaSettings.shown(screen).filterNot { it=="videos" && videoBackground })
    }
    private fun metadataText(size:Float,colorKey:String)=TextView(context).apply {
        textSize=size;setTextColor(if(colorKey=="game-metadata" && theme!=null)EsDeTheme.parseColor(theme.variable("gameMetadataColor","ffffff")) else theme?.color("gamelist",colorKey,"color") ?: Color.WHITE)
        theme?.property("gamelist",if(colorKey=="game-metadata") "game-players" else colorKey,"fontSize")?.toFloatOrNull()?.let {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,context.resources.displayMetrics.heightPixels*it)
        }
        typeface=theme?.let { EsDeTheme.font(context,it.property("gamelist",colorKey,"fontPath","_inc/fonts/Gilroy-Regular.ttf")) }
        setPadding(dp(10),dp(8),dp(10),dp(8))
    }
    private val description=metadataText(15f,"game-description").apply { maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END }
    private val details=metadataText(12f,"game-metadata").apply { maxLines=5 }
    private val empty = TextView(context).apply {
        visibility=View.INVISIBLE; text = "No artwork"; textSize = 14f; setTextColor(Color.rgb(148,161,180)); gravity = Gravity.CENTER
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), 0, dp(8))
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if(theme!=null && mediaMode==null) {
            addView(marquee,LinearLayout.LayoutParams(-1,dp(80)).apply {
                setMargins(dp(16),dp(4),dp(16),dp(8))
            })
        }
        if(screenMediaLayout) {
            setPadding(0,0,0,0)
            addView(requireNotNull(screenRows),LinearLayout.LayoutParams(-1,-1))
            layoutScreenMedia()
        } else if(metadataLayout) {
            setPadding(0,0,0,0)
            addView(primaryArea,LinearLayout.LayoutParams(-1,0,2f))
            val lower=LinearLayout(context).apply {
                setBackgroundColor(theme?.color("gamelist","game-description-background","color","00000055") ?: Color.TRANSPARENT)
                addView(secondaryArt,LinearLayout.LayoutParams(0,-1,0.32f).apply { setMargins(dp(10),dp(10),dp(10),dp(10)) })
                addView(LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL
                    addView(description,LinearLayout.LayoutParams(-1,0,1f))
                    addView(details,LinearLayout.LayoutParams(-1,-2))
                },LinearLayout.LayoutParams(0,-1,0.68f))
            }
            addView(lower,LinearLayout.LayoutParams(-1,0,1f))
        } else {
            if(!coverOnly && theme==null)addView(marquee, LinearLayout.LayoutParams(-1, dp(60)))
            addView(primaryArea, LinearLayout.LayoutParams(-1, 0, 1f))
            if(!coverOnly && !nativeMediaLayout)addView(screenshot, LinearLayout.LayoutParams(-1, dp(130)).apply { topMargin = dp(10) })
        }
        if(mediaMode!=null)setPadding(0,0,0,0)
        primaryArea.addView(empty,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
    }
    fun show(system: SystemSpec, game: Game?, refresh: Boolean = false) =
        requestArtwork(system,game,refresh,showNow=true)

    internal fun preload(system:SystemSpec,game:Game) =
        requestArtwork(system,game,refresh=false,showNow=false)

    internal fun showCachedFanArt(system: SystemSpec, game: Game) {
        val screen = artworkScreen
        if (!screenMediaLayout || manualVideoActive || ScreenMediaSettings.background(screen) != "fanart") return
        val group = GameCustomArtwork.group(system, game)
        val paths = GameCustomArtwork.paths(group, screen)
        val artwork = cache.get(cacheKey(ScreenMediaSettings.signature(screen), group, paths)) ?: return
        if ("fanart" in artwork.media || GameCustomArtwork.HERO in artwork.media) {
            earlyBackgroundGroup = group
            backgroundMediaChanged?.invoke(artwork.media, null)
        }
    }

    internal fun warmAdjacentFanArt(system: SystemSpec, games: List<Game>) {
        adjacentLoad?.cancel(true)
        if (!screenMediaLayout || games.isEmpty()) return
        val screen = artworkScreen
        if (ScreenMediaSettings.background(screen) != "fanart" && !ScreenMediaSettings.visible(screen, "fanart")) return
        val ticket = generation
        val options = ScreenMediaSettings.signature(screen)
        val keys = games.map { game ->
            val group = GameCustomArtwork.group(system, game)
            val paths = GameCustomArtwork.paths(group, screen)
            Triple(group, paths, cacheKey(options, group, paths))
        }
        adjacentLoad = previewWorker.submit {
            for ((group, paths, key) in keys) {
                if (ticket != generation || Thread.currentThread().isInterrupted) break
                if (cache.get(key) != null) continue
                val artwork = runCatching { load(group, ticket, screen, paths) }.getOrNull() ?: continue
                if (ticket != generation || Thread.currentThread().isInterrupted) break
                artwork.media.values.forEach(Bitmap::prepareToDraw)
                handler.post { if (ticket == generation && cache.get(key) == null) cache.put(key, artwork) }
            }
        }
    }

    private fun cacheKey(options: String, group: String, customPaths: Map<String, String>) =
        "${UserDataStore.location(context)}/$variant/$coverOnly/$mediaMode/$options/$group/$customPaths"

    private fun requestArtwork(system:SystemSpec,game:Game?,refresh:Boolean,showNow:Boolean) {
        if (showNow) earlyBackgroundGroup = null
        displayWhenReady=showNow
        if(game==null) {
            generation++;cancelLoad();stopManualVideo();currentGroup=null;currentCacheKey=null
            display(Artwork(null,null,null));return
        }
        val group=GameCustomArtwork.group(system,game)
        val first=currentGroup==null
        val changed=currentGroup!=group
        val screen=artworkScreen
        if (screenMediaLayout && showNow) layoutScreenMedia(screen)
        val options=if(screenMediaLayout)ScreenMediaSettings.signature(screen) else ""
        val customPaths=if(screenMediaLayout)GameCustomArtwork.paths(group,screen) else emptyMap()
        val key=cacheKey(options, group, customPaths)
        if(!changed && currentCacheKey==key && !refresh) {
            if(pending!=null || job!=null)return
            currentArtwork?.let { if(showNow && previewPaused)display(it);return }
        }
        val ticket=++generation
        cancelLoad()
        currentCacheKey=key
        currentGroup=group
        val fanArtBackground = screenMediaLayout && ScreenMediaSettings.background(screen) == "fanart"
        if(changed) {
            if (!bottomScreen || !screenMediaLayout || manualVideoActive) stopManualVideo()
            currentArtwork=null
        }
        if(!refresh)cache.get(key)?.let { acceptArtwork(it);return }
        if(changed) {
            // Reserve the final layout while loading. Never insert a temporary empty-art row.
            for(image in listOf(marquee,cover,screenshot,secondaryArt)) {
                image.setImageDrawable(null);image.visibility=View.INVISIBLE
            }
            description.text="";details.text="";empty.visibility=View.INVISIBLE
        }
        pending=Runnable {
            pending=null
            job=worker.submit {
                val artwork=runCatching {
                    load(group, ticket, screen, customPaths) { media ->
                        media.values.forEach(Bitmap::prepareToDraw)
                        handler.post {
                            if (ticket == generation && displayWhenReady && earlyBackgroundGroup == null && artworkScreen == screen &&
                                ScreenMediaSettings.background(screen) == "fanart" && !manualVideoActive) {
                                backgroundMediaChanged?.invoke(media, null)
                            }
                        }
                    }
                }.getOrDefault(Artwork(null,null,null))
                if(ticket==generation) {
                    artwork.media.values.forEach(Bitmap::prepareToDraw)
                    handler.post {
                        if(ticket==generation) { job=null;cache.put(key,artwork);acceptArtwork(artwork) }
                    }
                }
            }
        }.also { handler.postDelayed(it,if(first || !showNow || fanArtBackground || screenMediaLayout && ScreenMediaSettings.visible(screen, "fanart"))0 else 70) }
    }

    private fun acceptArtwork(artwork:Artwork) {
        if(displayWhenReady)display(artwork) else currentArtwork=artwork
    }
    private fun cancelLoad() {
        pending?.let(handler::removeCallbacks);pending=null
        job?.cancel(false);job=null
    }
    fun toggleVideoPreview(system:SystemSpec,game:Game) {
        show(system,game)
        if(manualVideoActive) { finishVideoPreview();return }
        manualVideoActive=true
        currentArtwork?.let(::display)
    }
    fun finishVideoPreview() {
        if(!manualVideoActive)return
        stopManualVideo()
        currentArtwork?.let(::display)
    }
    private fun stopManualVideo() {
        manualVideoActive=false
        fullScreenVideo?.close();fullScreenVideo=null
        video.stop()
        foregroundVideoUri=null
        previewPaused=true
    }
    private fun load(group: String, ticket: Int, screen:String, customPaths:Map<String,String>,
        showBackground: ((Map<String, Bitmap>) -> Unit)? = null): Artwork {
        val files = mutableListOf<Pair<String,String>>()
        var metadata:JSONObject?=null
        for (recordName in UserDataStore.scrapedRecordNames(context,group)) {
            if (ticket != generation) return Artwork(null,null,null)
            val bytes = UserDataStore.readScraped(context,group,recordName) ?: continue
            val record = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            if(metadata==null)metadata=record.optJSONObject("metadata")
            ScraperClient.objects(record.opt("files")).forEach { file -> files.add(file.optString("type") to file.optString("file")) }
        }
        fun bitmap(types: List<String>): Bitmap? {
            for (type in types) for ((kind,path) in files) {
                if (ticket != generation) return null
                if (kind != type || path.isBlank()) continue
                val decoded = runCatching {
                    val bytes = UserDataStore.readScraped(context,group,path) ?: return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    var sample = 1
                    val maxWidth=if(screenMediaLayout)1920 else 1000
                    val maxHeight=if(screenMediaLayout)1200 else 1000
                    while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) sample *= 2
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
                if (decoded != null) return decoded
            }
            return null
        }
        val videoUri by lazy {
            files.asSequence().filter { it.first in listOf("video-normalized","video","videos") }
                .mapNotNull { (_,path) -> runCatching { UserDataStore.scrapedUri(context,group,path) }.getOrNull() }.firstOrNull()
        }
        if(screenMediaLayout) {
            val background = ScreenMediaSettings.background(screen)
            val needed=ScreenMediaSettings.shown(screen).toSet()+background
            fun customImage(role:String)=customPaths[role]?.let { path ->
                if(ticket!=generation)null else runCatching { OrbitThemeArtwork.load(context,path) }.getOrNull()
            }
            fun manual():Bitmap? {
                for((type,path) in files) {
                    if(type !in ScreenMediaSettings.categories.first { it.folder=="manuals" }.types)continue
                    if(ticket!=generation)return null
                    val page=runCatching {
                        val uri=UserDataStore.scrapedUri(context,group,path) ?: return@runCatching null
                        context.contentResolver.openFileDescriptor(uri,"r")?.use { descriptor ->
                            android.graphics.pdf.PdfRenderer(descriptor).use rendererUse@ { renderer ->
                                if(renderer.pageCount==0)return@rendererUse null
                                renderer.openPage(0).use { first ->
                                    val scale=minOf(1000f/first.width,1200f/first.height)
                                    Bitmap.createBitmap((first.width*scale).toInt().coerceAtLeast(1),(first.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(Color.WHITE);first.render(it,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                        }
                    }.getOrNull()
                    if(page!=null)return page
                }
                return null
            }
            val hero=customImage(GameCustomArtwork.HERO)
            val fanArt = if (background == "fanart") {
                val category = ScreenMediaSettings.categories.first { it.folder == "fanart" }
                customImage(category.folder) ?: bitmap(category.types)
            } else null
            if (background == "fanart" && showBackground != null) {
                if (hero != null) showBackground(mapOf(GameCustomArtwork.HERO to hero))
                else if (fanArt != null) showBackground(mapOf("fanart" to fanArt))
            }
            val images=ScreenMediaSettings.categories.filter { it.folder in needed && it.folder!="videos" }.mapNotNull { category ->
                val image = if (category.folder == "fanart" && background == "fanart") fanArt
                    else customImage(category.folder) ?: bitmap(category.types) ?: if(category.folder=="manuals")manual() else null
                image?.let { category.folder to it }
            }.toMap()
            val media=if(hero==null)images else images+(GameCustomArtwork.HERO to hero)
            return Artwork(null,null,null,videoUri=videoUri,media=media)
        }
        if(mediaMode!=null) {
            val image=when(mediaMode) {
                "videos" -> null
                "fanart" -> bitmap(listOf("fanart","heroes"))
                else -> bitmap(listOf("ss-hd","ss","screenshot","screenshots"))
            }
            return Artwork(null,image,null,videoUri=if(mediaMode=="videos")videoUri else null)
        }
        val logo = bitmap(listOf("wheel-hd","wheel","logo","logos","marquee"))
        val primary = bitmap(listOf("box-2D","cover","boxart","grids"))
        val screenshot = bitmap(listOf("ss-hd","ss","screenshot","screenshots"))
        val fanart = bitmap(listOf("fanart","heroes"))
        val secondary = screenshot ?: fanart
        val physical=if(variant.contains("physicalmedia"))bitmap(listOf("support-2D","physicalmedia")) else null
        val selected=when {
            metadataLayout || variant.contains("screenshot") -> secondary ?: primary
            variant.endsWith("titlescreen") -> bitmap(listOf("sstitle","titlescreen")) ?: secondary ?: primary
            variant.endsWith("physicalmedia") -> physical ?: secondary ?: primary
            variant.endsWith("miximage") -> bitmap(listOf("miximage","mixrbv2","mixrbv1")) ?: composite(secondary,primary,logo)
            else -> if(nativeMediaLayout)primary else primary ?: secondary
        }
        val supplement=when {
            variant.endsWith("marquee") -> logo
            variant.endsWith("physicalmedia") -> physical
            else -> primary
        }
        val info=metadata ?: JSONObject()
        val synopsis=ScraperClient.objects(info.opt("synopsis"))
        val text=synopsis.firstOrNull { it.optString("langue")==ScrapePolicy.language(context) }?.optString("text")
            ?: synopsis.firstOrNull()?.optString("text") ?: info.optString("description",info.optString("overview"))
        fun field(vararg keys:String):String {
            for(key in keys) when(val value=info.opt(key)) {
                is String -> if(value.isNotBlank())return value
                is Number -> return value.toString()
                is JSONObject -> value.optString("text").takeIf(String::isNotBlank)?.let { return it }
            }
            return ""
        }
        val date=field("release_year","release_date").ifBlank { ScraperClient.objects(info.opt("dates")).firstOrNull()?.optString("text").orEmpty() }
        val detailText=listOf("Released" to date,"Players" to field("players","joueurs"),"Publisher" to field("publisher","editeur"),"Rating" to field("rating","note"))
            .filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }
        return Artwork(logo, selected, if(nativeMediaLayout)screenshot else if(coverOnly || primary==null)null else secondary,
            supplement,text.orEmpty(),detailText,videoUri,if(nativeMediaLayout)fanart else null)
    }
    private fun composite(background:Bitmap?,box:Bitmap?,logo:Bitmap?):Bitmap? {
        if(background==null && box==null && logo==null)return null
        val result=Bitmap.createBitmap(900,700,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun draw(bitmap:Bitmap?,x:Float,y:Float,w:Float,h:Float) {
            if(bitmap==null)return
            val scale=minOf(w/bitmap.width,h/bitmap.height)
            val width=bitmap.width*scale;val height=bitmap.height*scale
            canvas.drawBitmap(bitmap,null,RectF(x+(w-width)/2,y+(h-height)/2,x+(w+width)/2,y+(h+height)/2),paint)
        }
        draw(background,100f,50f,780f,530f)
        draw(box,0f,270f,300f,420f)
        draw(logo,320f,540f,550f,150f)
        return result
    }
    private fun display(artwork: Artwork) {
        if(manualVideoActive) {
            val uri=artwork.videoUri
            if(uri!=null) {
                currentArtwork=artwork
                if(fullScreenVideo?.uri!=uri) {
                    fullScreenVideo?.close()
                    video.stop()
                    backgroundMediaPaused?.invoke()
                    fullScreenVideo=FullScreenGameVideo(context,uri,::finishVideoPreview) {
                        finishVideoPreview()
                        android.widget.Toast.makeText(context,"Could not play the scraped video.",android.widget.Toast.LENGTH_SHORT).show()
                    }.also { it.open() }
                }
                return
            }
            stopManualVideo()
            android.widget.Toast.makeText(context,"No video has been scraped for this game.",android.widget.Toast.LENGTH_SHORT).show()
        }
        if (earlyBackgroundGroup == null || earlyBackgroundGroup == currentGroup) {
            backgroundMediaChanged?.invoke(artwork.media,artwork.videoUri)
        }
        if(screenMediaLayout) {
            val screen=artworkScreen
            val videoBackground=GameCustomArtwork.HERO !in artwork.media && ScreenMediaSettings.background(screen)=="videos"
            layoutScreenMedia(screen,videoBackground)
            screenImages.forEach { (type,image) ->
                val bitmap=artwork.media[type]
                if (!ScreenMediaSettings.visible(screen, type)) {
                    image.animate().cancel()
                    image.alpha = 1f
                    image.setImageBitmap(null)
                } else if (bitmap == null) {
                    if (image.drawable != null && image.visibility == View.VISIBLE) {
                        image.animate().alpha(0f).setDuration(140L).withEndAction {
                            image.setImageBitmap(null)
                            image.visibility = View.INVISIBLE
                            image.alpha = 1f
                        }.start()
                    } else image.visibility = View.INVISIBLE
                } else {
                    image.animate().cancel()
                    if ((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap !== bitmap) image.setImageBitmap(bitmap)
                    image.alpha = 1f
                    image.visibility = View.VISIBLE
                }
            }
            val showVideo=ScreenMediaSettings.visible(screen,"videos") && videosEnabled() && !videoBackground
            if(!showVideo) { video.stop();foregroundVideoUri=null }
            else if(previewPaused || foregroundVideoUri!=artwork.videoUri) {
                if (artwork.videoUri == null) video.fadeOut()
                else video.show(artwork.videoUri,if(bottomScreen || mediaMode=="screen")0 else 1000)
                foregroundVideoUri=artwork.videoUri
            }
            previewPaused=false;currentArtwork=artwork
            return
        }
        fun bind(image: ImageView, bitmap: Bitmap?, description: String) {
            image.setImageBitmap(bitmap);image.visibility=if(bitmap==null) View.INVISIBLE else View.VISIBLE;image.contentDescription=description
        }
        val showLogo=mediaMode==null && (theme==null && !coverOnly || theme!=null && UserDataStore.int(context,"colorfulMarquees",1)==1)
        bind(marquee,if(showLogo)artwork.marquee else null,"Game marquee")
        if(theme!=null && !showLogo)marquee.visibility=View.GONE
        bind(cover,artwork.cover,"Game artwork")
        primaryArea.visibility=View.VISIBLE
        val delay=if(mediaMode=="videos")0L else ((theme?.property("gamelist","game-video","delay")?.toFloatOrNull() ?: 1f)*1000).toLong().coerceIn(0,10000)
        if(!videosEnabled())video.stop()
        else if(previewPaused || currentArtwork?.videoUri!=artwork.videoUri)video.show(artwork.videoUri,delay)
        previewPaused=false;currentArtwork=artwork
        bind(screenshot,if(coverOnly || nativeMediaLayout)null else artwork.screenshot,"Game screenshot")
        bind(secondaryArt,artwork.secondaryArt,"Game supplementary artwork")
        description.text=artwork.description.ifBlank { "No description available" };details.text=artwork.details
        empty.visibility=if(artwork.marquee==null && artwork.cover==null && artwork.screenshot==null && artwork.fanart==null && artwork.videoUri==null) View.VISIBLE else View.INVISIBLE
    }
    internal fun setWheelScrolling(scrolling: Boolean) = video.setWheelScrolling(scrolling)

    fun pause() { displayWhenReady=false;earlyBackgroundGroup=null;adjacentLoad?.cancel(true);stopManualVideo();backgroundMediaPaused?.invoke() }
    fun destroy() { pause();generation++;cancelLoad();worker.shutdownNow();previewWorker.shutdownNow() }
}
