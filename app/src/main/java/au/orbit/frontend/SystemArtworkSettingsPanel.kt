package au.orbit.frontend

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*

/** Top-screen artwork overrides in the System Settings Customisation tab. */
class SystemArtworkSettingsPanel(
    private val activity:MainActivity,
    private val menu:EmulatorSettings.MenuWindow,
    private val system:SystemSpec
) {
    private var selectedKey="hero/image"
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float,color:Int=Color.WHITE)=TextView(activity).apply {
        text=value;textSize=size;setTextColor(color)
    }
    fun show() = showControls()

    private fun showControls(scrollY: Int = 0) {
        val theme = OrbitThemes.current()
        val screen = OrbitThemeArtwork.artworkScreen(activity, "top", theme)
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val viewport=ScrollView(activity).apply { defaultFocusHighlightEnabled=false;isSmoothScrollingEnabled=false;addView(content) }
        val controls=mutableListOf<Pair<String,View>>()
        fun refreshAfter(change:((()->Unit))->Unit) {
            val expectedRevision=menu.revision
            val key=selectedKey
            val offset=viewport.scrollY
            change {
                if(menu.dialog.isShowing && menu.revision==expectedRevision) {
                    selectedKey = key
                    showControls(offset)
                }
            }
        }
        fun control(key:String,row:View,choose:()->Unit) {
            row.isFocusable=true;row.isFocusableInTouchMode=true;row.isClickable=true
            SelectionStyle.apply(row)
            row.setOnFocusChangeListener { _,focus -> if(focus) {
                selectedKey=key
                menu.selectCompanionOption(row.contentDescription?.toString() ?: (row as? TextView)?.text?.toString() ?: key)
            } }
            row.setOnClickListener { row.requestFocus();selectedKey=key;choose() }
            controls.add(key to row)
            content.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        fun action(key:String,title:String,choose:()->Unit):Button {
            val row=Button(activity).apply { text=title;isAllCaps=false;textSize=15f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(10)) }
            control(key,row,choose)
            return row
        }
        fun imageCard(hero:Boolean) {
            val role=if(hero)"hero" else "system"
            val custom=if(hero)theme.systemHeroes[system.id]?.containsKey(screen)==true else theme.systemImages[system.id]?.containsKey(screen)==true
            val card=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(10),dp(14),dp(10)) }
            val box=FrameLayout(activity).apply { SelectionStyle.apply(this);isFocusable=false }
            val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
            val empty=label("Choose image",13f,theme.muted).apply { gravity=Gravity.CENTER }
            box.addView(image,FrameLayout.LayoutParams(-1,-1));box.addView(empty,FrameLayout.LayoutParams(-1,-1))
            card.addView(box,LinearLayout.LayoutParams(dp(150),dp(90)).apply { rightMargin=dp(16) })
            val text=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            text.addView(label(if(hero)"Hero image" else "System image",17f))
            text.addView(label(if(custom)"Custom" else if(hero)"Global" else "Default",13f,theme.muted))
            card.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            card.contentDescription = if (hero) "Hero image" else "System image"
            fun refreshPreview() {
                val bitmap=if(hero)OrbitThemeArtwork.image(activity,theme,"hero",system,screen)
                    else OrbitThemeArtwork.systemImage(activity,theme,system,screen) ?: OrbitConsoleImages.cached(system)
                image.setImageBitmap(bitmap)
                empty.visibility=if(bitmap==null)View.VISIBLE else View.GONE
            }
            refreshPreview()
            if(hero)OrbitThemeArtwork.requestHero(activity,theme,system,screen,::refreshPreview)
            else {
                OrbitThemeArtwork.requestSystemImage(activity,theme,system,screen,::refreshPreview)
                OrbitConsoleImages.request(activity,system,::refreshPreview)
            }
            control("$role/image",card) { refreshAfter { changed -> ThemeMediaSettings.chooseSystemImage(activity,system,screen,hero,changed) } }
            if(custom)action("$role/reset",if(hero)"Use global hero image" else "Use default system image") {
                selectedKey="$role/image"
                refreshAfter { changed -> ThemeMediaSettings.resetSystemImage(activity,system,screen,hero,changed) }
            }
        }
        imageCard(true)
        imageCard(false)
        var savingVisibility=false
        lateinit var visibilityButton:Button
        fun visibilitySaved() {
            savingVisibility=false
            val visible=OrbitThemes.current().showSystemArtwork(system.id,screen)
            visibilityButton.text="Show system image · ${if(visible)"On" else "Off"}"
            if(visibilityButton.hasFocus())menu.selectCompanionOption(visibilityButton.text.toString())
        }
        visibilityButton=action("visible","Show system image · ${if(theme.showSystemArtwork(system.id,screen))"On" else "Off"}") {
            if(!savingVisibility) {
                savingVisibility=true
                ThemeMediaSettings.setSystemImageVisible(activity,system,screen,!OrbitThemes.current().showSystemArtwork(system.id,screen),::visibilitySaved)
            }
        }
        action("visibility/reset","Reset image visibility") {
            if(!savingVisibility) {
                savingVisibility=true
                ThemeMediaSettings.resetSystemImageVisibility(activity,system,screen,::visibilitySaved)
            }
        }
        menu.page("Customisation · ${system.title}", viewport, backLabel = "Close", handle = { key, event ->
            val index=controls.indexOfFirst { it.second.hasFocus() }.takeIf { it>=0 }
                ?: controls.indexOfFirst { it.first==selectedKey }.coerceAtLeast(0)
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if(event.action==KeyEvent.ACTION_DOWN) {
                        val next=(index+if(key==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1).coerceIn(controls.indices)
                        val row=controls[next].second;row.requestFocus()
                        row.requestRectangleOnScreen(Rect(0,0,row.width,row.height),true)
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> {
                    if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)controls[index].second.performClick()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        })
        val expectedRevision=menu.revision
        val initial=controls.firstOrNull { it.first==selectedKey }?.second ?: controls.first().second
        initial.post { if(menu.dialog.isShowing && menu.revision==expectedRevision) {
            initial.requestFocus();viewport.scrollTo(0,scrollY)
        } }
    }
}
