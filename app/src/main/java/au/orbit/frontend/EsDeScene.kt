package au.orbit.frontend

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors
import com.caverock.androidsvg.SVG

/** Native layout renderer for imported data; game control remains owned by Orbit. */
class EsDeScene(private val activity:Activity,private val entry:ImportedThemes.Entry,private val scope:String,private val gameList:ListView?=null,private val systemChoices:List<SystemSpec> = emptyList(),private val preloadOnly:Boolean=false,private val backgroundLayer:Boolean?=null):FrameLayout(activity) {
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var ticket=0
    private var system: SystemSpec?=null
    private var count=0
    private var model:EsDeXml.Model?=null
    private var items=emptyList<EsDeXml.Item>()
    private val elements=mutableMapOf<String,View>()
    private var layout:ThemeFrame?=null
    private val videos=mutableMapOf<String,GameVideoPreview>()
    private var currentGame:Game?=null
    private var paused=false
    var onReady:(()->Unit)?=null
    companion object {
        fun clearMediaCache() { prepared.evictAll();images.evictAll() }
        private val prepared=android.util.LruCache<String,Pair<Triple<EsDeXml.Model,Media,Map<String,Bitmap?>>,Uri?>>(2)
        private val images=object:android.util.LruCache<String,Bitmap>(24*1024*1024) {
            override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
        }
        private val mediaTypes=mapOf(
            "cover" to listOf("box-2D","cover","boxart","grids"),"marquee" to listOf("wheel-hd","wheel","logo","logos","marquee"),
            "screenshot" to listOf("ss-hd","ss","screenshot","screenshots"),"fanart" to listOf("fanart","heroes"),
            "physicalmedia" to listOf("support-2D","physicalmedia"),"titlescreen" to listOf("sstitle","titlescreen"),
            "3dbox" to listOf("box-3D"),"backcover" to listOf("backcover"),
            "miximage" to listOf("miximage","mixrbv2","mixrbv1"),"video" to listOf("video-normalized","video","videos")
        )
    }
    private data class Media(val files:List<Pair<String,String>>,val metadata:JSONObject,val group:String)
    init { setBackgroundColor(if(backgroundLayer==null)Color.rgb(12,17,25) else Color.TRANSPARENT);isFocusable=false;defaultFocusHighlightEnabled=false }
    private fun sceneItems(parsed:EsDeXml.Model):List<EsDeXml.Item> {
        val source=(parsed.views[scope] ?: if(scope=="gamelist")parsed.views["detailed"] ?: parsed.views["basic"] else null).orEmpty()
        if(backgroundLayer==null)return source
        val common=parsed.views["system"].orEmpty().filter { item ->
            item.type=="image" && "background" in item.name.lowercase() &&
                parsed.views["gamelist"].orEmpty().any { it.name==item.name && it.props==item.props }
        }
        return if(backgroundLayer==true)common else source.filterNot { item -> common.any { it.name==item.name } }
    }
    fun showSystem(spec:SystemSpec,games:Int) { system=spec;count=games;currentGame=null;update(null) }
    fun showGame(game:Game?,refresh:Boolean=false) { if(refresh)prepared.evictAll();currentGame=game;paused=false;update(game) }
    fun initialise(spec:SystemSpec,games:Int,game:Game?) { system=spec;count=games;currentGame=game;update(game) }
    private fun readMedia(spec:SystemSpec,game:Game?):Media {
        if(game==null)return Media(emptyList(),JSONObject(),"")
        val group="${spec.id}/${ScraperClient.gameKey(game.uri.toString())}"
        val files=mutableListOf<Pair<String,String>>();var metadata:JSONObject?=null
        for(recordName in UserDataStore.scrapedRecordNames(activity,group)) {
            val bytes=UserDataStore.readScraped(activity,group,recordName) ?: continue
            val record=runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
            if(metadata==null)metadata=record.optJSONObject("metadata")
            ScraperClient.objects(record.opt("files")).forEach { files.add(it.optString("type") to it.optString("file")) }
        }
        return Media(files,metadata ?: JSONObject(),group)
    }
    private fun update(game:Game?) {
        val spec=system ?: return
        val generation=++ticket
        videos.values.forEach { it.stop() }
        val task=Runnable {
            val result=runCatching {
                val preparedKey=if(scope=="gamelist")"${entry.id}/${spec.id}/${game?.uri}/${ImportedThemes.options(activity,entry)}/$backgroundLayer" else null
                preparedKey?.let { prepared.get(it) }?.let { return@runCatching it }
                val parsed=expandSystemCarousel(ImportedThemes.model(activity,entry,spec.id),spec)
                val source=sceneItems(parsed).filter { it.props["visible"] !in setOf("false","0") }
                val media=readMedia(spec,game)
                fun mediaPath(types:String):String? {
                    val requested=types.split(Regex("[,\\s]+"))
                    for(type in requested)for(kind in mediaTypes[type].orEmpty()+type)media.files.firstOrNull { it.first==kind }?.let { return it.second }
                    return null
                }
                val art=source.associate { item ->
                    val path=mediaPath(item.props["imageType"].orEmpty())
                    val file=entry.file(item.props["path"] ?: item.props["staticImage"].orEmpty())
                        ?: entry.file(item.props["defaultImage"] ?: item.props["default"].orEmpty())
                    val key=if(path!=null)"${UserDataStore.location(activity)}/${media.group}/$path" else file?.path
                    val bitmap=if(item.type !in setOf("image","video","carousel","grid") || file?.extension?.lowercase() in setOf("mp4","webm","mkv"))null else key?.let { cacheKey ->
                        images.get(cacheKey) ?: runCatching {
                            val bytes=if(path!=null)UserDataStore.readScraped(activity,media.group,path) else file?.readBytes()
                            bytes?.let { decode(it,file?.extension) }?.also { it.prepareToDraw();images.put(cacheKey,it) }
                        }.getOrNull()
                    }
                    item.name to bitmap
                }
                val video=mediaPath("video")?.let { UserDataStore.scrapedUri(activity,media.group,it) }
                (Triple(parsed,media,art) to video).also { if(preparedKey!=null)prepared.put(preparedKey,it) }
            }
            activity.runOnUiThread {
                if(generation!=ticket || activity.isDestroyed)return@runOnUiThread
                if(preloadOnly) { onReady?.also { onReady=null;it() };return@runOnUiThread }
                result.onSuccess { (data,video) ->
                    val (parsed,media,art)=data
                    if(model!==parsed)build(parsed,spec)
                    for(item in items) {
                        val view=elements[item.name]
                        when(view) {
                            is TextView -> view.text=transform(metadata(item,spec,game,media.metadata),item)
                            is ImageView -> {
                                view.setImageBitmap(art[item.name])
                                (item.props["imageColor"] ?: if(item.type=="image")item.props["color"] else null)?.let { view.setColorFilter(color(it,Color.WHITE),PorterDuff.Mode.MULTIPLY) }
                            }
                            is FrameLayout -> {
                                (view.getChildAt(0) as? ImageView)?.setImageBitmap(art[item.name])
                                (view.getChildAt(1) as? TextView)?.apply { text=spec.title;visibility=if(art[item.name]==null)View.VISIBLE else View.INVISIBLE }
                            }
                        }
                        val enabled=activity.windowManager.defaultDisplay.displayId!=Display.DEFAULT_DISPLAY || UserDataStore.int(activity,"topScreenVideo",1)==1
                        if(item.type=="video" && !paused && enabled) {
                            val static=entry.file(item.props["path"].orEmpty())?.let(Uri::fromFile)
                            videos[item.name]?.show(video ?: static,((item.props["delay"]?.toFloatOrNull() ?: 1f)*1000).toLong().coerceIn(0,10000))
                        }
                    }
                    if(gameList==null && items.none { it.type in setOf("text","carousel","grid","textlist") })elements["orbitFallback"]?.let { (it as? TextView)?.text=spec.title }
                    onReady?.also { onReady=null;it() }
                }.onFailure { error ->
                    if(layout==null)build(EsDeXml.Model(emptyMap(),listOf(error.message.orEmpty())),spec)
                    onReady?.also { onReady=null;it() }
                }
            }
        }
        val readyKey=if(scope=="gamelist")"${entry.id}/${spec.id}/${game?.uri}/${ImportedThemes.options(activity,entry)}/$backgroundLayer" else null
        if(readyKey!=null && prepared.get(readyKey)!=null)task.run() else worker.execute(task)
    }
    private fun expandSystemCarousel(parsed:EsDeXml.Model,spec:SystemSpec):EsDeXml.Model {
        if(scope!="system" || systemChoices.isEmpty())return parsed
        val expanded=parsed.views[scope].orEmpty().flatMap { item ->
            if(item.type!="carousel")return@flatMap listOf(item)
            val p=item.props
            fun pair(key:String,fallback:String)= (p[key] ?: fallback).split(Regex("\\s+")).mapNotNull(String::toFloatOrNull).takeIf { it.size==2 }
                ?: fallback.split(' ').map(String::toFloat)
            val pos=pair("pos","0.5 0.5");val size=pair("size","1 1");val icon=pair("itemSize","0.15 0.15")
            val horizontal=p["type"]!="vertical"
            val max=(p["maxItemCount"]?.toFloatOrNull() ?: 7f).coerceIn(1f,20f)
            val selected=systemChoices.indexOf(spec).coerceAtLeast(0)
            val scale=p["itemScale"]?.toFloatOrNull() ?: 1f
            val centerX=pos[0]+(p["horizontalOffset"]?.toFloatOrNull() ?: 0f)*size[0]
            val centerY=pos[1]+(p["verticalOffset"]?.toFloatOrNull() ?: 0f)*size[1]
            systemChoices.mapIndexedNotNull { index,choice ->
                val offset=index-selected
                val x=centerX+if(horizontal)offset*size[0]/max else 0f
                val y=centerY+if(!horizontal)offset*size[1]/max else 0f
                if(x < -icon[0] || x>1+icon[0] || y < -icon[1] || y>1+icon[1])return@mapIndexedNotNull null
                val other=ImportedThemes.model(activity,entry,choice.id).views[scope]?.firstOrNull { it.name==item.name }?.props.orEmpty()
                val factor=if(offset==0)scale else 1f
                EsDeXml.Item("image","${item.name}-orbit-$index",p+mapOf(
                    "path" to other["staticImage"].orEmpty(),"defaultImage" to other["defaultImage"].orEmpty(),
                    "pos" to "$x $y","origin" to "0.5 0.5","size" to "${icon[0]*factor} ${icon[1]*factor}",
                    "opacity" to if(offset==0)"1" else (p["unfocusedItemOpacity"] ?: "0.5")))
            }
        }
        return parsed.copy(views=parsed.views+(scope to expanded))
    }
    private fun decode(bytes:ByteArray,extension:String?):Bitmap? {
        if(extension.equals("svg",true)) {
            val source=bytes.toString(Charsets.UTF_8)
            if(source.contains("<!DOCTYPE",true) || source.contains("<!ENTITY",true))return null
            val svg=SVG.getFromString(source)
            svg.setDocumentWidth("100%");svg.setDocumentHeight("100%")
            val bitmap=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bitmap));return bitmap
        }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        var sample=1;while(bounds.outWidth/sample>1200 || bounds.outHeight/sample>1200)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
    }
    private fun build(parsed:EsDeXml.Model,spec:SystemSpec) {
        model=parsed
        items=sceneItems(parsed)
        val frame=ThemeFrame(activity)
        val props=items.associate { it.name to it.props }
        val proxy=EsDeTheme.Model(emptyMap(),mapOf(scope to props))
        val primary=items.firstOrNull { it.type in setOf("textlist","carousel","grid") }
        for(item in items.sortedBy { it.props["zIndex"]?.toFloatOrNull() ?: when(it.type) {
            "textlist","carousel","grid" -> 50f
            "image","video" -> 30f
            else -> 40f
        } }) {
            val p=item.props
            if(p["visible"] in setOf("false","0"))continue
            var rect=if(scope=="system")"0.1 0.3 0.8 0.4" else "0.5 0.1 0.46 0.8"
            val view:View=when {
                item===primary && gameList!=null -> {
                    (gameList.parent as? ViewGroup)?.removeView(gameList)
                    gameList.setBackgroundColor(color(p["backgroundColor"],Color.TRANSPARENT))
                    gameList
                }
                item.type in setOf("carousel","grid","textlist") -> FrameLayout(activity).apply {
                    addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER },LayoutParams(-1,-1))
                    addView(TextView(activity).apply { text=spec.title;gravity=Gravity.CENTER;setTextColor(Color.WHITE);textSize=24f },LayoutParams(-1,-1))
                }
                item.type=="image" -> ImageView(activity).apply {
                    val fullBackground="background" in item.name.lowercase() && p["size"]?.trim()=="1 1"
                    scaleType=if(p.containsKey("cropSize") || fullBackground)ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(color(p["color"],Color.TRANSPARENT).takeIf { p["path"].isNullOrBlank() && p["imageType"].isNullOrBlank() } ?: Color.TRANSPARENT)
                }
                item.type=="video" -> FrameLayout(activity).apply {
                    addView(ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER },LayoutParams(-1,-1))
                    val player=GameVideoPreview(activity)
                    videos[item.name]=player;addView(player,LayoutParams(-1,-1))
                }
                else -> TextView(activity).apply {
                    text=spec.title;includeFontPadding=false;setTextColor(color(p["color"] ?: p["primaryColor"],Color.WHITE))
                    gravity=when(p["horizontalAlignment"] ?: p["alignment"]) { "center" -> Gravity.CENTER_HORIZONTAL;"right" -> Gravity.RIGHT;else -> Gravity.LEFT } or
                        when(p["verticalAlignment"]) { "center" -> Gravity.CENTER_VERTICAL;"bottom" -> Gravity.BOTTOM;else -> Gravity.TOP }
                    p["fontPath"]?.let { path -> entry.file(path)?.let { file -> runCatching { typeface=Typeface.createFromFile(file) } } }
                    ellipsize=android.text.TextUtils.TruncateAt.END
                    if(item.type in setOf("carousel","grid","textlist"))gravity=Gravity.CENTER
                }
            }
            view.alpha=p["opacity"]?.toFloatOrNull()?.coerceIn(0f,1f) ?: 1f
            view.rotation=p["rotation"]?.toFloatOrNull() ?: 0f
            view.isFocusable=view===gameList
            var adjusted=p
            if(scope=="system" && item.type=="grid") {
                adjusted=p+mapOf("pos" to "0.5 0.53","origin" to "0.5 0.5")
            }
            if(item===primary && gameList!=null && item.type=="carousel") {
                val center=p["pos"]?.split(Regex("\\s+"))?.firstOrNull()?.toFloatOrNull() ?: 0.25f
                val width=(p["itemSize"]?.split(Regex("\\s+"))?.firstOrNull()?.toFloatOrNull() ?: 0.2f)*1.5f
                adjusted=p+mapOf("pos" to "$center 0.4","origin" to "0.5 0","size" to "$width 0.6")
                gameList.setDivider(null)
            }
            if(adjusted["size"].isNullOrBlank())adjusted=adjusted+("size" to (p["cropSize"] ?: p["maxSize"] ?: "0.46 0.3"))
            val size=adjusted["size"].orEmpty().split(Regex("\\s+")).mapNotNull(String::toFloatOrNull)
            if(size.size==2 && size[1]==0f)adjusted=adjusted+("size" to "${size[0]} ${((p["fontSize"]?.toFloatOrNull() ?: 0.035f)*1.6f)}")
            val current=EsDeTheme.Model(emptyMap(),mapOf(scope to mapOf(item.name to adjusted)))
            frame.place(view,current,scope,item.name,rect,p["fontSize"]?.toFloatOrNull() ?: 0.035f)
            elements[item.name]=view
        }
        if(primary==null && gameList!=null) {
            (gameList.parent as? ViewGroup)?.removeView(gameList)
            frame.place(gameList,proxy,scope,"orbitGameList","0.04 0.15 0.42 0.8")
        }
        if(scope=="system" && items.isEmpty() && backgroundLayer!=true) {
            val label=TextView(activity).apply { text=spec.title;setTextColor(Color.WHITE);gravity=Gravity.CENTER }
            frame.place(label,proxy,scope,"orbitFallback","0.1 0.3 0.8 0.4",0.06f);elements["orbitFallback"]=label
        }
        removeAllViews();addView(frame,LayoutParams(-1,-1));layout=frame
        (gameList?.adapter as? BaseAdapter)?.notifyDataSetChanged()
        gameList?.post { if(isShown)gameList.requestFocus() }
    }
    private fun color(value:String?,fallback:Int)=value?.takeIf { it.isNotBlank() }?.let(EsDeTheme::parseColor) ?: fallback
    private fun transform(text:String,item:EsDeXml.Item)=when(item.props["letterCase"]) { "uppercase" -> text.uppercase();"lowercase" -> text.lowercase();else -> text }
    private fun metadata(item:EsDeXml.Item,spec:SystemSpec,game:Game?,data:JSONObject):String {
        val p=item.props
        p["systemdata"]?.let { return when(it) { "name" -> spec.id;"fullname" -> spec.title;"gamecountGamesNoText" -> "$count";else -> "$count games" } }
        fun field(vararg keys:String):String {
            for(key in keys)when(val value=data.opt(key)) {
                is String -> if(value.isNotBlank())return value
                is Number -> return value.toString()
                is JSONObject -> value.optString("text").takeIf(String::isNotBlank)?.let { return it }
            }
            return ""
        }
        return when(val key=p["metadata"]) {
            "name" -> game?.title.orEmpty()
            "description" -> field("description","overview").ifBlank { val texts=ScraperClient.objects(data.opt("synopsis"));(texts.firstOrNull { it.optString("langue")==ScrapePolicy.language(activity) } ?: texts.firstOrNull())?.optString("text").orEmpty() }
            "publisher" -> field("publisher","editeur")
            "developer" -> field("developer","developpeur")
            "players" -> field("players","joueurs")
            "genre" -> field("genre")
            "rating" -> field("rating","note")
            "releasedate" -> field("release_date","release_year").ifBlank { ScraperClient.objects(data.opt("dates")).firstOrNull()?.optString("text").orEmpty() }
            "systemName", "sourceSystemName" -> spec.id
            "systemFullname", "sourceSystemFullname" -> spec.title
            null -> p["text"]?.replace("${'$'}{system.fullName.noCollections}",spec.title)
                ?.replace("${'$'}{system.fullName}",spec.title)
                ?.replace("${'$'}{system.fullName.autoCollections}","")
                ?.replace("${'$'}{system.fullName.customCollections}","")
                ?: if(item.type in setOf("carousel","grid","textlist"))spec.title else if(item.type=="gamelistinfo")"$count games" else ""
            else -> field(key).ifBlank { p["defaultValue"].orEmpty().replace(":space:"," ") }
        }
    }
    fun textListProperties()=items.firstOrNull { it.type in setOf("textlist","grid","carousel") }?.props.orEmpty()
    fun pause() { paused=true;videos.values.forEach { it.stop() } }
    fun destroy() { ticket++;pause();worker.shutdownNow() }
}

