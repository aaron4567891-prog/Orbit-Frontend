package au.orbit.frontend

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache

/** User images are copied and decoded on workers; drawing only reads prepared bitmaps. */
object OrbitThemeArtwork {
    internal const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    private val cache=object:LruCache<String,Bitmap>(48*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    @Volatile private var activeImages=emptyMap<String,Bitmap>()
    fun screen(context:Context)=if((context as? Activity)?.windowManager?.defaultDisplay?.displayId==android.view.Display.DEFAULT_DISPLAY)"top" else "bottom"
    fun artworkScreen(context:Context,screen:String=screen(context),theme:OrbitThemes.Theme=OrbitThemes.current()) = when {
        theme.libraryScreen!="bottom" -> screen
        screen=="top" -> "bottom"
        else -> "top"
    }
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val loading=mutableMapOf<String,MutableList<()->Unit>>()
    fun image(context:Context,theme:OrbitThemes.Theme,role:String,system:SystemSpec?=null,target:String=artworkScreen(context,theme=theme)):Bitmap? {
        val path=if(role=="hero")theme.heroImage(target,system?.id) else theme.image(role,target)
        return path?.let { cache.get(it) ?: activeImages[it] }
            ?: if(role=="hero")theme.image(role,target)?.let { cache.get(it) ?: activeImages[it] } else null
    }
    fun systemImage(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String=artworkScreen(context,theme=theme)):Bitmap? =
        theme.systemImages[system.id]?.get(target)?.let { cache.get(it) }
    internal fun cachedImage(path:String):Bitmap? = cache.get(path)
    @Synchronized internal fun load(context:Context,path:String):Bitmap? {
        cache.get(path)?.let { return it }
        val bytes=UserDataStore.readNativeArtwork(context,path) ?: return null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        return decodeImage(bytes,bounds)?.also { cache.put(path,it) }
    }
    internal fun request(context:Context,path:String?,ready:()->Unit) {
        if(path==null)return
        if(cache.get(path)!=null)return
        synchronized(loading) {
            loading[path]?.let { it.add(ready);return }
            loading[path]=mutableListOf(ready)
        }
        worker.execute {
            runCatching { load(context.applicationContext,path) }
            val callbacks=synchronized(loading) { loading.remove(path).orEmpty().toList() }
            handler.post { callbacks.forEach { it() } }
        }
    }
    fun requestHero(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String=artworkScreen(context,theme=theme),ready:()->Unit) =
        request(context,theme.systemHeroes[system.id]?.get(target),ready)
    fun requestSystemImage(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String=artworkScreen(context,theme=theme),ready:()->Unit) =
        request(context,theme.systemImages[system.id]?.get(target),ready)
    fun requestSystemArtwork(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,ready:()->Unit) {
        requestHero(context,theme,system,ready=ready)
        requestSystemImage(context,theme,system,ready=ready)
    }
    @Synchronized fun prepare(context:Context,theme:OrbitThemes.Theme) {
        theme.artwork.map { it.file }.distinct().filter { cache.get(it)==null }.forEach { path ->
            runCatching { load(context,path) }
        }
        if(theme.id==OrbitThemes.current().id)activeImages=theme.artwork.map { it.file }.distinct().mapNotNull { path ->
            (cache.get(path) ?: activeImages[path])?.let { path to it }
        }.toMap()
    }
    fun assign(context:Context,theme:OrbitThemes.Theme,role:String,screens:Set<String>,uri:Uri?) =
        assignPath(context,theme,role,screens,null,uri?.let { importImage(context,it) })
    fun assignSystemHero(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,screens:Set<String>,uri:Uri?):OrbitThemes.Theme {
        return assignPath(context,theme,"hero",screens,system,uri?.let { importImage(context,it) })
    }
    fun assignSystemImage(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,screens:Set<String>,uri:Uri?):OrbitThemes.Theme {
        return assignPath(context,theme,"system",screens,system,uri?.let { importImage(context,it) })
    }
    internal fun assignDownloaded(context:Context,theme:OrbitThemes.Theme,role:String,screens:Set<String>,system:SystemSpec?,bytes:ByteArray) =
        assignPath(context,theme,role,screens,system,storeImage(context,bytes))

    private fun assignPath(context:Context,theme:OrbitThemes.Theme,role:String,screens:Set<String>,system:SystemSpec?,path:String?):OrbitThemes.Theme {
        val latest=OrbitThemes.list().firstOrNull { it.id==theme.id } ?: theme
        val updated=when {
            system==null -> latest.withArtwork(role,screens,path)
            role=="hero" -> latest.withSystemHero(system.id,screens,path)
            else -> latest.withSystemImage(system.id,screens,path)
        }
        UserDataStore.saveNativeTheme(context,updated.id,updated.json())
        OrbitThemes.refresh(context)
        if(system==null)prepare(context,updated) else {
            val images=if(role=="hero")updated.systemHeroes else updated.systemImages
            images[system.id].orEmpty().values.distinct().forEach { runCatching { load(context,it) } }
        }
        return updated
    }
    internal fun importImage(context:Context,uri:Uri):String {
        val output=java.io.ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer=ByteArray(32768)
            while(true) { val size=input.read(buffer);if(size<0)break
                require(output.size()+size<=MAX_IMAGE_BYTES) { "Choose an image smaller than 32 MB" };output.write(buffer,0,size)
            }
        } ?: error("Could not read selected image")
        return storeImage(context,output.toByteArray())
    }
    internal fun storeImage(context:Context,bytes:ByteArray):String {
        require(bytes.size<=MAX_IMAGE_BYTES) { "Choose an image smaller than 32 MB" }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Choose a PNG, JPEG or WebP image" }
        val extension=when(bounds.outMimeType) { "image/png" -> "png";"image/jpeg" -> "jpg";"image/webp" -> "webp";else -> error("Choose a PNG, JPEG or WebP image") }
        val bitmap=decodeImage(bytes,bounds) ?: error("Could not open this image")
        return "artwork/${java.util.UUID.randomUUID()}.$extension".also { file ->
            UserDataStore.saveNativeArtwork(context,file,bytes)
            cache.put(file,bitmap)
        }
    }
    private fun decodeImage(bytes:ByteArray,bounds:BitmapFactory.Options):Bitmap? {
        var sample=1
        while(bounds.outWidth/sample>1920 || bounds.outHeight/sample>1200)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
    }
    internal fun drawImage(canvas:Canvas,bitmap:Bitmap,area:RectF,cover:Boolean=false) {
        val scale=if(cover)maxOf(area.width()/bitmap.width,area.height()/bitmap.height) else minOf(area.width()/bitmap.width,area.height()/bitmap.height)
        val w=bitmap.width*scale;val h=bitmap.height*scale
        val checkpoint=canvas.save();canvas.clipRect(area)
        canvas.drawBitmap(bitmap,null,RectF(area.centerX()-w/2,area.centerY()-h/2,area.centerX()+w/2,area.centerY()+h/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restoreToCount(checkpoint)
    }
    fun hero(context:Context,canvas:Canvas,area:RectF,theme:OrbitThemes.Theme,system:SystemSpec?=null):Boolean {
        val bitmap=image(context,theme,"hero",system) ?: return false
        drawImage(canvas,bitmap,area);return true
    }
    internal fun systemHeroBackground(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String,fallback:Drawable?=null):Drawable=object:Drawable() {
        override fun draw(canvas:Canvas) {
            val hero=image(context,theme,"hero",system,target)
            canvas.drawColor(theme.background)
            if(hero!=null)drawImage(canvas,hero,RectF(bounds),cover=true)
            else fallback?.let { it.bounds=bounds;it.draw(canvas) }
        }
        override fun setAlpha(alpha:Int)=Unit
        override fun setColorFilter(filter:ColorFilter?)=Unit
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
    }
    /** [pageRole] picks a page-specific image (Systems or game page) before the theme background. */
    fun background(context:Context,theme:OrbitThemes.Theme=OrbitThemes.current(),physicalScreen:String=screen(context),
        pageRole:String?=null):Drawable=object:Drawable() {
        private val target=artworkScreen(context,physicalScreen,theme)
        private val bitmap=pageRole?.let { image(context,theme,it,target=target) } ?: image(context,theme,"background",target=target)
        override fun draw(canvas:Canvas) {
            canvas.drawColor(theme.background)
            bitmap?.let { drawImage(canvas,it,RectF(bounds),true)
                canvas.drawColor(Color.argb(80,Color.red(theme.background),Color.green(theme.background),Color.blue(theme.background))) }
        }
        override fun setAlpha(alpha:Int)=Unit
        override fun setColorFilter(filter:ColorFilter?)=Unit
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
    }
}
