package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Decodes row artwork off the UI thread; recycled rows retain their own identity. */
class GameLogoRows(private val context:Context) {
    private data class Artwork(val bitmap:Bitmap,val disc:DiscArtwork.Center?)
    private data class ArtworkKey(val location:String,val group:String,val mediaType:String,val fallbackMediaType:String?,val detectDisc:Boolean)
    private data class Binding(val key:ArtworkKey,val spinDiscs:Boolean)
    private val pendingTasks = LinkedBlockingDeque<Runnable>()
    private val worker = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, pendingTasks)
    private val handler=Handler(Looper.getMainLooper())
    private val cache=object:LruCache<ArtworkKey,Artwork>(24*1024*1024) {
        override fun sizeOf(key:ArtworkKey,value:Artwork)=value.bitmap.allocationByteCount
    }
    private val missing=mutableSetOf<ArtworkKey>()
    private class PendingArtwork {
        lateinit var task: Runnable
        val listeners = mutableMapOf<Any, (Artwork?) -> Unit>()
    }

    private val waiting = mutableMapOf<ArtworkKey, PendingArtwork>()
    private val requests = mutableMapOf<Any, ArtworkKey>()
    private val preloadOwner = Any()
    internal var generation=0
        private set
    private var preloadGeneration=0
    private var pendingPreload:Runnable?=null
    fun clear() {
        cancelPreload()
        generation++
        pendingTasks.clear()
        cache.evictAll()
        missing.clear()
        waiting.clear()
        requests.clear()
    }
    fun destroy() { clear();worker.shutdownNow() }
    internal fun cancelPreload() {
        preloadGeneration++
        pendingPreload?.let(handler::removeCallbacks)
        pendingPreload=null
        release(preloadOwner)
    }

    internal fun preload(
        system: SystemSpec, mediaType: String, fallback: String? = null,
        delayMillis: Long = 0, onReady: () -> Unit = {}, spinDiscs: Boolean = false, visibleGames: () -> List<Game>
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
                    val key=ArtworkKey(location,GameCustomArtwork.group(system,games.next()),mediaType,fallback,spinDiscs)
                    val cached=cache.get(key)
                    if(cached!=null) {
                        warmedBytes+=cached.bitmap.allocationByteCount
                        continue
                    }
                    if(key in missing)continue
                    // Queue only one speculative read so navigation can stop the rest immediately.
                    request(key, preloadOwner) {
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
        val key=ArtworkKey(UserDataStore.location(context),GameCustomArtwork.group(system,game),mediaType,fallbackMediaType,spinDiscs)
        if (!enabled || requests[row] != key) release(row)
        val previous=(row.tag as? Binding)?.key
        val binding=Binding(key,spinDiscs)
        val spinView=image as? GameTileArtworkView
        fun setArtwork(bitmap: Bitmap?) {
            if (scheduleArtwork != null && spinView != null) {
                spinView.updateWheelDrawable(bitmap?.let { BitmapDrawable(image.resources, it) })
            } else image.setImageBitmap(bitmap)
        }
        image.animate().cancel()
        text.animate().cancel()
        image.alpha=1f
        text.alpha=1f
        if(!enabled || !spinDiscs || row.tag!=binding)spinView?.showDisc(null)
        if(!enabled || previous?.group!=key.group || previous.location!=key.location) {
            setArtwork(null);image.visibility=View.INVISIBLE;text.visibility=View.VISIBLE
        }
        row.tag=if(enabled)binding else null
        spinView?.setSpinSelected(row.isSelected)
        row.contentDescription=game.title
        if(!enabled) {
            onArtworkPending?.invoke(false)
            return
        }
        fun display(artwork:Artwork?, loadedAsync:Boolean=false) {
            if(row.tag !== binding)return
            val bitmap=artwork?.bitmap
            val blend = blendFromTitle && loadedAsync && bitmap != null &&
                text.visibility == View.VISIBLE && row.isShown && row.isSelected
            setArtwork(bitmap);image.visibility=if(bitmap==null)View.INVISIBLE else View.VISIBLE
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
        request(key, row) { artwork ->
            if (row.tag === binding) {
                if (scheduleArtwork == null) display(artwork, loadedAsync = true)
                else scheduleArtwork { display(artwork, loadedAsync = true) }
            }
        }
    }

    internal fun recycle(row: View) {
        release(row)
        row.tag = null
    }

    internal fun prioritize(row: View) {
        val task = waiting[requests[row]]?.task ?: return
        if (pendingTasks.remove(task)) pendingTasks.addFirst(task)
    }

    private fun release(owner: Any) {
        val key = requests.remove(owner) ?: return
        val pending = waiting[key] ?: return
        pending.listeners.remove(owner)
        // A decode already in progress can still warm the cache. Drop queued reads with no row to update.
        if (pending.listeners.isEmpty() && worker.remove(pending.task)) waiting.remove(key)
    }

    private fun request(key: ArtworkKey, owner: Any, callback: (Artwork?) -> Unit) {
        requests[owner] = key
        waiting[key]?.let {
            it.listeners[owner] = callback
            return
        }
        val pending = PendingArtwork().apply { listeners[owner] = callback }
        pending.task = Runnable {
            val result = runCatching { load(key) }
            handler.post {
                if (waiting[key] !== pending) return@post
                waiting.remove(key)
                val artwork = result.getOrNull()
                if (artwork != null) cache.put(key, artwork) else if (result.isSuccess) missing.add(key)
                pending.listeners.keys.forEach(requests::remove)
                pending.listeners.values.forEach { it(artwork) }
            }
        }
        waiting[key] = pending
        worker.execute(pending.task)
    }

    private fun load(key:ArtworkKey):Artwork? {
        val hidden = GameCustomArtwork.removed(key.group, OrbitThemeArtwork.artworkScreen(context))
        fun visibleTypes(mediaType: String): List<String> {
            val role = when (mediaType) {
                "cover" -> "covers"
                "3dbox" -> "3dboxes"
                "physicalmedia" -> "physicalmedia"
                else -> "marquees"
            }
            return if (role in hidden) emptyList() else mediaTypes(mediaType)
        }
        val preferred = visibleTypes(key.mediaType)
        val types = preferred + key.fallbackMediaType?.let(::visibleTypes).orEmpty()
        val best=types.firstOrNull() ?: return null
        val remaining=types.drop(1)
        val alternatives=mutableListOf<Pair<String,String>>()
        fun find(type:String,files:List<Pair<String,String>>)=files.firstNotNullOfOrNull { (kind,path) ->
            if(kind!=type || path.isBlank())null
            else runCatching { decode(key.group,path,key.detectDisc && key.mediaType=="physicalmedia" && type in preferred) }.getOrNull()
        }
        for(bytes in UserDataStore.readScrapedRecords(context,key.group)) {
            val record=runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            if (!ScrapedMediaPlatform.matches(record, key.group.substringBefore('/'))) continue
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
