package au.orbit.frontend

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

data class CompanionBrowse(val key:String,val title:String,val detail:String="",val system:SystemSpec?=null,val icon:Drawable?=null,val iconResource:Int=android.R.drawable.ic_menu_preferences)

/** Displays system heroes or the icon and label for the active menu. */
class CompanionBrowseView(private val activity:Activity):LinearLayout(activity) {
    var backdropChanged:((Drawable)->Unit)?=null
    private var backdrop:Drawable=ColorDrawable(OrbitThemes.current().background)
    private val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
    private val title=TextView(activity).apply { textSize=28f;gravity=Gravity.CENTER }
    private val detail=TextView(activity).apply { textSize=16f;gravity=Gravity.CENTER }
    private var key:String?=null
    private var imageKey:String?=null
    private var backdropTheme:OrbitThemes.Theme?=null
    private var backdropKey:String?=null
    init { orientation=VERTICAL;gravity=Gravity.CENTER;setPadding(12,8,12,8)
        addView(image,LayoutParams(-1,0,1f));addView(title,LayoutParams(-1,-2));addView(detail,LayoutParams(-1,-2)) }
    fun show(selection:CompanionBrowse,refresh:Boolean=false) {
        val theme=OrbitThemes.current()
        val screen=OrbitThemeArtwork.artworkScreen(activity)
        val nextKey="${selection.key}/$screen"
        if(key==nextKey && backdropTheme==theme && !refresh) { publishBackdrop();return }
        key=nextKey
        backdropTheme=theme
        val system=selection.system
        val labelVisibility=if(system==null)View.VISIBLE else View.GONE
        image.visibility=labelVisibility;title.visibility=labelVisibility;detail.visibility=labelVisibility
        contentDescription=system?.title
        if(system!=null) {
            val hero=OrbitThemeArtwork.systemHeroBackground(activity,theme,system,screen)
            backdrop=hero;backdropKey=null
            publishBackdrop()
            OrbitThemeArtwork.requestHero(activity,theme,system,screen,hero::invalidateSelf)
            return
        }
        title.text=selection.title;title.setTextColor(theme.text)
        detail.text=selection.detail;detail.setTextColor(theme.muted)
        image.contentDescription=selection.title
        val nextImageKey=when {
            selection.icon!=null -> "app/${selection.key}"
            else -> "resource/${selection.iconResource}"
        }
        if(imageKey!=nextImageKey || refresh) {
            val drawable=when {
                selection.icon!=null -> selection.icon.constantState?.newDrawable(resources) ?: selection.icon
                else -> activity.getDrawable(selection.iconResource)
            }
            image.setImageDrawable(drawable)
            imageKey=nextImageKey
        }
        image.invalidate()
        val nextBackdropKey="${theme.id}/${theme.background}/${theme.image("background",screen)}"
        if(backdropKey!=nextBackdropKey || refresh) {
            backdrop=OrbitThemeArtwork.background(activity,theme)
            backdropKey=nextBackdropKey
        }
        publishBackdrop()
    }
    private fun publishBackdrop() {
        if(backdropChanged==null)background=backdrop else { background=null;backdropChanged?.invoke(backdrop) }
    }
    fun clear() { key=null;imageKey=null;image.setImageDrawable(null) }
    fun destroy()=clear()
}
