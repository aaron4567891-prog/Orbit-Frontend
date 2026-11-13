package au.orbit.frontend

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.*

/** Original vector artwork, shared by Orbit's system gallery and companion display. */
object OrbitSystemArtwork {
    fun drawForScreen(context:Context,canvas:Canvas,area:RectF,system:SystemSpec,theme:OrbitThemes.Theme,showHero:Boolean=true,heroInBackground:Boolean=false) {
        val hero=showHero && if(heroInBackground)OrbitThemeArtwork.image(context,theme,"hero",system)!=null
            else OrbitThemeArtwork.hero(context,canvas,area,theme,system)
        if(!theme.showSystemArtwork(system.id,OrbitThemeArtwork.artworkScreen(context)))return
        val custom=OrbitThemeArtwork.systemImage(context,theme,system)
        if(custom!=null)OrbitThemeArtwork.drawImage(canvas,custom,area)
        else if(hero)OrbitConsoleImages.cached(system)?.let { OrbitThemeArtwork.drawImage(canvas,it,area) }
        else if(!hero)draw(canvas,area,system,theme)
    }
    fun draw(canvas:Canvas,area:RectF,system:SystemSpec,theme:OrbitThemes.Theme,caption:Boolean=false) {
        canvas.save();canvas.translate(area.left,area.top);canvas.scale(area.width()/600f,area.height()/400f)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader=LinearGradient(0f,0f,600f,400f,theme.surface,theme.background,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f,0f,600f,400f),28f,28f,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=theme.accent;paint.alpha=35
        for(x in 30..600 step 40)canvas.drawLine(x.toFloat(),0f,x.toFloat(),400f,paint)
        for(y in 20..400 step 40)canvas.drawLine(0f,y.toFloat(),600f,y.toFloat(),paint)
        val photo=OrbitConsoleImages.cached(system)
        if(photo!=null) {
            paint.style=Paint.Style.FILL;paint.alpha=255
            val availableHeight=if(caption)330f else 370f
            val scale=minOf(560f/photo.width,availableHeight/photo.height)
            val w=photo.width*scale;val h=photo.height*scale
            canvas.drawBitmap(photo,null,RectF((600f-w)/2,15f+(availableHeight-h)/2,(600f+w)/2,15f+(availableHeight+h)/2),paint)
            if(caption) {
                paint.color=theme.text;paint.textSize=23f;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.DEFAULT_BOLD
                val label=android.text.TextUtils.ellipsize(system.title,android.text.TextPaint(paint),550f,android.text.TextUtils.TruncateAt.END)
                canvas.drawText(label.toString(),300f,376f,paint)
            }
            canvas.restore();return
        }
        paint.alpha=255;paint.strokeWidth=4f;paint.color=theme.muted
        val handheld=system.id in setOf("gb","gbc","gba","nds","3ds","psp","psvita","atarilynx","gamegear","ngp","ngpc","wonderswan","wonderswancolor")
        val arcade=system.id in setOf("arcade","mame","fbneo","fba","neogeo","cps","cps1","cps2","cps3","naomi","naomi2","atomiswave")
        if(arcade) {
            val shape=Path().apply { moveTo(200f,62f);lineTo(400f,62f);lineTo(425f,222f);lineTo(455f,258f);lineTo(435f,328f);lineTo(165f,328f);lineTo(145f,258f);lineTo(175f,222f);close() }
            canvas.drawPath(shape,paint);canvas.drawRoundRect(RectF(218f,90f,382f,202f),9f,9f,paint)
            canvas.drawLine(207f,251f,224f,227f,paint);canvas.drawCircle(224f,227f,9f,paint)
            paint.color=theme.accent;canvas.drawCircle(344f,248f,8f,paint);canvas.drawCircle(371f,241f,8f,paint)
        } else if(handheld) {
            canvas.drawRoundRect(RectF(110f,115f,490f,285f),36f,36f,paint)
            canvas.drawRoundRect(RectF(210f,137f,390f,263f),8f,8f,paint)
            canvas.drawLine(145f,200f,185f,200f,paint);canvas.drawLine(165f,180f,165f,220f,paint)
            paint.color=theme.accent;canvas.drawCircle(433f,190f,9f,paint);canvas.drawCircle(457f,214f,9f,paint)
        } else {
            canvas.drawRoundRect(RectF(172f,94f,428f,232f),18f,18f,paint)
            canvas.drawLine(194f,126f,405f,126f,paint);canvas.drawLine(194f,143f,340f,143f,paint)
            paint.color=theme.accent;canvas.drawCircle(396f,200f,7f,paint)
            canvas.drawRoundRect(RectF(160f,265f,440f,333f),30f,30f,paint)
            canvas.drawLine(188f,299f,230f,299f,paint);canvas.drawLine(209f,278f,209f,320f,paint)
            canvas.drawCircle(367f,290f,8f,paint);canvas.drawCircle(397f,310f,8f,paint)
            paint.color=theme.muted;canvas.drawLine(300f,265f,300f,232f,paint)
        }
        if(caption) {
            paint.style=Paint.Style.FILL;paint.color=theme.text;paint.textSize=23f;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            val label=android.text.TextUtils.ellipsize(system.title,android.text.TextPaint(paint),550f,android.text.TextUtils.TruncateAt.END)
            canvas.drawText(label.toString(),300f,376f,paint)
        }
        canvas.restore()
    }
    fun drawable(system:SystemSpec,theme:OrbitThemes.Theme,context:Context?=null,showHero:Boolean=true)=object:Drawable() {
        override fun draw(canvas:Canvas) { val b=bounds;val width=minOf(b.width().toFloat(),b.height()*1.5f);val height=width/1.5f
            val area=RectF(b.centerX()-width/2,b.centerY()-height/2,b.centerX()+width/2,b.centerY()+height/2)
            val current=OrbitThemes.list().firstOrNull { it.id==theme.id } ?: theme
            if(context==null)OrbitSystemArtwork.draw(canvas,area,system,current)
            else drawForScreen(context,canvas,area,system,current,showHero) }
        override fun setAlpha(alpha:Int)=Unit
        override fun setColorFilter(filter:ColorFilter?)=Unit
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    fun bitmap(system:SystemSpec,theme:OrbitThemes.Theme,width:Int=480,height:Int=320)=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {
        draw(Canvas(it),RectF(0f,0f,width.toFloat(),height.toFloat()),system,theme,true)
    }
}

/** Instant, native system navigation. No XML, bitmap decoder or background queue. */
class OrbitSystemsView(context:Context,private val systems:List<SystemSpec>,initial:Int,private val count:(SystemSpec)->Int,
    private val selected:(SystemSpec)->Unit,private val open:(SystemSpec)->Unit,private val showHero:Boolean=true):View(context) {
    private var cursor=initial.coerceIn(systems.indices)
    private var touchX=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init { isFocusableInTouchMode=true;defaultFocusHighlightEnabled=false;isClickable=true
        setOnClickListener { open(systems[cursor]) };setOnKeyListener { _,_,event -> handle(event) };selected(systems[cursor]);prepareImages() }
    private fun prepareImages() {
        val theme=OrbitThemes.current()
        val heroScreen=if(showHero)OrbitThemeArtwork.artworkScreen(context) else OrbitThemeArtwork.artworkScreen(context,"top")
        for(offset in -1..1) {
            val spec=systems[(cursor+offset+systems.size)%systems.size]
            OrbitConsoleImages.request(context,spec) { invalidate() }
            OrbitThemeArtwork.requestHero(context,theme,spec,heroScreen) { if(showHero)invalidate() }
            OrbitThemeArtwork.requestSystemImage(context,theme,spec) { invalidate() }
        }
    }
    fun handle(event:KeyEvent):Boolean {
        val step=when(event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1;KeyEvent.KEYCODE_DPAD_RIGHT -> 1;else -> 0 }
        if(step!=0) { if(event.action==KeyEvent.ACTION_DOWN)move(step);return true }
        if(event.keyCode in setOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)performClick();return true
        }
        return false
    }
    private fun move(step:Int) { cursor=(cursor+step+systems.size)%systems.size;selected(systems[cursor]);prepareImages();invalidate() }
    override fun onDraw(canvas:Canvas) {
        val theme=OrbitThemes.current()
        val w=width.toFloat();val h=height.toFloat();val spec=systems[cursor]
        paint.color=theme.muted;paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);paint.textSize=h*0.022f
        canvas.drawText("O R B I T   /   COLLECTION",w*0.055f,h*0.10f,paint)
        paint.color=theme.text;paint.typeface=Typeface.create("sans-serif",Typeface.BOLD);paint.textSize=h*0.066f
        val title=android.text.TextUtils.ellipsize(spec.title,android.text.TextPaint(paint),w*0.40f,android.text.TextUtils.TruncateAt.END)
        canvas.drawText(title.toString(),w*0.055f,h*0.40f,paint)
        paint.typeface=Typeface.DEFAULT;paint.textSize=h*0.030f;paint.color=theme.muted
        canvas.drawText("${count(spec)} games",w*0.055f,h*0.47f,paint)
        paint.color=theme.accent;paint.textSize=h*0.023f
        canvas.drawText("${cursor+1} / ${systems.size}",w*0.055f,h*0.75f,paint)
        val area=RectF(w*0.48f,h*0.19f,w*0.94f,h*0.83f)
        OrbitSystemArtwork.drawForScreen(context,canvas,area,spec,theme,showHero,heroInBackground=true)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.action==MotionEvent.ACTION_DOWN) { touchX=event.x;return true }
        if(event.action==MotionEvent.ACTION_UP) { val delta=event.x-touchX;if(kotlin.math.abs(delta)>width*0.07f)move(if(delta>0)-1 else 1) else performClick() }
        return true
    }
    override fun performClick()=super.performClick()
}
