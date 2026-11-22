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
    /** Newest request first: while scrolling, the system on screen decodes before ones already passed. */
    private val workQueue=object:java.util.concurrent.LinkedBlockingDeque<Runnable>() {
        override fun take():Runnable=takeLast()
        override fun poll():Runnable?=pollLast()
        override fun poll(timeout:Long,unit:java.util.concurrent.TimeUnit):Runnable?=pollLast(timeout,unit)
    }
    private val worker=java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,workQueue)
    /** Queued decode jobs by key, so asking again moves a waiting job to the front. */
    private val queued=mutableMapOf<String,Runnable>()
    private val previewWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val previewGeneration=java.util.concurrent.atomic.AtomicInteger()
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val loading=mutableMapOf<String,MutableList<()->Unit>>()
    fun image(context:Context,theme:OrbitThemes.Theme,role:String,system:SystemSpec?=null,target:String=artworkScreen(context,theme=theme)):Bitmap? {
        if(role=="hero" && system!=null && theme.heroRemoved(system.id,target))return null
        val path=if(role=="hero" && system!=null)systemHeroKey(theme,system.id,target)
            else if(role=="hero")theme.heroImage(target) else theme.image(role,target)
        return path?.let { cachedImage(it) ?: activeImages[it] }
            ?: if(role=="hero")theme.image(role,target)?.let { cachedImage(it) ?: activeImages[it] } else null
    }
    fun systemImage(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String=artworkScreen(context,theme=theme)):Bitmap? =
        theme.systemImages[system.id]?.get(target)?.let(::cachedImage)
    fun systemLogo(theme:OrbitThemes.Theme,system:SystemSpec):Bitmap? =
        if(theme.logoRemoved(system.id))null else cachedImage(systemLogoKey(theme, system.id))
    internal fun systemLogoKey(path: String) = "system-logo/$path"
    internal fun systemHeroKey(theme: OrbitThemes.Theme, systemId: String, target: String): String? =
        if(theme.heroRemoved(systemId,target))null
        else theme.heroImage(target,systemId) ?: if(SystemDefaultBackgrounds.supports(systemId))SystemDefaultBackgrounds.key(systemId) else null
    internal fun hasSystemHero(theme: OrbitThemes.Theme, systemId: String, target: String) =
        systemHeroKey(theme,systemId,target)!=null
    internal fun systemLogoKey(theme: OrbitThemes.Theme, systemId: String) =
        theme.systemLogos[systemId]?.let(::systemLogoKey) ?: SystemWordmarkVariants.key(systemId)
    fun requestSystemLogo(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,ready:()->Unit) {
        if (theme.logoRemoved(system.id)) return
        val path = theme.systemLogos[system.id]
        if (path != null) requestDecoded(context,path,ready,logo=true,systemId=system.id)
        else requestBitmap(SystemWordmarkVariants.key(system.id),ready) {
            SystemWordmarkVariants.load(context.applicationContext,system.id)
        }
    }
    internal fun cachedImage(path:String):Bitmap? = cache.get(path) ?: SystemArtworkPreviews.cached(path)
    @Synchronized internal fun load(context:Context,path:String):Bitmap? {
        cache.get(path)?.let { return it }
        val bytes=UserDataStore.readNativeArtwork(context,path) ?: return null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        return decodeImage(bytes,bounds)?.also { cache.put(path,it) }
    }
    internal fun request(context:Context,path:String?,ready:()->Unit) {
        requestDecoded(context,path,ready,logo=false)
    }
    private fun requestDecoded(context:Context,path:String?,ready:()->Unit,logo:Boolean,systemId:String?=null) {
        if(path==null)return
        val key=if(logo)systemLogoKey(path) else path
        requestBitmap(key,ready) {
            val bitmap=load(context.applicationContext,path)
            if(logo && bitmap!=null)SystemLogoWordmark.prepare(bitmap) {
                systemId?.let { SystemWordmarkVariants.load(context.applicationContext,it) }
            } else bitmap
        }
    }
    private fun requestBitmap(key:String,ready:()->Unit,decode:()->Bitmap?) {
        if(cache.get(key)!=null)return
        synchronized(loading) {
            loading[key]?.let { listeners ->
                listeners.add(ready)
                // Still waiting: bring it to the front, as it is wanted again now (the system just stopped on).
                queued[key]?.let { job -> if(workQueue.remove(job))workQueue.offerLast(job) }
                return
            }
            loading[key]=mutableListOf(ready)
        }
        val job=Runnable {
            synchronized(loading) { queued.remove(key) }
            runCatching { decode()?.let { cache.put(key,it) } }
            val callbacks=synchronized(loading) { loading.remove(key).orEmpty().toList() }
            handler.post { callbacks.forEach { it() } }
        }
        synchronized(loading) { queued[key]=job }
        worker.execute(job)
    }
    fun requestHero(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String=artworkScreen(context,theme=theme),ready:()->Unit) {
        if(theme.heroRemoved(system.id,target))return
        val path=theme.heroImage(target,system.id)
        if(path!=null)request(context,path,ready)
        else if(SystemDefaultBackgrounds.supports(system.id))requestBitmap(SystemDefaultBackgrounds.key(system.id),ready) {
            SystemDefaultBackgrounds.load(context.applicationContext,system.id)
        }
    }
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
    /** Never gates startup; the visible system takes priority over the rest of the cache. */
    fun warmSystems(context: Context, theme: OrbitThemes.Theme, systems: List<SystemSpec>, first: SystemSpec) {
        val app = context.applicationContext
        // Full-resolution requests use the normal foreground-artwork queue, not the bulk preview queue.
        SystemArtworkPreviews.artworkPaths(theme, setOf(first.id)).forEach { request(app, it) {} }
        requestSystemLogo(app,theme,first) {}
        requestHero(app,theme,first,"top") {}
        requestHero(app,theme,first,"bottom") {}
        OrbitConsoleImages.request(app, first)
        val generation = previewGeneration.incrementAndGet()
        previewWorker.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            if (generation != previewGeneration.get()) return@execute
            val ordered = (listOf(first) + systems).distinctBy { it.id }
            // Finish all name logos before warming backgrounds or console photographs.
            val logoSources = ordered.map { system -> logoSource(app,theme,system,cachedOnly=false) }
            val sources = logoSources + ordered.flatMap { system ->
                (theme.systemHeroes[system.id].orEmpty().values + theme.systemImages[system.id].orEmpty().values).distinct().map { path ->
                    SystemArtworkPreviews.Source(path) { UserDataStore.readNativeArtwork(app, path) }
                } + (if(setOf("top","bottom").any { theme.heroImage(it,system.id)==null && !theme.heroRemoved(system.id,it) })
                    listOf(SystemArtworkPreviews.Source(SystemDefaultBackgrounds.key(system.id)) {
                        SystemDefaultBackgrounds.bytes(app,system.id)
                    }) else emptyList()) + SystemArtworkPreviews.Source(SystemArtworkPreviews.consoleKey(system), trim = true) {
                    runCatching { app.assets.open("system-artwork/${system.id}.webp").use { it.readBytes() } }.getOrNull()
                }
            }
            runCatching { SystemArtworkPreviews.prepare(app, sources) { generation != previewGeneration.get() } }
        }
    }
    fun restoreSystemLogos(context: Context, theme: OrbitThemes.Theme, systems: List<SystemSpec>) {
        SystemArtworkPreviews.restoreLogos(context, systems.map { logoSource(context,theme,it,cachedOnly=true) })
    }
    private fun logoSource(context: Context, theme: OrbitThemes.Theme, system: SystemSpec, cachedOnly: Boolean): SystemArtworkPreviews.Source {
        val path = theme.systemLogos[system.id]
        return if (path == null) SystemArtworkPreviews.Source(SystemWordmarkVariants.key(system.id),wordmark=true) {
            SystemWordmarkVariants.bytes(context,system.id)
        } else SystemArtworkPreviews.Source(systemLogoKey(path),wordmark=true,
            horizontal={ pixels -> SystemWordmarkVariants.load(context,system.id,pixels) }) {
            if (cachedOnly) null else UserDataStore.readNativeArtwork(context,path)
        }
    }
    fun assign(context:Context,theme:OrbitThemes.Theme,role:String,screens:Set<String>,uri:Uri?) =
        assignPath(context,theme,role,screens,null,uri?.let { importImage(context,it) })
    fun assignSystemHero(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,screens:Set<String>,uri:Uri?):OrbitThemes.Theme {
        return assignPath(context,theme,"hero",screens,system,uri?.let { importImage(context,it) })
    }
    fun assignSystemImage(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,screens:Set<String>,uri:Uri?):OrbitThemes.Theme {
        return assignPath(context,theme,"system",screens,system,uri?.let { importImage(context,it) })
    }
    fun assignSystemLogo(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,uri:Uri?) =
        assignPath(context,theme,"systemLogo",emptySet(),system,uri?.let { importImage(context,it) })
    internal fun assignDownloaded(context:Context,theme:OrbitThemes.Theme,role:String,screens:Set<String>,system:SystemSpec?,bytes:ByteArray) =
        assignPath(context,theme,role,screens,system,storeImage(context,bytes))

    private fun assignPath(context:Context,theme:OrbitThemes.Theme,role:String,screens:Set<String>,system:SystemSpec?,path:String?):OrbitThemes.Theme {
        val latest=OrbitThemes.list().firstOrNull { it.id==theme.id } ?: theme
        val updated=when {
            system==null -> latest.withArtwork(role,screens,path)
            role=="hero" -> latest.withSystemHero(system.id,screens,path)
            role=="systemLogo" -> latest.withSystemLogo(system.id,path)
            else -> latest.withSystemImage(system.id,screens,path)
        }
        UserDataStore.saveNativeTheme(context,updated.id,updated.json())
        OrbitThemes.refresh(context)
        if(system==null)prepare(context,updated) else if(role=="systemLogo") {
            path?.let { runCatching { load(context,it)?.let { bitmap -> cache.put(systemLogoKey(it),SystemLogoWordmark.prepare(bitmap) {
                SystemWordmarkVariants.load(context,system.id)
            }) } } }
        } else {
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
    /**
     * Where to crop a hero, as fractions of its width and height. [left]..[right] must stay in view
     * (the logo); the window then moves towards [preferLeft]..[preferRight] (the logo plus console or art).
     */
    internal data class HeroFocus(val left:Float,val right:Float,val top:Float=0f,val bottom:Float=1f,
        val preferLeft:Float=left,val preferRight:Float=right)
    /** Bundled 16:9 backgrounds place the console art and system name here. */
    internal val BUNDLED_HERO_FOCUS=HeroFocus(0.54f,0.955f,0.2f,0.935f)
    /** Bundled heroes crop around their logo: a per-hero span for SteamGridDB banners, else the composed name area. */
    internal fun heroFocus(context:Context,path:String?):HeroFocus? {
        if(path?.startsWith("bundled-system-hero")!=true)return null
        val span=SystemDefaultBackgrounds.focus(context.applicationContext,path.substringAfterLast('/'))
            ?: return BUNDLED_HERO_FOCUS
        return if(span.size>=4)HeroFocus(span[0],span[1],preferLeft=span[2],preferRight=span[3]) else HeroFocus(span[0],span[1])
    }
    /** Start of a crop window of [visible] (0–1) that keeps [start]..[end] in view when it fits, else centres on it. */
    internal fun cropStart(start:Float,end:Float,visible:Float):Float =
        cropWindow(start,end,start,end,visible,0f)
    /**
     * Start of a crop window of [visible] (0–1). The first [inset] of the window is covered (for example by
     * the systems list), so [mustStart]..[mustEnd] is kept in the rest; within that, the window centres the
     * uncovered part on [preferStart]..[preferEnd] as closely as it can.
     */
    internal fun cropWindow(mustStart:Float,mustEnd:Float,preferStart:Float,preferEnd:Float,visible:Float,inset:Float):Float {
        if(visible>=1f)return 0f
        val open=visible*(1f-inset)
        fun centred(start:Float,end:Float)=(start+end)/2f-visible*inset-open/2f
        val offset=if(mustEnd-mustStart>open)centred(mustStart,mustEnd)
            else centred(preferStart,preferEnd).coerceIn(mustEnd-visible,mustStart-visible*inset)
        return offset.coerceIn(0f,1f-visible)
    }
    internal fun drawImage(canvas:Canvas,bitmap:Bitmap,area:RectF,cover:Boolean=false,alpha:Int=255,focus:HeroFocus?=null,inset:Float=0f) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha=alpha }
        val checkpoint=canvas.save();canvas.clipRect(area)
        if(cover) {
            // Fill the area on any screen shape, cropping around the focus (or the centre).
            val scale=maxOf(area.width()/bitmap.width,area.height()/bitmap.height)
            val visibleX=(area.width()/scale/bitmap.width).coerceAtMost(1f)
            val visibleY=(area.height()/scale/bitmap.height).coerceAtMost(1f)
            val left=if(focus==null)(1f-visibleX)/2f
                else cropWindow(focus.left,focus.right,focus.preferLeft,focus.preferRight,visibleX,inset)
            val top=if(focus==null)(1f-visibleY)/2f else cropStart(focus.top,focus.bottom,visibleY)
            val source=Rect((left*bitmap.width).toInt(),(top*bitmap.height).toInt(),
                ((left+visibleX)*bitmap.width).toInt().coerceAtMost(bitmap.width),((top+visibleY)*bitmap.height).toInt().coerceAtMost(bitmap.height))
            canvas.drawBitmap(bitmap,source,area,paint)
        } else {
            val scale=minOf(area.width()/bitmap.width,area.height()/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            canvas.drawBitmap(bitmap,null,RectF(area.centerX()-w/2,area.centerY()-h/2,area.centerX()+w/2,area.centerY()+h/2),paint)
        }
        canvas.restoreToCount(checkpoint)
    }
    /**
     * Fills [area] edge to edge on any screen shape, cropping around [focus] (or the centre).
     * Bundled banners carry no burned-in name, so [name] is drawn over a soft shade at the bottom right.
     */
    internal fun drawHero(canvas:Canvas,bitmap:Bitmap,area:RectF,alpha:Int=255,focus:HeroFocus?=null,name:Bitmap?=null,inset:Float=0f,
        nameBottom:Float=0.86f) {
        val banner=bitmap.width>bitmap.height*2.2f
        drawImage(canvas,bitmap,area,cover=true,alpha=alpha,focus=focus,inset=inset)
        if(!banner || name==null)return
        val checkpoint=canvas.save();canvas.clipRect(area)
        val shadeTop=area.top+area.height()*0.62f
        canvas.drawRect(area.left,shadeTop,area.right,area.bottom,Paint().apply {
            shader=LinearGradient(0f,shadeTop,0f,area.bottom,Color.TRANSPARENT,Color.argb(alpha*150/255,0,0,0),Shader.TileMode.CLAMP)
        })
        val width=area.width()*0.4f;val height=minOf(area.height()*0.12f,width/2.5f)
        // Clear of the bottom-screen dock, or of the Horizontal view's row along the bottom.
        val bottom=area.top+area.height()*nameBottom
        val fit=minOf(width/name.width,height/name.height)
        val nameWidth=name.width*fit
        // Bottom right, unless the hero's own logo or subject is there (Xbox): then bottom left, clear of the panel.
        val crowded=focus?.let { heroFocusOnScreen(bitmap,area,it,inset) }?.let { (start,end) ->
            end>area.right-area.width()*0.05f-nameWidth && start<area.right-area.width()*0.05f } == true
        val left=if(crowded)area.left+area.width()*(inset+0.04f) else area.right-area.width()*0.05f-nameWidth
        // Proportions kept; the logo on the hero is drawn as it is (only panel logos are restyled).
        val logo=RectF(left,bottom-height/2f-name.height*fit/2f,left+nameWidth,bottom-height/2f+name.height*fit/2f)
        canvas.drawBitmap(name,null,logo,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha=alpha })
        canvas.restoreToCount(checkpoint)
    }
    /** Where a hero's focus span (its logo or subject) lands across [area], after the same crop the background uses. */
    private fun heroFocusOnScreen(bitmap:Bitmap,area:RectF,focus:HeroFocus,inset:Float):Pair<Float,Float> {
        val scale=maxOf(area.width()/bitmap.width,area.height()/bitmap.height)
        val visible=(area.width()/scale/bitmap.width).coerceAtMost(1f)
        val start=cropWindow(focus.left,focus.right,focus.preferLeft,focus.preferRight,visible,inset)
        fun x(fraction:Float)=area.left+(fraction-start)/visible*area.width()
        return x(focus.left) to x(focus.right)
    }
    private val glassSources=java.util.WeakHashMap<Bitmap,MutableMap<Int,Bitmap>>()
    /** A small copy of a hero; enlarged with filtering it reads as glass. Fewer pixels give more blur. */
    private fun glassSource(bitmap:Bitmap,pixels:Int):Bitmap = synchronized(glassSources) {
        glassSources.getOrPut(bitmap) { mutableMapOf() }.getOrPut(pixels) {
            val height=(pixels*bitmap.height/bitmap.width.toFloat()).toInt().coerceIn(1,pixels*2)
            Bitmap.createScaledBitmap(bitmap,pixels,height,true)
        }
    }
    private val panelPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * The Systems panel over [panel], in the style chosen in System Settings: glass (the selected hero,
     * softened and lined up with the background drawn across [screen]) or solid grey, with an optional border.
     */
    fun drawSystemsPanel(context:Context,canvas:Canvas,screen:RectF,panel:RectF,theme:OrbitThemes.Theme,system:SystemSpec,
        heroInset:Float=SystemsPanel.inset(),topEdge:Boolean=false) {
        val style=SystemsPanel.style()
        if(style==SystemsPanel.Style.OFF)return
        val checkpoint=canvas.save()
        canvas.clipRect(panel)
        if(style==SystemsPanel.Style.GLASS) {
            val target=artworkScreen(context,theme=theme)
            image(context,theme,"hero",system)?.let { bitmap ->
                drawHero(canvas,glassSource(bitmap,SystemsPanel.blur().pixels),screen,
                    focus=heroFocus(context,systemHeroKey(theme,system.id,target)),inset=heroInset)
            }
            canvas.drawColor(SystemsPanel.glassTint(theme))
        } else canvas.drawColor(SystemsPanel.fillColor(style,theme))
        canvas.restoreToCount(checkpoint)
        if(SystemsPanel.border()) {
            val stroke=(panel.height()*0.003f).coerceIn(1.5f,3f)
            panelPaint.style=Paint.Style.STROKE
            panelPaint.strokeWidth=stroke
            panelPaint.color=SystemsPanel.borderColor(style,theme)
            // The border runs along the panel's open edge: its right side, or its top for the bottom strip.
            if(topEdge)canvas.drawLine(panel.left,panel.top+stroke/2,panel.right,panel.top+stroke/2,panelPaint)
            else canvas.drawLine(panel.right-stroke/2,panel.top,panel.right-stroke/2,panel.bottom,panelPaint)
        }
    }
    fun hero(context:Context,canvas:Canvas,area:RectF,theme:OrbitThemes.Theme,system:SystemSpec?=null):Boolean {
        val bitmap=image(context,theme,"hero",system) ?: return false
        val target=artworkScreen(context,theme=theme)
        drawHero(canvas,bitmap,area,focus=system?.let { heroFocus(context,systemHeroKey(theme,it.id,target)) })
        return true
    }
    /** How each hero bitmap was last shown, so an outgoing image keeps its own crop and name while it fades. */
    private class Shown(val focus: HeroFocus?, val system: SystemSpec, val theme: OrbitThemes.Theme, val bundled: Boolean)
    private val shownAs = java.util.WeakHashMap<Bitmap, Shown>()
    internal class SystemHeroBackground(context: Context, private val theme: OrbitThemes.Theme, private val system: SystemSpec, target: String,
        private val fallback: Drawable?, previous: Bitmap?, private val leftInset: Float = 0f,
        private val nameBottom: Float = 0.86f) : Drawable() {
        private val path = systemHeroKey(theme,system.id,target)
        private val focus = heroFocus(context,path)
        // Bundled heroes carry no burned-in name; the system logo is drawn beneath the artwork.
        private val bundled = path?.startsWith("bundled-system-hero") == true
        private fun name() = if (bundled) systemLogo(theme, system) else null
        init { if (bundled) requestSystemLogo(context.applicationContext, theme, system) { invalidateSelf() } }
        private fun loadedImage() = path?.let { cachedImage(it) ?: activeImages[it] }
        // Hold one image directly: cache eviction must not clear the visible background.
        // Keeping a bitmap rather than the previous drawable avoids a chain of old systems.
        private var fromImage: Bitmap? = previous.takeIf { path != null }
        private var targetImage: Bitmap? = null
        private var fadeStartedAt = 0L
        private val transition = SystemBackgroundTransition.style()
        private val fadeDuration = SystemBackgroundTransition.duration(transition, SystemBackgroundTransition.speed())
        private val direction = SystemBackgroundTransition.direction()
        // Rapid scrolling hands off the dominant image, rather than jumping to
        // a target which has barely appeared. Retain at most two bitmap references.
        val displayedImage: Bitmap? get() = if (targetImage == null ||
            fromImage != null && android.os.SystemClock.uptimeMillis() - fadeStartedAt < fadeDuration / 2)
            fromImage else targetImage

        init { loadedImage()?.let(::acceptImage) }

        private fun acceptImage(image: Bitmap) {
            if (targetImage === image) return
            synchronized(shownAs) { shownAs[image] = Shown(focus, system, theme, bundled) }
            // The same artwork arriving at full resolution replaces the preview in place, without another fade.
            if (targetImage != null) { targetImage = image; return }
            fromImage = displayedImage
            targetImage = image
            fadeStartedAt = android.os.SystemClock.uptimeMillis()
        }

        fun artworkReady() {
            val image = loadedImage()
            if (image != null) acceptImage(image)
            else { targetImage = null; fromImage = null }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            // A worker can populate the cache between construction and request().
            loadedImage()?.let(::acceptImage)
            canvas.drawColor(theme.background)
            val next = targetImage
            val previous = fromImage
            if (next != null) {
                if (previous != null && previous !== next) {
                    val elapsed = android.os.SystemClock.uptimeMillis() - fadeStartedAt
                    val progress = if (fadeDuration == 0L) 1f else (elapsed.toFloat() / fadeDuration).coerceIn(0f, 1f)
                    val frame = SystemBackgroundTransition.frame(transition, progress, direction)
                    if (transition == SystemBackgroundTransition.Style.FADE && progress < 1f) canvas.drawColor(Color.BLACK)
                    fun layer(bitmap: Bitmap, alpha: Float, x: Float, y: Float, scale: Float = 1f, label: Bitmap? = null, crop: HeroFocus? = focus) {
                        if (alpha <= 0f) return
                        val saved = canvas.save()
                        canvas.clipRect(bounds)
                        canvas.translate(x * bounds.width(), y * bounds.height())
                        canvas.scale(scale, scale, bounds.exactCenterX(), bounds.exactCenterY())
                        drawHero(canvas, bitmap, RectF(bounds), alpha = (alpha * 255).toInt(), focus = crop, name = label, inset = leftInset, nameBottom = nameBottom)
                        canvas.restoreToCount(saved)
                    }
                    layer(next, frame.newAlpha, frame.newX, frame.newY, frame.newScale, name())
                    val old = shownOf(previous)
                    layer(previous, frame.oldAlpha, frame.oldX, frame.oldY, label = old.second, crop = old.first)
                    if (progress < 1f) invalidateSelf() else fromImage = null
                } else drawHero(canvas, next, RectF(bounds), focus = focus, name = name(), inset = leftInset, nameBottom = nameBottom)
            } else if (previous != null) shownOf(previous).let { (crop, label) ->
                drawHero(canvas, previous, RectF(bounds), focus = crop, name = label, inset = leftInset, nameBottom = nameBottom)
            }
            else fallback?.let { it.bounds = bounds; it.draw(canvas) }
        }
        /** The crop and name an outgoing image was shown with (for another system, its own). */
        private fun shownOf(image: Bitmap): Pair<HeroFocus?, Bitmap?> {
            val shown = synchronized(shownAs) { shownAs[image] } ?: return focus to null
            return shown.focus to if (shown.bundled) systemLogo(shown.theme, shown.system) else null
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
    }
    internal fun systemHeroBackground(context:Context,theme:OrbitThemes.Theme,system:SystemSpec,target:String,
        fallback:Drawable?=null,previous:Bitmap?=null,leftInset:Float=0f,nameBottom:Float=0.86f) =
        SystemHeroBackground(context,theme,system,target,fallback,previous,leftInset,nameBottom)
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
