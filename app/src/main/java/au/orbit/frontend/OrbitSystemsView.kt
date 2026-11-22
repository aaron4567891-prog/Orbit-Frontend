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
/** How much a picked-up logo grows; carried logos are sized so this still fits their frame. */
private const val LIFT_SCALE = 1.15f
private const val WHEEL_HOLD_RAMP_MS = 1200f
private const val WHEEL_HOLD_START_SPEED = 8f
private const val WHEEL_HOLD_MAX_SPEED = 24f

/** Original vector artwork, shared by Orbit's system gallery and companion display. */
object OrbitSystemArtwork {
    fun drawForScreen(context:Context,canvas:Canvas,area:RectF,system:SystemSpec,theme:OrbitThemes.Theme,showHero:Boolean=true,heroInBackground:Boolean=false) {
        if (system == retroAchievementsSystem) {
            val size = minOf(area.width(), area.height()).times(0.36f).toInt()
            context.getDrawable(android.R.drawable.btn_star_big_on)?.apply {
                val left = (area.centerX() - size / 2f).toInt()
                val top = (area.centerY() - size / 2f).toInt()
                setBounds(left, top, left + size, top + size)
                draw(canvas)
            }
            return
        }
        val hero=showHero && if(heroInBackground)OrbitThemeArtwork.hasSystemHero(theme,system.id,OrbitThemeArtwork.artworkScreen(context))
            else OrbitThemeArtwork.hero(context,canvas,area,theme,system)
        if(!theme.showSystemArtwork(system.id,OrbitThemeArtwork.artworkScreen(context)))return
        val custom=OrbitThemeArtwork.systemImage(context,theme,system)
        if(custom!=null)OrbitThemeArtwork.drawImage(canvas,custom,area)
        else if(theme.systemImages[system.id]?.containsKey(OrbitThemeArtwork.artworkScreen(context))==true)return
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
    initialSystems: List<SystemSpec>,
    initial: Int,
    private val count: (SystemSpec) -> Int,
    private val selected: (SystemSpec) -> Unit,
    private val open: (SystemSpec) -> Unit,
    private val showHero: Boolean = true,
    private val verticalScroll: Boolean = false,
    private val artworkSelection: () -> SystemSpec? = { null },
    /** Horizontal view: every system in one row along the bottom of the screen. */
    private val bottomStrip: Boolean = false,
    /** Called with the new order after a system or tile has been moved. */
    private val reordered: (List<SystemSpec>) -> Unit = {}
) : View(context) {
    private val systems = initialSystems.toMutableList()
    /** Rearrange mode (entered by holding Y): A picks up and drops entries, B cancels or leaves. */
    var moving = false
        private set
    /** True while an entry is picked up and travels past its neighbours. */
    private var carrying = false
    private var orderBeforeCarry: List<SystemSpec> = emptyList()
    private var cursorBeforeCarry = 0

    fun startMove() {
        if (systems.size < 2) return
        stopHeldScroll(snap = false); stopSideHold()
        moving = true; carrying = false
        stripAnimation?.cancel()
        invalidate()
    }

    /** 0 resting, 1 fully lifted: the carried logo grows and casts a shadow. */
    private var liftAmount = 0f
    private var liftAnimation: ValueAnimator? = null
    /** Slide offsets (in rows or slots) while the carried logo and the one it passes trade places. */
    private var carryShift = 0f
    private var swapShift = 0f
    private var swapIndex = -1
    private var swapAnimation: ValueAnimator? = null
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowRect = RectF()

    private fun animateLift(target: Float) {
        liftAnimation?.cancel()
        liftAnimation = ValueAnimator.ofFloat(liftAmount, target).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { liftAmount = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun pickUp() {
        orderBeforeCarry = systems.toList(); cursorBeforeCarry = cursor
        carrying = true
        animateLift(1f)
    }

    /** A: set it down here and save the order; rearrange mode stays on. */
    private fun drop() {
        carrying = false
        animateLift(0f)
        reordered(systems.toList())
    }

    /** Lifted look for the carried logo: a soft shadow below and a slightly larger logo, drawn above the rest. */
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A system's tile in rearrange mode: a rounded box behind its logo; [lifted] draws it solid with a white border. */
    private fun drawTile(canvas: Canvas, tile: RectF, lifted: Boolean) {
        val radius = tile.height() * 0.22f
        tilePaint.style = Paint.Style.FILL
        tilePaint.color = if (lifted) OrbitThemes.current().surface else android.graphics.Color.WHITE
        tilePaint.alpha = if (lifted) 235 else 26
        canvas.drawRoundRect(tile, radius, radius, tilePaint)
        tilePaint.style = Paint.Style.STROKE
        tilePaint.strokeWidth = (tile.height() * 0.03f).coerceIn(1.5f, 4f)
        tilePaint.color = android.graphics.Color.WHITE
        tilePaint.alpha = if (lifted) 255 else 70
        canvas.drawRoundRect(tile, radius, radius, tilePaint)
    }

    /** The picked-up tile, box and logo together, raised above the others with a shadow beneath. */
    private fun drawLifted(canvas: Canvas, drawn: () -> Unit, tile: RectF) {
        val lift = liftAmount
        val drop = tile.height() * 0.14f * lift
        shadowRect.set(tile.left, tile.top + drop, tile.right, tile.bottom + drop)
        shadowPaint.color = android.graphics.Color.BLACK
        shadowPaint.alpha = (140 * lift).toInt()
        shadowPaint.maskFilter = BlurMaskFilter((tile.height() * 0.16f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        if (lift > 0f) canvas.drawRoundRect(shadowRect, tile.height() * 0.22f, tile.height() * 0.22f, shadowPaint)
        val saved = canvas.save()
        val scale = 1f + (LIFT_SCALE - 1f) * lift
        canvas.scale(scale, scale, tile.centerX(), tile.centerY())
        canvas.translate(0f, -tile.height() * 0.06f * lift)
        drawTile(canvas, tile, lifted = true)
        drawn()
        canvas.restoreToCount(saved)
    }

    /** B while carrying: put it back where it was, without saving. */
    private fun cancelCarry() {
        carrying = false
        systems.clear(); systems.addAll(orderBeforeCarry)
        cursor = cursorBeforeCarry
        stripFirst = -1
        updateSelection()
    }

    /** B otherwise: leave rearrange mode. */
    private fun leaveRearrange() {
        moving = false; carrying = false
        invalidate()
    }

    /** Swaps the picked-up entry with its neighbour; the selection travels with it. */
    private fun moveEntry(step: Int) {
        val target = cursor + step
        if (target !in systems.indices) return
        java.util.Collections.swap(systems, cursor, target)
        cursor = target
        // The carried logo glides from its old place; the one it passed slides back into the gap.
        swapIndex = cursor - step
        carryShift = -step.toFloat(); swapShift = step.toFloat()
        swapAnimation?.cancel()
        swapAnimation = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                carryShift = -step * t; swapShift = step * t
                invalidate()
            }
            start()
        }
        if (stripShown) slideStrip(step)
        invalidate()
    }
    private val lifting get() = carrying || liftAmount > 0f
    internal val artworkScrolling: Boolean
        get() = heldDirection != 0 || dragging || wheelAnimation?.isRunning == true ||
            sideHeld != 0 || stripAnimation?.isRunning == true
    private var cursor = initial.coerceIn(systems.indices)
    private var touchStart = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val artworkBounds = RectF()
    // The console image crossfades with the hero background instead of popping in over it.
    private var shownArtwork: SystemSpec? = null
    private var fadingArtwork: SystemSpec? = null
    private var artworkFadeStartedAt = 0L
    private val logoBounds = RectF()
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wheelOffset = 0f
    private var wheelAnimation: ValueAnimator? = null
    private var logoAnimation: ValueAnimator? = null
    private var logoAmounts = emptyMap<Int, Float>()
    private val startLogoAnimation = object : Runnable {
        override fun run() {
            if (!isShown || !SystemLogoAnimation.enabled()) return
            animateSelectedLogo()
        }
    }
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
    private val baseWheelSpacing get() = height * if (portrait) 0.085f else 0.135f
    // Rows spread out as the Text size for every system grows, so larger logos have room.
    private val wheelSpacing get() = baseWheelSpacing * (if (SystemLogoAnimation.enabled()) 1.10f else 1f) *
        maxOf(1f, SystemsPanel.textSize().scale * 0.85f)
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
        refreshLogoAnimation()
        if (verticalScroll) contentDescription = spec.title
        selected(spec)
        val theme = OrbitThemes.current()
        val heroScreen = if (showHero) OrbitThemeArtwork.artworkScreen(context) else OrbitThemeArtwork.artworkScreen(context, "top")
        // Heroes three either side load at full size ahead of time, so fast scrolling stays sharp. Artwork
        // decodes newest-request-first, so the farthest are asked for first and the selected system last.
        val nearby = listOf(3, -3, 2, -2, 1, -1, 0).map { Math.floorMod(cursor + it, systems.size) }.distinct()
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

    internal fun refreshLogoAnimation() {
        removeCallbacks(startLogoAnimation)
        logoAnimation?.cancel()
        if (!SystemLogoAnimation.enabled()) {
            logoAmounts = emptyMap()
            invalidate()
        } else if (verticalScroll) {
            // The wheel supplies continuous position, so no second animation or settling delay is needed.
            invalidate()
        } else postOnAnimation(startLogoAnimation)
    }

    private fun animateSelectedLogo() {
        val from = logoAmounts
        val target = cursor
        val indices = from.keys + target
        logoAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140L
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                logoAmounts = indices.associateWith { index ->
                    val start = from[index] ?: 0f
                    val end = if (index == target) 1f else 0f
                    start + (end - start) * progress
                }.filterValues { it > 0f }
                invalidate()
            }
            start()
        }
    }

    fun handle(event: KeyEvent): Boolean {
        if (moving) {
            val back = if (verticalScroll) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
            val forward = if (verticalScroll) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
            val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
            when (event.keyCode) {
                back, forward -> if (!carrying) return handleBrowse(event)
                    else if (event.action == KeyEvent.ACTION_DOWN) moveEntry(if (event.keyCode == forward) 1 else -1)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (up) { if (carrying) drop() else pickUp() }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (up) { if (carrying) cancelCarry() else leaveRearrange() }
            }
            return true
        }
        return handleBrowse(event)
    }

    private fun handleBrowse(event: KeyEvent): Boolean {
        val previous = if (verticalScroll) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_LEFT
        val next = if (verticalScroll) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_RIGHT
        val step = when (event.keyCode) {
            previous -> -1
            next -> 1
            else -> 0
        }
        if (step != 0) {
            if (!verticalScroll) {
                // A steady repeat of our own: Android's key repeat starts late and arrives unevenly.
                if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                    if (event.repeatCount == 0 && sideHeld != step) startSideHold(step)
                } else if (event.action == KeyEvent.ACTION_UP) stopSideHold()
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

    private var sideHeld = 0
    private var sideHeldSince = 0L
    private val sideRepeat = object : Runnable {
        override fun run() {
            if (sideHeld == 0) return
            move(sideHeld)
            // Steps quicken the longer the direction is held.
            val held = android.os.SystemClock.uptimeMillis() - sideHeldSince
            postDelayed(this, if (held > 1500L) 70L else 110L)
        }
    }

    private fun startSideHold(step: Int) {
        stopSideHold()
        sideHeld = step
        sideHeldSince = android.os.SystemClock.uptimeMillis()
        move(step)
        if (systems.size > 1) postDelayed(sideRepeat, 320L)
    }

    private fun stopSideHold() {
        removeCallbacks(sideRepeat)
        sideHeld = 0
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
        if (stripShown) slideStrip(step)
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
        else if (stripShown) drawStrip(canvas, theme, w, h)
        else drawTitle(canvas, theme, cursor, w, h)
        drawSelectedArtwork(canvas, theme, w, h)
    }

    private val stripShown get() = bottomStrip && !verticalScroll && !portrait
    private val stripBar = RectF()
    private val stripFrame = RectF()
    /** Logos in the bottom row at once; the row scrolls only when the selection passes an edge. */
    private val stripColumns = 6
    private var stripFirst = -1
    /** Animated slot of the selection frame and row scroll, in slots. */
    private var stripFramePos = 0f
    private var stripScroll = 0f
    private var stripAnimation: ValueAnimator? = null

    private fun stripFirstFor(index: Int, first: Int): Int {
        val count = minOf(stripColumns, systems.size)
        val maxFirst = systems.size - count
        return when {
            first < 0 -> (index - count / 2).coerceIn(0, maxFirst)
            index < first -> index
            index >= first + count -> index - count + 1
            else -> first
        }.coerceIn(0, maxFirst)
    }

    /** The frame slides to the new slot; the row scrolls when the selection reaches an edge. */
    private fun slideStrip(step: Int) {
        val oldFirst = stripFirstFor(Math.floorMod(cursor - step, systems.size), stripFirst)
        val oldSlot = Math.floorMod(cursor - step, systems.size) - oldFirst
        val newFirst = stripFirstFor(cursor, oldFirst)
        val newSlot = cursor - newFirst
        stripFirst = newFirst
        stripAnimation?.cancel()
        // Wrapping from one end to the other jumps without sliding.
        if (kotlin.math.abs(step) > 1 || kotlin.math.abs(newFirst - oldFirst) > 1) { stripFramePos = newSlot.toFloat(); stripScroll = 0f; invalidate(); return }
        val fromFrame = oldSlot.toFloat(); val fromScroll = (newFirst - oldFirst).toFloat()
        stripAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            // Shorter while a direction is held, so each slide finishes before the next step.
            duration = if (sideHeld != 0) 90L else 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                stripFramePos = fromFrame + (newSlot - fromFrame) * t
                stripScroll = fromScroll * (1f - t)
                invalidate()
            }
            start()
        }
    }

    /** Horizontal view: a one-row grid of logos along the bottom, with the selection frame moving across it. */
    private fun drawStrip(canvas: Canvas, theme: OrbitThemes.Theme, w: Float, h: Float) {
        getLocationInWindow(windowLocation)
        glassScreen.set(-windowLocation[0].toFloat(), -windowLocation[1].toFloat(),
            rootView.width - windowLocation[0].toFloat(), rootView.height - windowLocation[1].toFloat())
        val barTop = h * 0.78f
        stripBar.set(glassScreen.left, barTop, glassScreen.right, glassScreen.bottom)
        val shown = artworkSelection()?.takeIf { it in systems } ?: systems[cursor]
        if (showHero) OrbitThemeArtwork.drawSystemsPanel(context, canvas, glassScreen, stripBar, theme, shown, heroInset = 0f, topEdge = true)
        if (stripFirst < 0) stripFirst = stripFirstFor(cursor, -1)
        // Keep the row and frame in step with the selection whenever nothing is sliding.
        if (stripAnimation?.isRunning != true) {
            stripFirst = stripFirstFor(cursor, stripFirst)
            stripFramePos = (cursor - stripFirst).toFloat(); stripScroll = 0f
        }
        val count = minOf(stripColumns, systems.size)
        val margin = w * 0.02f
        val slot = (w - margin * 2) / stripColumns
        val centreY = (barTop + h) / 2f
        val slotHeight = (h - barTop) * 0.62f
        fun slotCentre(position: Float) = margin + slot * (position + 0.5f)
        // Selection frame on its slot.
        val frameX = slotCentre(stripFramePos)
        stripFrame.set(frameX - slot * 0.47f, centreY - slotHeight * 0.62f, frameX + slot * 0.47f, centreY + slotHeight * 0.62f)
        val selectedId = systems[cursor].id
        if (!carrying && liftAmount == 0f) LogoHalo.drawSelection(canvas, stripFrame, stripFrame.height() * 0.22f,
            // Rearrange mode: white outline; carrying: white tint as well.
            if (moving) android.graphics.Color.WHITE else SystemsPanel.selectionColor(selectedId, theme),
            tinted = !moving && SystemsPanel.selectionColour(selectedId) != SystemsPanel.SelectionColour.DEFAULT)
        canvas.save()
        canvas.clipRect(0f, barTop, w, h)
        var liftedStrip: (() -> Unit)? = null
        // One extra slot either side so logos scroll in and out smoothly.
        for (column in -1..count) {
            val index = stripFirst + column
            if (index !in systems.indices) continue
            val carried = lifting && index == cursor
            val centreX = if (carried) slotCentre(stripFramePos)
                else slotCentre(column + stripScroll + if (index == swapIndex) swapShift else 0f)
            val logoWidth = slot * 0.8f * SystemsPanel.textSize().scale.coerceAtMost(1.15f)
            val left = centreX - logoWidth / 2f
            fun slotTile(x: Float) = RectF(x - slot * 0.47f, centreY - slotHeight * 0.62f, x + slot * 0.47f, centreY + slotHeight * 0.62f)
            if (moving && !carried) drawTile(canvas, slotTile(centreX), lifted = false)
            if (carried) {
                val tile = slotTile(centreX)
                liftedStrip = {
                    drawLifted(canvas, {
                        drawLogo(canvas, theme, index, left, centreY, logoWidth, slotHeight, 1f, panel = false,
                            maxWidth = slot * 0.88f / LIFT_SCALE, maxHeight = slotHeight / LIFT_SCALE, centred = true)
                    }, tile)
                }
                continue
            }
            if (drawLogo(canvas, theme, index, left, centreY, logoWidth, slotHeight, 1f, panel = false,
                    maxWidth = slot * 0.9f, maxHeight = slotHeight * 1.1f, centred = true)) continue
            titlePaint.color = if (index == cursor) theme.text else theme.muted
            titlePaint.alpha = 255
            titlePaint.textSize = textScale(h) * 0.036f * SystemsPanel.textSize().scale
            val natural = titlePaint.measureText(systems[index].title)
            if (natural > slot * 0.9f) titlePaint.textSize *= maxOf(0.6f, slot * 0.9f / natural)
            val label = android.text.TextUtils.ellipsize(systems[index].title, titlePaint, slot * 0.9f,
                android.text.TextUtils.TruncateAt.END).toString()
            val textWidth = titlePaint.measureText(label)
            if (index == cursor) selectedLogoBounds.set(centreX - textWidth / 2f, centreY - titlePaint.textSize / 2f,
                centreX + textWidth / 2f, centreY + titlePaint.textSize / 2f)
            if (systems[index] == liftedSystem) continue
            canvas.drawText(label, centreX - textWidth / 2f, centreY - (titlePaint.ascent() + titlePaint.descent()) / 2f, titlePaint)
        }
        liftedStrip?.invoke()
        canvas.restore()
    }

    /** The system whose logo has lifted into the Y menu; it is not drawn in its usual place meanwhile. */
    var liftedSystem: SystemSpec? = null
        set(value) { field = value; invalidate() }
    private val selectedLogoBounds = RectF()

    /** Where the selected system's logo (or name) was last drawn, in window coordinates. */
    fun selectedLogoRectInWindow(): RectF? {
        if (selectedLogoBounds.isEmpty) return null
        val location = IntArray(2).also(::getLocationInWindow)
        return RectF(selectedLogoBounds).apply { offset(location[0].toFloat(), location[1].toFloat()) }
    }

    private val wheelWindow = RectF()
    /** Left edge of the wheel's logos and game count. */
    private val wheelLeft get() = if (portrait) 0.055f else 0.022f
    private val glassPanelShown get() = showHero && !portrait && SystemsPanel.style() != SystemsPanel.Style.OFF
    /** The right edge logos and names must stay inside: the panel's edge, or the wheel window's. */
    private fun contentRight(w: Float) =
        (if (glassPanelShown) w * SystemsPanel.width().fraction else w * if (portrait) 0.5f else 0.30f) - w * 0.022f
    private val wheelSelection = RectF()

    /** One see-through window behind the wheel's logos, and a frame in the accent colour at the selected position. */
    private val glassScreen = RectF()
    private val glassPanel = RectF()
    private val windowLocation = IntArray(2)

    private fun drawWheelWindow(canvas: Canvas, theme: OrbitThemes.Theme, w: Float, h: Float) {
        val inset = h * 0.012f
        wheelWindow.set(w * if (glassPanelShown) 0.008f else 0.03f, h * wheelTop + inset,
            if (glassPanelShown) w * SystemsPanel.width().fraction - w * 0.012f else w * if (portrait) 0.5f else 0.30f, h * 0.86f - inset)
        if (showHero && !portrait) {
            // The Systems panel down the whole left of the screen, lined up with the hero behind this view.
            getLocationInWindow(windowLocation)
            glassScreen.set(-windowLocation[0].toFloat(), -windowLocation[1].toFloat(),
                rootView.width - windowLocation[0].toFloat(), rootView.height - windowLocation[1].toFloat())
            glassPanel.set(glassScreen.left, glassScreen.top, w * SystemsPanel.width().fraction, glassScreen.bottom)
            val shown = artworkSelection()?.takeIf { it in systems } ?: systems[cursor]
            OrbitThemeArtwork.drawSystemsPanel(context, canvas, glassScreen, glassPanel, theme, shown)
        } else LogoHalo.drawWindow(canvas, wheelWindow, wheelWindow.width() * 0.06f)
        val half = baseWheelSpacing * 0.52f * maxOf(1f, SystemsPanel.textSize().scale * 0.85f)
        wheelSelection.set(wheelWindow.left + inset, wheelCentre - half, wheelWindow.right - inset, wheelCentre + half)
        val selectedId = systems[cursor].id
        // While picked up, the frame turns white so it is clear the entry is moving.
        if (!carrying && liftAmount == 0f) LogoHalo.drawSelection(canvas, wheelSelection, half * 0.45f,
            // Rearrange mode: white outline; carrying: white tint as well.
            if (moving) android.graphics.Color.WHITE else SystemsPanel.selectionColor(selectedId, theme),
            tinted = !moving && SystemsPanel.selectionColour(selectedId) != SystemsPanel.SelectionColour.DEFAULT)
    }

    private fun drawWheel(canvas: Canvas, theme: OrbitThemes.Theme, w: Float, h: Float) {
        drawWheelWindow(canvas, theme, w, h)
        canvas.save()
        canvas.clipRect(0f, h * wheelTop, w * wheelRight, h * 0.86f)
        val first = -minOf(3, (systems.size - 1) / 2)
        val last = minOf(3, systems.size / 2)
        var liftedDraw: (() -> Unit)? = null
        val tileHalf = baseWheelSpacing * 0.52f * maxOf(1f, SystemsPanel.textSize().scale * 0.85f)
        val tileInset = h * 0.012f
        fun rowTile(center: Float) = RectF(wheelWindow.left + tileInset, center - tileHalf, wheelWindow.right - tileInset, center + tileHalf)
        for (step in first..last) {
            val index = Math.floorMod(cursor + step, systems.size)
            val position = step - wheelOffset + when {
                lifting && index == cursor -> carryShift
                index == swapIndex -> swapShift
                else -> 0f
            }
            val emphasis = (1f - abs(position) * 0.23f).coerceIn(0.45f, 1f)
            // Logos sit close to the panel's left edge, giving them more width to grow.
            val left = w * (wheelLeft + (1f - emphasis) * 0.03f)
            val center = wheelCentre + position * wheelSpacing
            // Logos and names stay inside the panel, whatever its width and the system's text size.
            val available = (contentRight(w) - left).coerceAtLeast(w * 0.05f)
            val selection = SystemLogoAnimation.scrollSelection(position)
            // Zoom follows the wheel continuously, including across cursor wrap and direction changes.
            // Normal uses part of the panel's width, leaving room for Large and Extra large to grow.
            val logoWidth = minOf(w * if (portrait) 0.32f else 0.16f, available * 0.6f)
            val logoHeight = baseWheelSpacing * if (SystemLogoAnimation.enabled()) 0.88f else 0.94f
            // Logos stay fully opaque inside the wheel window; position still sets their indent and zoom.
            if (moving && !(lifting && index == cursor)) drawTile(canvas, rowTile(center), lifted = false)
            if (lifting && index == cursor) {
                val tile = rowTile(center)
                liftedDraw = {
                    drawLifted(canvas, {
                        if (!drawLogo(canvas, theme, index, left, center, logoWidth, logoHeight, 1f, selection, panel = false,
                                maxWidth = available / LIFT_SCALE, maxHeight = wheelSpacing / LIFT_SCALE)) {
                            titlePaint.color = theme.text; titlePaint.alpha = 255
                            titlePaint.textSize = textScale(h) * 0.066f * SystemsPanel.textSize(systems[index].id).scale
                            canvas.drawText(systems[index].title, left, center - (titlePaint.ascent() + titlePaint.descent()) / 2, titlePaint)
                        }
                    }, tile)
                }
                continue
            }
            if (drawLogo(canvas, theme, index, left, center, logoWidth, logoHeight, 1f, selection, panel = false,
                    maxWidth = available, maxHeight = wheelSpacing * 1.15f)) continue
            val selectedAmount = (1f - abs(position)).coerceIn(0f, 1f)
            titlePaint.color = androidx.core.graphics.ColorUtils.blendARGB(theme.muted, theme.text, selectedAmount)
            titlePaint.alpha = (255 * emphasis).toInt()
            titlePaint.textSize = textScale(h) * 0.066f * SystemLogoAnimation.scale(selection) * SystemsPanel.textSize(systems[index].id).scale
            // Shrink a long name to fit the panel before shortening it.
            val natural = titlePaint.measureText(systems[index].title)
            if (natural > available) titlePaint.textSize *= maxOf(0.6f, available / natural)
            val label = android.text.TextUtils.ellipsize(systems[index].title, titlePaint,
                available, android.text.TextUtils.TruncateAt.END)
            val baseline = center - (titlePaint.ascent() + titlePaint.descent()) / 2
            if (index == cursor) selectedLogoBounds.set(left, center - titlePaint.textSize / 2f,
                left + titlePaint.measureText(label.toString()), center + titlePaint.textSize / 2f)
            if (systems[index] == liftedSystem) continue
            canvas.drawText(label.toString(), left, baseline, titlePaint)
        }
        liftedDraw?.invoke()
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas, theme: OrbitThemes.Theme, index: Int, w: Float, h: Float) {
        val titleWidth = w * if (portrait) 0.89f else 0.40f
        val titleCenter = h * if (portrait) 0.62f else 0.37f
        val logoWidth = w * if (portrait) 0.32f else 0.16f
        if (drawLogo(canvas, theme, index, w * 0.055f, titleCenter, logoWidth, textScale(h) * 0.16f, 1f,
                logoAmounts[index] ?: 0f)) return
        titlePaint.color = theme.text
        titlePaint.alpha = 255
        titlePaint.textSize = textScale(h) * 0.066f * SystemLogoAnimation.scale(logoAmounts[index] ?: 0f) * SystemsPanel.textSize(systems[index].id).scale
        val title = android.text.TextUtils.ellipsize(systems[index].title, titlePaint, titleWidth, android.text.TextUtils.TruncateAt.END)
        val baseline = h * if (portrait) 0.65f else 0.40f
        selectedLogoBounds.set(w * 0.055f, baseline - titlePaint.textSize, w * 0.055f + titlePaint.measureText(title.toString()), baseline)
        if (systems[index] == liftedSystem) return
        canvas.drawText(title.toString(), w * 0.055f, baseline, titlePaint)
    }

    private fun drawLogo(canvas: Canvas, theme: OrbitThemes.Theme, index: Int, left: Float, center: Float,
        width: Float, height: Float, opacity: Float, selection: Float = 0f, panel: Boolean = true,
        maxWidth: Float = Float.MAX_VALUE, maxHeight: Float = Float.MAX_VALUE, centred: Boolean = false): Boolean {
        val bitmap = OrbitThemeArtwork.systemLogo(theme, systems[index]) ?: return false
        val systemId = systems[index].id
        // Fit with one scale factor so the original logo proportions are preserved; Text size scales it per system.
        val zoomed = SystemLogoAnimation.scale(selection)
        val fit = minOf(minOf(width / bitmap.width, height / bitmap.height) * SystemsPanel.textSize(systemId).scale,
            maxWidth / zoomed / bitmap.width, maxHeight / zoomed / bitmap.height)
        val halfHeight = bitmap.height * fit / 2f
        // Centred logos (the bottom strip) sit in the middle of their slot; wheel logos align to the left.
        val logoLeft = if (centred) left + width / 2f - bitmap.width * fit / 2f else left
        logoBounds.set(logoLeft, center - halfHeight, logoLeft + bitmap.width * fit, center + halfHeight)
        if (index == cursor) selectedLogoBounds.set(logoBounds)
        if (systems[index] == liftedSystem) return true
        val zoom = SystemLogoAnimation.scale(selection)
        val checkpoint = canvas.save()
        canvas.scale(zoom, zoom, logoBounds.left, logoBounds.centerY())
        logoPaint.alpha = (255 * opacity).toInt()
        // Over hero artwork each logo sits on a bordered panel so it reads on any colours.
        if (showHero) LogoHalo.drawLogo(canvas, bitmap, logoBounds, logoPaint.alpha, logoPaint, panel, systemId)
        else {
            val previous = logoPaint.colorFilter
            LogoHalo.systemFilter(systemId)?.let { logoPaint.colorFilter = it }
            canvas.drawBitmap(bitmap, null, logoBounds, logoPaint)
            logoPaint.colorFilter = previous
        }
        canvas.restoreToCount(checkpoint)
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
            stripShown -> 0.74f
            else -> 0.47f
        }
        // RetroAchievements and placeholder tiles describe themselves instead of a game count.
        val detail = if (system == retroAchievementsSystem || SystemTiles.isTile(system)) system.subtitle else "${count(system)} games"
        canvas.drawText(detail, w * if (verticalScroll) wheelLeft else 0.055f, h * countY, paint)
        paint.color = theme.accent
        paint.textSize = textScale(h) * 0.023f
        val position = "${cursor + 1} / ${systems.size}"
        val positionX = when {
            portrait -> w * 0.94f - paint.measureText(position)
            verticalScroll -> w * 0.235f
            stripShown -> w * 0.945f - paint.measureText(position)
            else -> w * 0.055f
        }
        canvas.drawText(position, positionX, h * if (verticalScroll) 0.92f else if (portrait) 0.73f else if (stripShown) 0.74f else 0.75f, paint)
        if (portrait) {
            val artWidth = minOf(w * 0.89f, h * 0.33f * 1.5f)
            val artHeight = artWidth / 1.5f
            artworkBounds.set((w - artWidth) / 2f, h * 0.29f - artHeight / 2f,
                (w + artWidth) / 2f, h * 0.29f + artHeight / 2f)
        } else if (stripShown) artworkBounds.set(w * 0.30f, h * 0.10f, w * 0.70f, h * 0.70f)
        else artworkBounds.set(w * 0.48f, h * 0.19f, w * 0.94f, h * 0.83f)
        val artworkSystem = artworkSelection()?.takeIf { it in systems } ?: system
        val now = android.os.SystemClock.uptimeMillis()
        if (artworkSystem != shownArtwork) {
            fadingArtwork = shownArtwork
            shownArtwork = artworkSystem
            artworkFadeStartedAt = now
        }
        val style = SystemBackgroundTransition.style()
        val duration = SystemBackgroundTransition.duration(style, SystemBackgroundTransition.speed())
        val progress = if (duration == 0L) 1f else ((now - artworkFadeStartedAt).toFloat() / duration).coerceIn(0f, 1f)
        val outgoing = fadingArtwork?.takeIf { progress < 1f && it in systems }
        if (outgoing == null) {
            fadingArtwork = null
            OrbitSystemArtwork.drawForScreen(context, canvas, artworkBounds, artworkSystem, theme, showHero, heroInBackground = true)
        } else {
            // Match the background's timing: fade-through-black hides both images mid-way.
            val frame = SystemBackgroundTransition.frame(
                if (style == SystemBackgroundTransition.Style.FADE) style else SystemBackgroundTransition.Style.CROSSFADE,
                progress, SystemBackgroundTransition.direction())
            fun layer(spec: SystemSpec, alpha: Float) {
                if (alpha <= 0f) return
                val saved = canvas.saveLayerAlpha(artworkBounds, (alpha * 255).toInt())
                OrbitSystemArtwork.drawForScreen(context, canvas, artworkBounds, spec, theme, showHero, heroInBackground = true)
                canvas.restoreToCount(saved)
            }
            layer(outgoing, frame.oldAlpha)
            layer(artworkSystem, if (style == SystemBackgroundTransition.Style.FADE) frame.newAlpha else progress)
            postInvalidateOnAnimation()
        }
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
            dragging = false
            wheelVelocity?.recycle()
            wheelVelocity = null
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) { dragging = false; stopHeldScroll(snap = true) }
    }

    override fun onDetachedFromWindow() {
        stopSideHold()
        removeCallbacks(startLogoAnimation)
        stopHeldScroll(snap = false)
        dragging = false
        wheelVelocity?.recycle()
        wheelVelocity = null
        wheelAnimation?.cancel()
        wheelAnimation = null
        logoAnimation?.cancel()
        logoAnimation = null
        wheelOffset = 0f
        keyTarget = 0f
        super.onDetachedFromWindow()
    }

    override fun performClick() = super.performClick()
}
