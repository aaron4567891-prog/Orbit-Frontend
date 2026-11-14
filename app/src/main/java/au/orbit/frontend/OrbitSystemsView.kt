package au.orbit.frontend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.view.*
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

private const val WHEEL_HOLD_DELAY_MS = 240L
private const val WHEEL_HOLD_RAMP_MS = 1200f
private const val WHEEL_HOLD_START_SPEED = 8f
private const val WHEEL_HOLD_MAX_SPEED = 24f

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

/** Native system navigation. The wheel draws cached artwork and moves independently of the preview. */
class OrbitSystemsView(
    context: Context,
    private val systems: List<SystemSpec>,
    initial: Int,
    private val count: (SystemSpec) -> Int,
    private val selected: (SystemSpec) -> Unit,
    private val open: (SystemSpec) -> Unit,
    private val showHero: Boolean = true,
    private val verticalScroll: Boolean = false
) : View(context) {
    private var cursor = initial.coerceIn(systems.indices)
    private var touchStart = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val collectionTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val artworkBounds = RectF()
    private val logoBounds = RectF()
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wheelOffset = 0f
    private var wheelAnimation: ValueAnimator? = null
    private var keyTarget = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var wheelVelocity: VelocityTracker? = null
    private var heldDirection = 0
    private var heldSince = 0L
    private var holdFrameTime = 0L
    private val holdFrame = object : Runnable {
        override fun run() {
            if (heldDirection == 0 || !isShown) {
                stopHeldScroll(snap = true)
                return
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (holdFrameTime == 0L) {
                wheelAnimation?.cancel()
                keyTarget = 0f
            } else {
                val heldScrollTime = now - heldSince - WHEEL_HOLD_DELAY_MS
                val ramp = (heldScrollTime / WHEEL_HOLD_RAMP_MS).coerceIn(0f, 1f)
                val speed = WHEEL_HOLD_START_SPEED + (WHEEL_HOLD_MAX_SPEED - WHEEL_HOLD_START_SPEED) * ramp
                scrollWheel(heldDirection * speed * (now - holdFrameTime).coerceAtMost(48L) / 1000f)
            }
            holdFrameTime = now
            postOnAnimation(this)
        }
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val portrait get() = !usesThorLayout && height > width * 1.2f
    private val wheelTop get() = if (portrait) 0.49f else 0.14f
    private val wheelRight get() = if (portrait) 0.96f else 0.46f
    private val wheelCentre get() = height * if (portrait) 0.675f else 0.50f
    private val wheelSpacing get() = height * if (portrait) 0.085f else 0.135f
    private fun textScale(height: Float) = if (portrait) minOf(height, width * 1.25f) else height

    init {
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        isClickable = true
        setOnClickListener { open(systems[cursor]) }
        setOnKeyListener { _, _, event -> handle(event) }
        updateSelection()
    }

    private fun updateSelection() {
        val spec = systems[cursor]
        if (verticalScroll) contentDescription = spec.title
        selected(spec)
        val theme = OrbitThemes.current()
        val heroScreen = if (showHero) OrbitThemeArtwork.artworkScreen(context) else OrbitThemeArtwork.artworkScreen(context, "top")
        val nearby = (-1..1).map { Math.floorMod(cursor + it, systems.size) }.distinct()
        for (index in nearby) {
            val system = systems[index]
            OrbitConsoleImages.request(context, system) { invalidate() }
            OrbitThemeArtwork.requestHero(context, theme, system, heroScreen) { if (showHero) invalidate() }
            OrbitThemeArtwork.requestSystemImage(context, theme, system) { invalidate() }
        }
        val logoRange = if (verticalScroll) minOf(4, (systems.size - 1) / 2) else 0
        val logoEnd = if (verticalScroll) minOf(4, systems.size / 2) else 0
        for (step in -logoRange..logoEnd) {
            val system = systems[Math.floorMod(cursor + step, systems.size)]
            OrbitThemeArtwork.requestSystemLogo(context, theme, system) { invalidate() }
        }
        invalidate()
    }

    fun handle(event: KeyEvent): Boolean {
        val previous = if (verticalScroll) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
        val next = if (verticalScroll) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
        val step = when (event.keyCode) {
            previous -> -1
            next -> 1
            else -> 0
        }
        if (step != 0) {
            if (!verticalScroll) {
                if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) move(step)
            } else if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                if (event.repeatCount == 0 && heldDirection != step) startHeldScroll(step)
            } else if (event.action == KeyEvent.ACTION_UP && heldDirection == step) {
                stopHeldScroll(snap = true)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) performClick()
            return true
        }
        return false
    }

    private fun startHeldScroll(step: Int) {
        stopHeldScroll(snap = false)
        heldDirection = step
        heldSince = android.os.SystemClock.uptimeMillis()
        val target = ((wheelOffset + keyTarget).roundToInt() + step).coerceIn(-3, 3)
        animateWheel(target - wheelOffset, keyStep = true)
        if (systems.size > 1) postDelayed(holdFrame, WHEEL_HOLD_DELAY_MS)
    }

    private fun stopHeldScroll(snap: Boolean) {
        val scrolling = holdFrameTime != 0L
        removeCallbacks(holdFrame)
        heldDirection = 0
        holdFrameTime = 0L
        if (scrolling && snap) animateWheel(-wheelOffset)
    }

    private fun move(step: Int) {
        val next = Math.floorMod(cursor + step, systems.size)
        if (cursor == next) return
        cursor = next
        updateSelection()
    }

    // Keep the offset relative to the closest title so the wheel can wrap indefinitely.
    private fun scrollWheel(distance: Float) {
        wheelOffset += distance
        val steps = wheelOffset.roundToInt()
        wheelOffset -= steps
        if (steps != 0) move(steps)
        invalidate()
    }

    private fun animateWheel(distance: Float, durationMs: Long = 170L, keyStep: Boolean = false) {
        wheelAnimation?.cancel()
        keyTarget = if (keyStep) distance else 0f
        if (systems.size < 2 || abs(distance) < 0.001f) return
        var travelled = 0f
        wheelAnimation = ValueAnimator.ofFloat(0f, distance).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val position = it.animatedValue as Float
                if (keyStep) keyTarget = distance - position
                scrollWheel(position - travelled)
                travelled = position
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val theme = OrbitThemes.current()
        val w = width.toFloat()
        val h = height.toFloat()
        if (verticalScroll) drawWheel(canvas, theme, w, h)
        else drawTitle(canvas, theme, cursor, w, h)
        drawSelectedArtwork(canvas, theme, w, h)
        paint.color = theme.muted
        paint.typeface = collectionTypeface
        paint.textSize = textScale(h) * 0.022f
        canvas.drawText("O R B I T   /   COLLECTION", w * 0.055f, h * 0.10f, paint)
    }

    private fun drawWheel(canvas: Canvas, theme: OrbitThemes.Theme, w: Float, h: Float) {
        canvas.save()
        canvas.clipRect(0f, h * wheelTop, w * wheelRight, h * 0.86f)
        val first = -minOf(3, (systems.size - 1) / 2)
        val last = minOf(3, systems.size / 2)
        for (step in first..last) {
            val position = step - wheelOffset
            val emphasis = (1f - abs(position) * 0.23f).coerceIn(0.45f, 1f)
            val index = Math.floorMod(cursor + step, systems.size)
            val left = w * (0.055f + (1f - emphasis) * 0.065f)
            val center = wheelCentre + position * wheelSpacing
            val available = w * (wheelRight - 0.005f) - left
            val logoHeight = if (portrait) 0.072f else 0.104f
            if (drawLogo(canvas, theme, index, left, center, available, h * logoHeight * emphasis, emphasis)) continue
            val selectedAmount = (1f - abs(position)).coerceIn(0f, 1f)
            titlePaint.color = androidx.core.graphics.ColorUtils.blendARGB(theme.muted, theme.text, selectedAmount)
            titlePaint.alpha = (255 * emphasis).toInt()
            titlePaint.textSize = textScale(h) * 0.066f * emphasis
            val label = android.text.TextUtils.ellipsize(systems[index].title, titlePaint,
                available, android.text.TextUtils.TruncateAt.END)
            val baseline = center - (titlePaint.ascent() + titlePaint.descent()) / 2
            canvas.drawText(label.toString(), left, baseline, titlePaint)
        }
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas, theme: OrbitThemes.Theme, index: Int, w: Float, h: Float) {
        val titleWidth = w * if (portrait) 0.89f else 0.40f
        val titleCenter = h * if (portrait) 0.62f else 0.37f
        if (drawLogo(canvas, theme, index, w * 0.055f, titleCenter, titleWidth, textScale(h) * 0.13f, 1f)) return
        titlePaint.color = theme.text
        titlePaint.textSize = textScale(h) * 0.066f
        val title = android.text.TextUtils.ellipsize(systems[index].title, titlePaint, titleWidth, android.text.TextUtils.TruncateAt.END)
        canvas.drawText(title.toString(), w * 0.055f, h * if (portrait) 0.65f else 0.40f, titlePaint)
    }

    private fun drawLogo(canvas: Canvas, theme: OrbitThemes.Theme, index: Int, left: Float, center: Float,
        width: Float, height: Float, opacity: Float): Boolean {
        val bitmap = OrbitThemeArtwork.systemLogo(theme, systems[index]) ?: return false
        val scale = minOf(width / bitmap.width, height / bitmap.height)
        val halfHeight = bitmap.height * scale / 2f
        logoBounds.set(left, center - halfHeight, left + bitmap.width * scale, center + halfHeight)
        logoPaint.alpha = (255 * opacity).toInt()
        canvas.drawBitmap(bitmap, null, logoBounds, logoPaint)
        return true
    }

    private fun drawSelectedArtwork(canvas: Canvas, theme: OrbitThemes.Theme, w: Float, h: Float) {
        val system = systems[cursor]
        paint.typeface = Typeface.DEFAULT
        paint.textSize = textScale(h) * 0.030f
        paint.color = theme.muted
        val countY = when {
            verticalScroll -> 0.92f
            portrait -> 0.73f
            else -> 0.47f
        }
        canvas.drawText("${count(system)} games", w * 0.055f, h * countY, paint)
        paint.color = theme.accent
        paint.textSize = textScale(h) * 0.023f
        val position = "${cursor + 1} / ${systems.size}"
        val positionX = when {
            portrait -> w * 0.94f - paint.measureText(position)
            verticalScroll -> w * 0.34f
            else -> w * 0.055f
        }
        canvas.drawText(position, positionX, h * if (verticalScroll) 0.92f else if (portrait) 0.73f else 0.75f, paint)
        if (portrait) {
            val artWidth = minOf(w * 0.89f, h * 0.33f * 1.5f)
            val artHeight = artWidth / 1.5f
            artworkBounds.set((w - artWidth) / 2f, h * 0.29f - artHeight / 2f,
                (w + artWidth) / 2f, h * 0.29f + artHeight / 2f)
        } else artworkBounds.set(w * 0.48f, h * 0.19f, w * 0.94f, h * 0.83f)
        OrbitSystemArtwork.drawForScreen(context, canvas, artworkBounds, system, theme, showHero, heroInBackground = true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (verticalScroll) return handleWheelTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchStart = event.x
            MotionEvent.ACTION_UP -> {
                val delta = event.x - touchStart
                if (abs(delta) > width * 0.07f) move(if (delta > 0) -1 else 1)
                else performClick()
            }
        }
        return true
    }

    private fun handleWheelTouch(event: MotionEvent): Boolean {
        val spacing = wheelSpacing
        if (spacing <= 0f) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            wheelVelocity?.recycle()
            wheelVelocity = VelocityTracker.obtain()
        }
        wheelVelocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopHeldScroll(snap = false)
                wheelAnimation?.cancel()
                keyTarget = 0f
                touchStart = event.y
                lastTouchY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (systems.size > 1 && (dragging || abs(event.y - touchStart) >= touchSlop)) {
                dragging = true
                scrollWheel((lastTouchY - event.y) / spacing)
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    scrollWheel((lastTouchY - event.y) / spacing)
                    val velocity = wheelVelocity?.let {
                        it.computeCurrentVelocity(1000)
                        -it.yVelocity / spacing
                    } ?: 0f
                    val coast = if (abs(velocity) >= 3f) (velocity * 0.11f).coerceIn(-4f, 4f) else 0f
                    animateWheel((wheelOffset + coast).roundToInt() - wheelOffset, if (coast == 0f) 150L else 240L)
                } else if (event.x <= width * wheelRight && event.y in height * wheelTop..height * 0.86f) {
                    val step = ((event.y - wheelCentre) / spacing + wheelOffset).roundToInt()
                    val first = -minOf(3, (systems.size - 1) / 2)
                    val last = minOf(3, systems.size / 2)
                    if (step == 0) performClick()
                    else if (step in first..last) {
                        animateWheel(step - wheelOffset)
                    } else animateWheel(-wheelOffset)
                } else {
                    if (event.x > width * wheelRight || (portrait && event.y < height * wheelTop)) performClick()
                    animateWheel(-wheelOffset)
                }
            }
            MotionEvent.ACTION_CANCEL -> animateWheel(-wheelOffset)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            wheelVelocity?.recycle()
            wheelVelocity = null
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopHeldScroll(snap = true)
    }

    override fun onDetachedFromWindow() {
        stopHeldScroll(snap = false)
        wheelVelocity?.recycle()
        wheelVelocity = null
        wheelAnimation?.cancel()
        wheelAnimation = null
        wheelOffset = 0f
        keyTarget = 0f
        super.onDetachedFromWindow()
    }

    override fun performClick() = super.performClick()
}
