package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors

/** Native rendering adapter for the original Colorful ES-DE system screen. CC-BY-NC-SA 2.0. */
class ColorfulSystemsView(
    context:Context, private val systems:List<SystemSpec>, initial:Int,
    private val gameCount:(SystemSpec)->Int,
    private val selected:(SystemSpec)->Unit, private val open:(SystemSpec)->Unit
):FrameLayout(context) {
    private var cursor=initial.coerceIn(systems.indices)
    private var frame=ThemeFrame(context)
    private val images=object:LruCache<String,Bitmap>(24*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.byteCount
    }
    private val worker=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    @Volatile private var ticket=0
    private var touchX=0f
    init {
        isFocusableInTouchMode=true;defaultFocusHighlightEnabled=false;isClickable=true
        descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        addView(frame,LayoutParams(-1,-1))
        setOnClickListener { open(systems[cursor]) }
        setOnKeyListener { _,_,event -> handleController(event) }
        update()
    }
    fun handleController(event:KeyEvent):Boolean {
        val key=event.keyCode
        if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if(event.action==KeyEvent.ACTION_DOWN)move(if(key==KeyEvent.KEYCODE_DPAD_LEFT)-1 else 1)
            return true
        }
        if(key in listOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)open(systems[cursor])
            return true
        }
        return false
    }
    private fun move(step:Int) { cursor=(cursor+step+systems.size)%systems.size;update() }
    private fun update() {
        val current=++ticket
        val spec=systems[cursor];selected(spec)
        val theme=EsDeTheme.model(context,spec.id)
        val path=theme.property("system","system-carousel","staticImage","./_inc/systems/images/${spec.id}.webp").removePrefix("./")
        val cached=images.get(path)
        if(cached!=null) { render(spec,theme,cached);return }
        worker.submit {
            if(current!=ticket)return@submit
            val bitmap=runCatching {
                val stream=runCatching { context.assets.open(EsDeTheme.base+path) }.getOrElse { context.assets.open(EsDeTheme.base+"_inc/systems/images/_default.webp") }
                stream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            bitmap?.let { images.put(path,it) }
            handler.post { if(current==ticket)render(spec,theme,bitmap) }
        }
    }
    private fun render(spec:SystemSpec,theme:EsDeTheme.Model,bitmap:Bitmap?) {
        // Replace text, background and artwork together; keep the old system visible while decoding.
        val previous=frame
        frame=ThemeFrame(context)
        // Keep the original PlayStation Vita blue across all system pages.
        setBackgroundColor(Color.rgb(5, 97, 195))
        fun text(name:String,value:String,defaultRect:String,defaultFont:Float) {
            val label=TextView(context).apply {
                text=value;includeFontPadding=false;setTextColor(theme.color("system",name,"color"))
                typeface=EsDeTheme.font(context,theme.property("system",name,"fontPath","_inc/fonts/Gilroy-Regular.ttf"))
            }
            frame.place(label,theme,"system",name,defaultRect,theme.property("system",name,"fontSize").toFloatOrNull() ?: defaultFont)
        }
        text("system-label",listOf(theme.variable("systemManufacturer"),theme.variable("systemReleaseYear"),theme.variable("systemHardwareType")).filter(String::isNotBlank).joinToString(" / "),"0.046875 0.0833 0.3073 0.032",0.0315f)
        text("system-name",theme.variable("systemName",spec.title),"0.046875 0.125 0.3073 0.2",0.075f)
        text("system-description",theme.variable("systemDescription",spec.subtitle).substringBefore("\n\n"),"0.046875 0.49 0.3073 0.204",0.027f)
        text("system-game-count","${gameCount(spec)} GAMES", "0.046875 0.774 0.3073 0.045",0.033f)
        val image=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=spec.title }
        frame.place(image,theme,"system","system-carousel","0.401 0 0.599 1")
        image.setImageBitmap(bitmap)
        addView(frame,LayoutParams(-1,-1))
        removeView(previous)
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.action) {
            MotionEvent.ACTION_DOWN -> { touchX=event.x;return true }
            MotionEvent.ACTION_UP -> { val delta=event.x-touchX;if(kotlin.math.abs(delta)>width*0.07f)move(if(delta>0)-1 else 1)else performClick();return true }
        }
        return true
    }
    override fun performClick():Boolean=super.performClick()
    fun destroy() { ticket++;worker.shutdownNow();images.evictAll() }
}
