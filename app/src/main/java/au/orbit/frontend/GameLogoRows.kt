package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Decodes row artwork off the UI thread; recycled rows retain their own identity. */
class GameLogoRows(private val context:Context) {
    private data class Artwork(val bitmap:Bitmap,val disc:DiscArtwork.Center?)
    private data class ArtworkKey(val location:String,val group:String,val mediaType:String,val fallbackMediaType:String?)
    private data class Binding(val key:ArtworkKey,val spinDiscs:Boolean)
    private val worker=Executors.newFixedThreadPool(2)
    private val handler=Handler(Looper.getMainLooper())
    private val cache=object:LruCache<ArtworkKey,Artwork>(24*1024*1024) {
        override fun sizeOf(key:ArtworkKey,value:Artwork)=value.bitmap.allocationByteCount
    }
    private val missing=mutableSetOf<ArtworkKey>()
    private val waiting=mutableMapOf<ArtworkKey,MutableList<(Artwork?)->Unit>>()
    internal var generation=0
        private set
    private var preloadGeneration=0
    private var pendingPreload:Runnable?=null
    fun clear() { cancelPreload();generation++;cache.evictAll();missing.clear();waiting.clear() }
    fun destroy() { clear();worker.shutdownNow() }
    internal fun cancelPreload() {
        preloadGeneration++
        pendingPreload?.let(handler::removeCallbacks)
        pendingPreload=null
    }

    internal fun preload(
        system: SystemSpec, mediaType: String, fallback: String? = null,
        delayMillis: Long = 0, onReady: () -> Unit = {}, visibleGames: () -> List<Game>
    ) {
        cancelPreload()
        val ticket=preloadGeneration
        pendingPreload=Runnable {
            pendingPreload=null
            val games=visibleGames().iterator()
            val location=UserDataStore.location(context)
            var warmedBytes=0
            fun next() {
                while(ticket==preloadGeneration && games.hasNext() && warmedBytes<20*1024*1024) {
                    val key=ArtworkKey(location,GameCustomArtwork.group(system,games.next()),mediaType,fallback)
                    val cached=cache.get(key)
                    if(cached!=null) {
                        warmedBytes+=cached.bitmap.allocationByteCount
                        continue
                    }
                    if(key in missing)continue
                    // Queue only one speculative read so navigation can stop the rest immediately.
                    request(key) {
                        warmedBytes+=it?.bitmap?.allocationByteCount ?: 0
                        next()
                    }
                    return
                }
                if(ticket==preloadGeneration)onReady()
            }
            next()
        }.also { handler.postDelayed(it,delayMillis) }
    }

