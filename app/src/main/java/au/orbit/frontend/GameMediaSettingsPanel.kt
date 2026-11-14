package au.orbit.frontend

import android.graphics.Color
import android.graphics.Rect
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** The settings page and game popup edit the same physical-screen preferences. */
internal class GameMediaSettingsPanel(private val activity:MainActivity,private val menu:EmulatorSettings.MenuWindow) {
    class Option(val title:String,val description:String,val value:()->String,val choose:((()->Unit))->Unit)
    companion object {
        fun options(activity:MainActivity,physicalScreen:String,changed:()->Unit):List<Option> {
            val screen=OrbitThemeArtwork.artworkScreen(activity,physicalScreen)
            fun save(option:String,value:Any,updated:()->Unit) {
                runCatching { UserDataStore.put(activity,"settings",ScreenMediaSettings.key(screen,option),value) }
                    .onSuccess { changed();updated() }
                    .onFailure { Toast.makeText(activity,it.message ?: "Could not save media settings",Toast.LENGTH_LONG).show() }
            }
            val background=Option("Background media","Press A to cycle media types, theme background and Off. Missing media uses the theme background.",
                { ScreenMediaSettings.label(ScreenMediaSettings.background(screen)) }) { updated ->
                val choices=ScreenMediaSettings.backgrounds
                val next=(choices.indexOf(ScreenMediaSettings.background(screen))+1)%choices.size
                save("background",choices[next],updated)
            }
            return listOf(background)+ScreenMediaSettings.categories.map { category ->
                Option(category.title,
                    if(category.folder=="manuals")"Show the manual's first page as a preview."
                    else "Show or hide ${category.title.lowercase()} in front of the background. Downloaded files are kept.",
                    { if(ScreenMediaSettings.visible(screen,category.folder))"Show" else "Hide" }) { updated ->
                    save(category.folder,if(ScreenMediaSettings.visible(screen,category.folder))0 else 1,updated)
                }
            }
        }
    }
    fun show(back: () -> Unit) {
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        val viewport=ScrollView(activity).apply { defaultFocusHighlightEnabled=false;isSmoothScrollingEnabled=true;addView(content) }
        val buttons=mutableListOf<Button>()
        options(activity, "top", activity::refreshGameMediaOptions).forEach { option ->
            fun label()="${option.title} · ${option.value()}"
            val row=Button(activity).apply {
                text=label();isAllCaps=false;textSize=16f;setTextColor(Color.WHITE);isFocusableInTouchMode=true
                SelectionStyle.apply(this)
                contentDescription=option.description
                setOnFocusChangeListener { _,focused -> if(focused)menu.selectCompanionOption(label()) }
                setOnClickListener { requestFocus();option.choose {
                    text=label();menu.selectCompanionOption(label())
                } }
            }
            buttons.add(row);content.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        menu.page("Media",viewport,backLabel="",back=back,handle={ key,event ->
            val index=buttons.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
            when(key) {
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if(event.action==KeyEvent.ACTION_DOWN) {
                        val row=buttons[(index+if(key==KeyEvent.KEYCODE_DPAD_DOWN)1 else -1).coerceIn(buttons.indices)]
                        row.requestFocus();row.requestRectangleOnScreen(Rect(0,0,row.width,row.height),false)
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> {
                    if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)buttons[index].performClick()
                    true
                }
                else -> false
            }
        })
        buttons.first().requestFocus()
    }
}
