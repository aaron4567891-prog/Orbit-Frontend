package au.orbit.frontend

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import java.util.concurrent.Executors

/** Gallery and authoring for original Orbit themes. */
class ThemeGalleryPanel(private val activity:Activity,private val columns:Int=3,private val exit:(()->Unit)?=null,
    private val changed:()->Unit) {
    companion object { private val worker=Executors.newSingleThreadExecutor() }
    private val blue=Color.rgb(5,97,195)
    private val muted=Color.rgb(148,161,180)
    private val wide get() = activity.resources.configuration.screenWidthDp >= 600
    val view=LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(Color.rgb(12,17,25))
    }
    var initialFocus:View=view
        private set
    private var details:OrbitThemes.Theme?=null
    private var returnFromTool: (() -> Unit)? = null
    var isRebuilding=false
        private set
    private val controls=mutableListOf<View>()
    private var scroll:ScrollView?=null
    init { gallery(false) }
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun text(value:String,size:Float,color:Int=Color.WHITE)=TextView(activity).apply { text=value;textSize=size;setTextColor(color) }
    private fun background(focused:Boolean=false)=GradientDrawable().apply {
        setColor(Color.rgb(20,29,42));cornerRadius=dp(14).toFloat();setStroke(dp(if(focused)2 else 1),if(focused)blue else Color.rgb(38,51,68))
    }
    private fun focusable(row:View,click:()->Unit) {
        row.isFocusable=true;row.isFocusableInTouchMode=true;row.isClickable=true;row.defaultFocusHighlightEnabled=false
        row.background=background();row.setOnFocusChangeListener { v,focus -> v.background=background(focus) };row.setOnClickListener { click() }
        var startX=0f;var startY=0f
        row.setOnTouchListener { v,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=event.x;startY=event.y;true }
                MotionEvent.ACTION_UP -> {
                    if(kotlin.math.abs(event.x-startX)<dp(12) && kotlin.math.abs(event.y-startY)<dp(12)) { v.requestFocus();v.performClick() }
                    true
                }
                else -> true
            }
        }
    }
    private fun action(label:String,click:()->Unit)=Button(activity).apply {
        text=label;isAllCaps=false;textSize=14f;setTextColor(Color.WHITE);backgroundTintList=null;focusable(this,click)
        setPadding(dp(6), 0, dp(6), 0)
    }
    private fun toolbar(title: String, back: (() -> Unit)? = null) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        if (back != null) {
            val size = if (title.isEmpty()) LinearLayout.LayoutParams(0, dp(44), 1f)
                else LinearLayout.LayoutParams(dp(88), dp(44))
            addView(action("‹ Back", back), size)
        }
        if (title.isNotEmpty()) {
            addView(text(title, 20f).apply {
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(if (back == null) 0 else 10), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    private fun retainFocus() {
        isRebuilding=true
        if(view.hasFocus()) {
            val policy=view.descendantFocusability
            view.descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
            view.isFocusableInTouchMode=true;view.requestFocus()
            view.descendantFocusability=policy
        }
    }
    private fun preview(theme:OrbitThemes.Theme,games:Boolean=false)=object:View(activity) {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val split=theme.libraryScreen=="bottom"
        val spec=SystemSpec("psx","PlayStation","PS","",emptySet())
        val consoles=listOf(spec,SystemSpec("snes","Super Nintendo","SNES","",emptySet()),
            SystemSpec("gba","Game Boy Advance","GBA","",emptySet()))
        override fun onDraw(canvas:Canvas) {
            val scale = minOf(width / 960f, height / 540f)
            canvas.drawColor(theme.background)
            val checkpoint = canvas.save()
            canvas.translate((width - 960 * scale) / 2, (height - 540 * scale) / 2)
            canvas.scale(scale, scale)
            if(split) {
                val top=Rect(232,8,728,267)
                val topScreen=OrbitThemeArtwork.artworkScreen(activity,"top",theme)
                val screen=canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(RectF(top),18f,18f,Path.Direction.CW) })
                OrbitThemeArtwork.systemHeroBackground(activity,theme,spec,topScreen).apply { bounds=top;draw(canvas) }
                canvas.restoreToCount(screen)
                canvas.translate(264f,281f);canvas.scale(0.45f,0.45f)
                canvas.clipPath(Path().apply { addRoundRect(RectF(0f,0f,960f,540f),32f,32f,Path.Direction.CW) })
            }
            OrbitThemeArtwork.background(activity,theme,if(split)"bottom" else OrbitThemeArtwork.screen(activity)).apply { setBounds(0,0,960,540);draw(canvas) }
            paint.color=theme.muted;paint.textSize=15f;canvas.drawText(if(games)"PLAYSTATION / GAMES" else "ORBIT / COLLECTION",38f,45f,paint)
            if(split) {
                drawSplitTiles(canvas)
            } else if(games) {
                val labels=listOf("007: The World Is Not Enough","Crash Bandicoot: Warped","Final Fantasy VII","Gran Turismo","Spyro the Dragon")
                paint.textSize=20f
                labels.forEachIndexed { i,name -> paint.color=if(i==1)theme.accent else theme.text;canvas.drawText(name,44f,132f+i*59f,paint) }
                paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=theme.accent
                canvas.drawRoundRect(RectF(32f,150f,418f,208f),6f,6f,paint);paint.style=Paint.Style.FILL
                OrbitSystemArtwork.draw(canvas,RectF(460f,100f,914f,435f),spec,theme)
            } else if(theme.systemLayout=="artwork" || OrbitThemeArtwork.image(activity,theme,"hero",spec)!=null) {
                paint.color=theme.text;paint.textSize=45f;paint.typeface=Typeface.DEFAULT_BOLD;canvas.drawText("PlayStation",38f,235f,paint)
                paint.typeface=Typeface.DEFAULT;paint.color=theme.muted;paint.textSize=19f;canvas.drawText("184 games",38f,281f,paint)
                val area=RectF(466f,103f,918f,425f)
                OrbitSystemArtwork.drawForScreen(activity,canvas,area,spec,theme)
            } else {
                listOf("PlayStation","Super Nintendo","Game Boy Advance").forEachIndexed { i,name ->
                    paint.color=theme.surface;val left=38f+i*300f;canvas.drawRoundRect(RectF(left,150f,left+280f,388f),14f,14f,paint)
                    paint.color=theme.text;paint.textSize=22f;canvas.drawText(name,left+16f,264f,paint)
                }
            }
            canvas.restoreToCount(checkpoint)
        }
        private fun drawSplitTiles(canvas:Canvas) {
            val inset=8f
            for(index in 0 until 18) {
                val x=78f+(index%6)*135f
                val y=66f+(index/6)*139f
                val tile=RectF(x,y,x+125f,y+125f)
                paint.color=theme.text
                canvas.drawRoundRect(tile,17f,17f,paint)
                val icon=RectF(tile).apply { inset(inset,inset) }
                if(!games && index<consoles.size) {
                    OrbitConsoleImages.cached(consoles[index])?.let { OrbitThemeArtwork.drawImage(canvas,it,icon) }
                        ?: OrbitSystemArtwork.draw(canvas,icon,consoles[index],theme)
                } else {
                    paint.color=if(index%3==0)theme.accent else if(index%3==1)theme.surface else theme.muted
                    canvas.drawRoundRect(icon,11f,11f,paint)
                }
                if(index==0) {
                    paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=9f
                    canvas.drawRoundRect(tile,17f,17f,paint)
                    paint.color=SplitLibraryGrid.selectionBlue;paint.strokeWidth=6f
                    canvas.drawRoundRect(tile,17f,17f,paint);paint.style=Paint.Style.FILL
                }
            }
            if(games) {
                paint.color=theme.surface
                canvas.drawRoundRect(RectF(32f,485f,928f,529f),14f,14f,paint)
                paint.color=theme.text;paint.textSize=20f
                canvas.drawText("‹ Systems",48f,514f,paint)
                canvas.drawText("007: The World Is Not Enough",185f,514f,paint)
            }
        }
        init { contentDescription="${theme.name} · ${if(games)"Game list" else "Systems"} preview"
            (if(split && !games)consoles else listOf(spec)).forEach { OrbitConsoleImages.request(activity,it) { invalidate() } }
            if(split)OrbitThemeArtwork.requestHero(activity,theme,spec,OrbitThemeArtwork.artworkScreen(activity,"top",theme)) { invalidate() }
            else OrbitThemeArtwork.requestSystemArtwork(activity,theme,spec) { invalidate() }
        }
    }
    private fun gallery(focus:Boolean=true) {
        retainFocus();details=null;returnFromTool=null;controls.clear();view.removeAllViews()
        val header = toolbar(if (wide) "Themes" else "", exit)
        val create=action("＋ Create theme",::createTheme)
        val reload=action("Reload") { worker.execute {
            val result=runCatching { OrbitThemes.refresh(activity) }
            activity.runOnUiThread { if(!activity.isDestroyed)result.onSuccess { gallery() }.onFailure { error(it) } }
        } }
        controls.add(create);controls.add(reload)
        header.addView(create, if (wide) LinearLayout.LayoutParams(dp(150), dp(44)) else LinearLayout.LayoutParams(0, dp(44), 1.5f))
        header.addView(reload, if (wide) LinearLayout.LayoutParams(dp(90), dp(44)) else LinearLayout.LayoutParams(0, dp(44), 1f))
        view.addView(header)
        val grid=GridLayout(activity).apply { columnCount=columns }
        scroll=ScrollView(activity).apply { defaultFocusHighlightEnabled=false;addView(grid) }
        view.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val horizontalInset = if (exit == null) 16 else 48
        val cardWidth = (activity.resources.configuration.screenWidthDp - horizontalInset) / columns - 10
        val cardHeight = (cardWidth * 9 / 16 + 54).coerceIn(144, 280)
        val activeTheme = OrbitThemes.current().id
        OrbitThemes.list().forEachIndexed { index,theme ->
            val card=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(8)) }
            card.addView(preview(theme),LinearLayout.LayoutParams(-1,0,1f))
            card.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), 0)
                addView(text(theme.name, 18f).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (theme.id == activeTheme) addView(text("✓ Active", 12f, blue))
            })
            focusable(card) { showDetails(theme) };controls.add(card)
            grid.addView(card,GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)).apply {
                width=0;height=dp(cardHeight);setMargins(dp(5),dp(5),dp(5),dp(9))
            })
            if(theme.id==activeTheme)initialFocus=card
        }
        if(initialFocus !in controls)initialFocus=controls.first()
        controls.forEach { it.setOnKeyListener { _,_,event -> handleNavigation(event) } }
        if(focus)initialFocus.post { initialFocus.requestFocus();isRebuilding=false } else isRebuilding=false
    }
    private fun showDetails(theme:OrbitThemes.Theme) {
        retainFocus();details=theme;returnFromTool=null;view.removeAllViews()
        val header = toolbar(theme.name) { gallery() }
        header.addView(action("Artwork credits") {
            val credits=activity.assets.open("system-artwork/ARTWORK-CREDITS.md").bufferedReader().use { it.readText() }
            showTool("Console artwork credits", { showDetails(theme) }) {
                val creditsView = text("Artwork by viking, faeran and contributors. Creative Commons Attribution-NonCommercial-ShareAlike 2.0.\n\n$credits", 14f)
                addView(ScrollView(activity).apply { defaultFocusHighlightEnabled = false; addView(creditsView) },
                    LinearLayout.LayoutParams(-1, 0, 1f))
            }
        },LinearLayout.LayoutParams(dp(if (wide) 132 else 105),dp(44)))
        view.addView(header)
        if (theme.description.isNotBlank()) {
            view.addView(text(theme.description, 13f, muted).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        val previewHost=FrameLayout(activity)
        previewHost.addView(preview(theme),FrameLayout.LayoutParams(-1,-1))
        view.addView(previewHost,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(4);bottomMargin=dp(6) })
        worker.execute {
            OrbitThemeArtwork.prepare(activity,theme)
            activity.runOnUiThread { if(details?.id==theme.id)previewHost.getChildAt(0)?.invalidate() }
        }
        val choices=LinearLayout(activity)
        choices.addView(action("Systems preview") { previewHost.removeAllViews();previewHost.addView(preview(theme),FrameLayout.LayoutParams(-1,-1)) },LinearLayout.LayoutParams(0,dp(44),1f))
        choices.addView(action("Game list preview") { previewHost.removeAllViews();previewHost.addView(preview(theme,true),FrameLayout.LayoutParams(-1,-1)) },LinearLayout.LayoutParams(0,dp(44),1f))
        val apply=action(if(OrbitThemes.current().id==theme.id)"✓ Applied" else "Apply theme") {
            worker.execute {
                val result=runCatching { OrbitThemes.apply(activity,theme) }
                activity.runOnUiThread { if(!activity.isDestroyed)result.onSuccess {
                    changed()
                    if(view.isAttachedToWindow)showDetails(theme)
                }.onFailure { error(it) } }
            }
        }
        val actions=if (wide) choices else LinearLayout(activity)
        actions.addView(apply,LinearLayout.LayoutParams(0,dp(48),1f))
        if(OrbitThemes.canDelete(theme))actions.addView(action("Delete theme") { deleteTheme(theme) },LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(8) })
        if (!wide) view.addView(choices)
        view.addView(actions,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(4) })
        initialFocus=apply;apply.post { apply.requestFocus();isRebuilding=false }
    }
    private fun showTool(title: String, back: () -> Unit, body: LinearLayout.() -> Unit) {
        retainFocus()
        returnFromTool = back
        controls.clear()
        view.removeAllViews()
        val header = toolbar(title, back)
        view.addView(header)
        view.body()
        header.getChildAt(0).requestFocus()
        isRebuilding = false
    }
    private fun deleteTheme(theme:OrbitThemes.Theme) {
        if(!OrbitThemes.canDelete(theme))return
        val back = { showDetails(theme) }
        showTool("Delete ${theme.name}?", back) {
            addView(text("If this theme is active, Orbit Midnight will be applied.", 14f, muted))
            lateinit var delete: Button
            delete = action("Delete theme") {
                if (delete.isEnabled) {
                    delete.isEnabled = false
                    val active = OrbitThemes.current().id == theme.id
                    worker.execute {
                        val result = runCatching { OrbitThemes.delete(activity, theme) }
                        activity.runOnUiThread {
                            if (!activity.isDestroyed) {
                                delete.isEnabled = true
                                result.onSuccess {
                                    if (active) changed()
                                    gallery(focus = view.hasFocus())
                                }.onFailure { error(it) }
                            }
                        }
                    }
                }
            }
            addView(delete, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }
    private fun createTheme() {
        val back = { gallery() }
        val bases = OrbitThemes.list()
        var baseIndex = bases.indexOfFirst { it.id == OrbitThemes.current().id }.coerceAtLeast(0)
        val name = EditText(activity).apply { hint = "Theme name"; setSingleLine(); setText("My Orbit theme"); selectAll() }
        showTool("Create Orbit theme", back) {
            addView(name)
            lateinit var base: Button
            base = action("Starting layout · ${bases[baseIndex].name}") {
                baseIndex = (baseIndex + 1) % bases.size
                base.text = "Starting layout · ${bases[baseIndex].name}"
            }
            addView(base, LinearLayout.LayoutParams(-1, dp(48)))
            lateinit var create: Button
            create = action("Create") {
                val title = name.text.toString().trim()
                if (title.isEmpty() || title.length > 60) name.error = "Enter a name with 1–60 characters"
                else if (create.isEnabled) {
                    create.isEnabled = false
                    val selected = bases[baseIndex]
                    worker.execute {
                        val result = runCatching { OrbitThemes.create(activity, title, selected) }
                        activity.runOnUiThread {
                            if (!activity.isDestroyed) {
                                create.isEnabled = true
                                result.onSuccess { if (returnFromTool === back) showDetails(it) else gallery(focus = view.hasFocus()) }
                                    .onFailure { error(it) }
                            }
                        }
                    }
                }
            }
            addView(create, LinearLayout.LayoutParams(-1, dp(48)))
        }
        name.requestFocus()
    }
    private fun error(error:Throwable)=Toast.makeText(activity,error.message ?: "Could not update theme",Toast.LENGTH_LONG).show()
    fun back():Boolean {
        returnFromTool?.let { it(); return true }
        if(details==null)return false
        gallery()
        return true
    }
    fun handleNavigation(event:KeyEvent):Boolean {
        if(details!=null)return false
        val current=controls.indexOfFirst { it.hasFocus() };if(current<0)return false
        val key=event.keyCode
        if(key in setOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)controls[current].performClick();return true
        }
        val next=when(key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if(current==0 || (current>=2 && (current-2)%columns==0))return false else current-1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if(current==0)1 else if(current>=2 && (current-2)%columns<columns-1)minOf(current+1,controls.lastIndex) else current
            KeyEvent.KEYCODE_DPAD_UP -> if(current<2)current else if(current-2<columns)minOf(current-2,1) else current-columns
            KeyEvent.KEYCODE_DPAD_DOWN -> if(current<2)minOf(2+current,controls.lastIndex) else minOf(current+columns,controls.lastIndex)
            else -> return false
        }
        if(event.action==KeyEvent.ACTION_DOWN) {
            val target=controls[next];target.requestFocus()
            if(next>=2)target.requestRectangleOnScreen(Rect(0,0,target.width,target.height),false)
        }
        return true
    }
}
