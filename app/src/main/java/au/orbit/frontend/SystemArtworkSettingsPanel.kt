package au.orbit.frontend

import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*

/** Top-screen artwork overrides in System Settings. */
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
    fun show(back: () -> Unit) = showControls(back)

    private fun showControls(back: () -> Unit, scrollY: Int = 0) {
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
                    showControls(back, offset)
                }
            }
        }
        fun control(key:String,row:View,inline:Boolean=false,choose:()->Unit) {
            row.isFocusable=true;row.isFocusableInTouchMode=true;row.isClickable=true
            SelectionStyle.apply(row)
            row.setOnFocusChangeListener { _,focus -> if(focus) {
                selectedKey=key
                menu.selectCompanionOption(row.contentDescription?.toString() ?: (row as? TextView)?.text?.toString() ?: key)
            } }
            row.setOnClickListener { row.requestFocus();selectedKey=key;choose() }
            controls.add(key to row)
            if(!inline)content.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        fun action(key:String,title:String,choose:()->Unit):Button {
            val row=Button(activity).apply { text=title;isAllCaps=false;textSize=15f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(10)) }
            control(key,row,choose=choose)
            return row
        }
        fun imageCard(role:String,title:String) {
            val hero=role=="hero"
            val logo=role=="logo"
            val removed=when(role) {
                "hero" -> theme.heroRemoved(system.id,screen)
                "logo" -> theme.logoRemoved(system.id)
                else -> !theme.showSystemArtwork(system.id,screen)
            }
            val custom=when(role) {
                "hero" -> theme.systemHeroes[system.id]?.containsKey(screen)==true
                "logo" -> system.id in theme.systemLogos
                else -> theme.systemImages[system.id]?.containsKey(screen)==true
            }
            val card=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(10),dp(14),dp(10)) }
            val box=FrameLayout(activity).apply { SelectionStyle.apply(this);isFocusable=false }
            val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
            val empty=label("Choose image",13f,theme.muted).apply { gravity=Gravity.CENTER }
            box.addView(image,FrameLayout.LayoutParams(-1,-1));box.addView(empty,FrameLayout.LayoutParams(-1,-1))
            card.addView(box,LinearLayout.LayoutParams(dp(150),dp(90)).apply { rightMargin=dp(16) })
            val text=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
            text.addView(label(title,17f))
            text.addView(label(if(removed)"Removed" else if(custom)"Custom" else if(hero && theme.image("hero",screen)!=null)"Global" else if(hero || logo)"Bundled default" else "Default",13f,theme.muted))
            card.addView(text,LinearLayout.LayoutParams(0,-2,1f))
            card.contentDescription = title
            content.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            fun refreshPreview() {
                if(removed) { image.setImageBitmap(null);empty.text="Removed";empty.visibility=View.VISIBLE;return }
                val bitmap=if(logo)OrbitThemeArtwork.systemLogo(theme,system)
                    else if(hero)OrbitThemeArtwork.image(activity,theme,"hero",system,screen)
                    else OrbitThemeArtwork.systemImage(activity,theme,system,screen) ?: OrbitConsoleImages.cached(system)
                image.setImageBitmap(bitmap)
                empty.visibility=if(bitmap==null)View.VISIBLE else View.GONE
            }
            refreshPreview()
            if(logo)OrbitThemeArtwork.requestSystemLogo(activity,theme,system,::refreshPreview)
            else if(hero)OrbitThemeArtwork.requestHero(activity,theme,system,screen,::refreshPreview)
            else {
                OrbitThemeArtwork.requestSystemImage(activity,theme,system,screen,::refreshPreview)
                OrbitConsoleImages.request(activity,system,::refreshPreview)
            }
            control("$role/image",card,inline=true) { refreshAfter { changed ->
                // Choosing a new image is how a removed one comes back; a removed system image is switched on again.
                val shown = if(role=="system" && removed) { { ThemeMediaSettings.setSystemImageVisible(activity,system,screen,true,changed) } } else changed
                if(logo)ThemeMediaSettings.chooseSystemLogo(activity,system,shown)
                else ThemeMediaSettings.chooseSystemImage(activity,system,screen,hero,shown)
            } }
            // Remove sits beside its image (left and right move between them); a removed image has none.
            if(!removed) {
                val remove=Button(activity).apply {
                    setText("Remove");isAllCaps=false;textSize=14f;setTextColor(Color.WHITE)
                    contentDescription="Remove ${title.lowercase()}"
                    setPadding(dp(14),dp(8),dp(14),dp(8))
                }
                card.addView(remove,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(12) })
                control("$role/remove",remove,inline=true) {
                    selectedKey="$role/image"
                    refreshAfter { changed ->
                        if(role=="system")ThemeMediaSettings.setSystemImageVisible(activity,system,screen,false,changed)
                        else ThemeMediaSettings.setSystemArtworkRemoved(activity,system,if(logo)"logo" else "hero/$screen",true,changed)
                    }
                }
            }
            if(custom)action("$role/reset",if(logo)"Use default system logo" else if(hero)"Use default hero image" else "Use default system image") {
                selectedKey="$role/image"
                refreshAfter { changed ->
                    if(logo)ThemeMediaSettings.setSystemLogo(activity,theme,system,null,changed)
                    else ThemeMediaSettings.resetSystemImage(activity,system,screen,hero,changed)
                }
            }
        }
        imageCard("hero","Hero image")
        imageCard("system","System image")
        imageCard("logo","System logo")
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
        fun partner(key:String)=when {
            key.endsWith("/image") -> key.removeSuffix("/image")+"/remove"
            key.endsWith("/remove") -> key.removeSuffix("/remove")+"/image"
            else -> null
        }
        menu.page("Artwork images · ${system.title}", viewport, back = back, handle = { key, event ->
            val index=controls.indexOfFirst { it.second.hasFocus() }.takeIf { it>=0 }
                ?: controls.indexOfFirst { it.first==selectedKey }.coerceAtLeast(0)
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if(event.action==KeyEvent.ACTION_DOWN) {
                        // Rows move vertically; a Remove button counts as part of its image row.
                        val rows=controls.filterNot { it.first.endsWith("/remove") }
                        val current=controls[index].first.let { if(it.endsWith("/remove"))partner(it) else it }
                        val at=rows.indexOfFirst { it.first==current }.coerceAtLeast(0)
                        val next=(at+if(key==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1).coerceIn(rows.indices)
                        val row=rows[next].second;row.requestFocus()
                        row.requestRectangleOnScreen(Rect(0,0,row.width,row.height),true)
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> {
                    if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)controls[index].second.performClick()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val current=controls[index].first
                    val wanted=if(key==KeyEvent.KEYCODE_DPAD_RIGHT && current.endsWith("/image"))partner(current)
                        else if(key==KeyEvent.KEYCODE_DPAD_LEFT && current.endsWith("/remove"))partner(current) else null
                    if(event.action==KeyEvent.ACTION_DOWN)wanted?.let { target -> controls.firstOrNull { it.first==target }?.second?.requestFocus() }
                    true
                }
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
