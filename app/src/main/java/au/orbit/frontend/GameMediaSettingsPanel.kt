package au.orbit.frontend

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
        val options = options(activity, "top", activity::refreshGameMediaOptions)
        val labels = options.map { option -> { "${option.title} · ${option.value()}" } }
        menu.liveActions("Hide Media", labels, { position -> options[position].choose {} }, back)
    }
}