    fun bind(
        row:FrameLayout,text:TextView,system:SystemSpec,game:Game,enabled:Boolean,
        mediaType:String="marquee",fallbackMediaType:String?=null,spinDiscs:Boolean=false,
        showTitleWhileLoading:Boolean=true,onArtworkPending:((Boolean)->Unit)?=null,
        blendFromTitle:Boolean=false, scheduleArtwork: ((() -> Unit) -> Unit)? = null
    ) {
        val image=(row.getChildAt(1) as? ImageView) ?: GameTileArtworkView(context).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER;isFocusable=false
            setPadding(16,6,16,6)
            row.addView(this,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        }
        val key=ArtworkKey(UserDataStore.location(context),GameCustomArtwork.group(system,game),mediaType,fallbackMediaType)
        val previous=(row.tag as? Binding)?.key
        val binding=Binding(key,spinDiscs)
        val spinView=image as? GameTileArtworkView
        image.animate().cancel()
        text.animate().cancel()
        image.alpha=1f
        text.alpha=1f
        if(!enabled || !spinDiscs || row.tag!=binding)spinView?.showDisc(null)
        if(!enabled || previous?.group!=key.group || previous.location!=key.location) {
            image.setImageDrawable(null);image.visibility=View.INVISIBLE;text.visibility=View.VISIBLE
        }
        row.tag=if(enabled)binding else null
        image.isSelected=row.isSelected
        row.contentDescription=game.title
        if(!enabled) {
            onArtworkPending?.invoke(false)
            return
        }
        fun display(artwork:Artwork?, loadedAsync:Boolean=false) {
            if(row.tag!=binding)return
            val bitmap=artwork?.bitmap
            val blend = blendFromTitle && loadedAsync && bitmap != null &&
                text.visibility == View.VISIBLE && row.isShown
            image.setImageBitmap(bitmap);image.visibility=if(bitmap==null)View.INVISIBLE else View.VISIBLE
            if (blend) {
                image.alpha=0f
                image.animate().alpha(1f).setDuration(140L).start()
                text.animate().alpha(0f).setDuration(140L).withEndAction {
                    if (row.tag === binding) text.visibility=View.INVISIBLE
                }.start()
            } else text.visibility=if(bitmap==null)View.VISIBLE else View.INVISIBLE
            spinView?.showDisc(artwork?.disc?.takeIf { spinDiscs })
            onArtworkPending?.invoke(false)
        }
        cache.get(key)?.let { display(it);return }
        if(key in missing) { display(null);return }
        val awaitingArtwork=image.drawable==null
        if(!showTitleWhileLoading && awaitingArtwork)text.visibility=View.INVISIBLE
        onArtworkPending?.invoke(awaitingArtwork)
        request(key) { artwork ->
            if (row.tag === binding) {
                if (scheduleArtwork == null) display(artwork, loadedAsync = true)
                else scheduleArtwork { display(artwork, loadedAsync = true) }
            }
        }
    }

    private fun request(key:ArtworkKey,callback:(Artwork?)->Unit) {
        waiting[key]?.let { it.add(callback);return }
        waiting[key]=mutableListOf(callback)
        val ticket=generation
        worker.execute {
            val artwork=runCatching { load(key) }.getOrNull()
            handler.post {
                if(ticket==generation) {
                    if(artwork==null)missing.add(key) else cache.put(key,artwork)
                    waiting.remove(key)?.forEach { it(artwork) }
                }
            }
        }
    }

    private fun load(key:ArtworkKey):Artwork? {
        val preferred=mediaTypes(key.mediaType)
        val types=preferred+key.fallbackMediaType?.let(::mediaTypes).orEmpty()
        val best=types.first()
        val remaining=types.drop(1)
        val alternatives=mutableListOf<Pair<String,String>>()
        fun find(type:String,files:List<Pair<String,String>>)=files.firstNotNullOfOrNull { (kind,path) ->
            if(kind!=type || path.isBlank())null
            else runCatching { decode(key.group,path,key.mediaType=="physicalmedia" && type in preferred) }.getOrNull()
        }
        for(bytes in UserDataStore.readScrapedRecords(context,key.group)) {
            val record=runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            val files=ScraperClient.objects(record.opt("files")).map { it.optString("type") to it.optString("file") }
            find(best,files)?.let { return it }
            files.filterTo(alternatives) { it.first in remaining }
        }
        return remaining.firstNotNullOfOrNull { find(it,alternatives) }
    }

    private fun decode(group:String,path:String,physicalMedia:Boolean):Artwork? {
        val bytes=UserDataStore.readScraped(context,group,path) ?: return null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        var sample=1
        while(bounds.outWidth/sample>700 || bounds.outHeight/sample>700)sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        val disc=if(physicalMedia && bitmap.hasAlpha()) {
            DiscArtwork.center(bitmap.width,bitmap.height) { x,y -> bitmap.getPixel(x,y) ushr 24 }
        } else null
        bitmap.prepareToDraw()
        return Artwork(bitmap,disc)
    }

    private fun mediaTypes(mediaType:String)=when(mediaType) {
        "cover" -> listOf("box-2D","cover","boxart","grids")
        "3dbox" -> ScraperMediaTypes.boxArt3D.types
        "physicalmedia" -> listOf("support-2D","physicalmedia")
        else -> listOf("wheel-hd","wheel","logo","logos","marquee")
    }
}
