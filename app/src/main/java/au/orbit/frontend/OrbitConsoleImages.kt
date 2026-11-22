package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Standalone console images; decoding never runs in display or input callbacks. */
object OrbitConsoleImages {
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private val missing=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cache=object:LruCache<String,Bitmap>(32*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    fun cached(system:SystemSpec)=cache.get(system.id) ?: SystemArtworkPreviews.cached(SystemArtworkPreviews.consoleKey(system))
    fun load(context:Context,system:SystemSpec):Bitmap? {
        cache.get(system.id)?.let { return it }
        if(system.id in missing)return null
        val result=runCatching {
            val bytes=context.assets.open("system-artwork/${system.id}.webp").use { it.readBytes() }
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            var sample=1;while(bounds.outWidth/sample>900 || bounds.outHeight/sample>900)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })?.let(SystemTileArtwork::trim)
        }.getOrNull()
        if(result!=null)cache.put(system.id,result) else missing.add(system.id)
        return result
    }
    fun request(context:Context,system:SystemSpec,ready:()->Unit={}) {
        if(cache.get(system.id)!=null || system.id in missing)return
        val app=context.applicationContext
        worker.execute { load(app,system);handler.post(ready) }
    }
}
