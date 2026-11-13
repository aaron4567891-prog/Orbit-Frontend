package au.orbit.frontend

import android.app.AlertDialog
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import java.util.concurrent.Executors

/** Global and per-system artwork controls; file work stays off the UI thread. */
object ThemeMediaSettings {
    private val worker=Executors.newSingleThreadExecutor()
    fun screensLabel(screens:Set<String>)=when {
        screens.size==2 -> "Both screens"
        "top" in screens -> "Top screen"
        "bottom" in screens -> "Bottom screen"
        else -> "Off"
    }
    fun imageLabel(theme:OrbitThemes.Theme,role:String)=
        theme.artwork.filter { it.role==role }.map { it.screen }.toSet().let { if(it.isEmpty())"None" else screensLabel(it) }

    private fun update(activity:MainActivity,theme:OrbitThemes.Theme,changed:()->Unit,visibilityOnly:Boolean=false,save:()->OrbitThemes.Theme) {
        worker.execute {
            val result=runCatching(save)
            activity.runOnUiThread {
                if(!activity.isDestroyed && !activity.isFinishing)result.onSuccess {
                    if(visibilityOnly)activity.mediaArtworkVisibilityChanged(theme.id) else activity.mediaArtworkChanged(theme.id)
                    changed()
                }.onFailure {
                    Toast.makeText(activity,it.message ?: "Could not update artwork",Toast.LENGTH_LONG).show()
                    if(visibilityOnly)changed()
                }
            }
        }
    }
    fun setImage(activity:MainActivity,theme:OrbitThemes.Theme,role:String,screens:Set<String>,uri:Uri?,changed:()->Unit) =
        update(activity,theme,changed) { OrbitThemeArtwork.assign(activity,theme,role,screens,uri) }
    private fun pickSource(activity:MainActivity,title:String,role:String,query:String,screens:Set<String>,
        local:(Uri)->Unit,online:(ByteArray)->Unit) {
        choose(activity,title,listOf("Search artwork","Choose from device")) { choice ->
            when(choice) {
                0 -> ArtworkSearchPicker(activity,role,query,screens,online).show()
                1 -> activity.pickThemeImage { uri -> if(uri!=null)local(uri) }
            }
        }
    }
    private fun choose(activity: MainActivity, title: String, choices: List<String>, selected: (Int) -> Unit) {
        val dialog = AlertDialog.Builder(activity.popupContext()).setTitle(title)
            .setItems(choices.toTypedArray()) { window, index ->
                window.dismiss()
                selected(index)
            }.create()
        val confirm = ArtworkConfirmPress()
        dialog.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (confirm.released(event)) {
                        val list = dialog.listView
                        val index = list.selectedItemPosition.coerceIn(choices.indices)
                        list.performItemClick(list.selectedView, index, list.getItemIdAtPosition(index))
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP) dialog.dismiss()
                    true
                }
                else -> false
            }
        }
        dialog.styleArtworkWindow(activity.popupContext())
        dialog.show()
        activity.trackPopup(dialog)
        dialog.listView.selector = artworkOutline(activity)
        FrontendNavigationBar.apply(activity.popupContext(),dialog.window)
    }
    internal fun applyDownloaded(activity:MainActivity,theme:OrbitThemes.Theme,role:String,screens:Set<String>,system:SystemSpec?,bytes:ByteArray,changed:()->Unit) {
        update(activity,theme,{
            changed()
            Toast.makeText(activity,"Artwork applied",Toast.LENGTH_SHORT).show()
        }) { OrbitThemeArtwork.assignDownloaded(activity,theme,role,screens,system,bytes) }
    }
    fun setSystemArtwork(activity:MainActivity,theme:OrbitThemes.Theme,screens:Set<String>,changed:()->Unit) {
        update(activity,theme,changed,visibilityOnly=true) {
            val latest=OrbitThemes.list().firstOrNull { it.id==theme.id } ?: theme
            val updated=latest.copy(systemArtworkScreens=screens)
            UserDataStore.saveNativeTheme(activity,updated.id,updated.json())
            OrbitThemes.refresh(activity)
            updated
        }
    }
    fun chooseSystemImage(activity:MainActivity,system:SystemSpec,screen:String,hero:Boolean,changed:()->Unit) {
        val theme=OrbitThemes.current()
        val role=if(hero)"hero" else "system"
        val screens=setOf(screen)
        pickSource(activity,if(hero)"Hero image" else "System image",role,system.title,screens,
            local={ uri -> update(activity,theme,changed) {
                if(hero)OrbitThemeArtwork.assignSystemHero(activity,theme,system,screens,uri)
                else OrbitThemeArtwork.assignSystemImage(activity,theme,system,screens,uri)
            } },
            online={ bytes -> applyDownloaded(activity,theme,role,screens,system,bytes,changed) })
    }
    fun resetSystemImage(activity:MainActivity,system:SystemSpec,screen:String,hero:Boolean,changed:()->Unit) {
        val theme=OrbitThemes.current()
        update(activity,theme,changed) {
            if(hero)OrbitThemeArtwork.assignSystemHero(activity,theme,system,setOf(screen),null)
            else OrbitThemeArtwork.assignSystemImage(activity,theme,system,setOf(screen),null)
        }
    }
    fun setSystemImageVisible(activity:MainActivity,system:SystemSpec,screen:String,visible:Boolean,changed:()->Unit) {
        require(screen in setOf("top","bottom"))
        val theme=OrbitThemes.current()
        update(activity,theme,changed,visibilityOnly=true) {
            val latest=OrbitThemes.list().firstOrNull { it.id==theme.id } ?: theme
            val updated=latest.withSystemArtworkVisible(system.id,screen,visible)
            UserDataStore.saveNativeTheme(activity,updated.id,updated.json())
            OrbitThemes.refresh(activity)
            updated
        }
    }
    fun resetSystemImageVisibility(activity:MainActivity,system:SystemSpec,screen:String,changed:()->Unit) {
        val theme=OrbitThemes.current()
        update(activity,theme,changed,visibilityOnly=true) {
            val latest=OrbitThemes.list().firstOrNull { it.id==theme.id } ?: theme
            val updated=latest.withSystemArtworkVisible(system.id,screen,screen in latest.systemArtworkScreens)
            UserDataStore.saveNativeTheme(activity,updated.id,updated.json())
            OrbitThemes.refresh(activity)
            updated
        }
    }
}