class ImportedSystemsView(activity:Activity,entry:ImportedThemes.Entry,private val systems:List<SystemSpec>,initial:Int,private val count:(SystemSpec)->Int,private val selected:(SystemSpec)->Unit,private val open:(SystemSpec)->Unit):FrameLayout(activity) {
    private var cursor=initial.coerceIn(systems.indices)
    private val scene=EsDeScene(activity,entry,"system",systemChoices=systems,backgroundLayer=false)
    private var touchX=0f
    init {
        addView(scene,LayoutParams(-1,-1));isFocusableInTouchMode=true;defaultFocusHighlightEnabled=false
        descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        setOnClickListener { open(systems[cursor]) };update()
    }
    private fun update() { val spec=systems[cursor];selected(spec);scene.showSystem(spec,count(spec)) }
    fun handle(event:KeyEvent):Boolean {
        val step=when(event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_UP -> -1;KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_DOWN -> 1;else -> 0 }
        if(step!=0) { if(event.action==KeyEvent.ACTION_DOWN) { cursor=(cursor+step+systems.size)%systems.size;update() };return true }
        if(event.keyCode in listOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER)) { if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)open(systems[cursor]);return true }
        return false
    }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean {
        if(event.action==android.view.MotionEvent.ACTION_DOWN)touchX=event.x
        if(event.action==android.view.MotionEvent.ACTION_UP) { if(kotlin.math.abs(event.x-touchX)>width*0.07f) { cursor=(cursor+(if(event.x>touchX)-1 else 1)+systems.size)%systems.size;update() } else performClick() }
        return true
    }
    fun destroy()=scene.destroy()
}
