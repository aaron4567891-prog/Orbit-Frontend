package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.FrameLayout

/** A background layer covers the complete window while controls keep their own insets. */
internal class GameMediaSurface(context:Context,val content:View, audioPriority: Int = 1):FrameLayout(context) {
    companion object {
        /** Setting (0/1, off by default): the top screen's art blends into the next instead of cutting. */
        const val ART_CROSSFADE_KEY="topArtCrossfade"
        const val ART_CROSSFADE_MS=220
        fun artCrossfadeEnabled()=TransitionSettings.flag(ART_CROSSFADE_KEY)
    }
    private val backdrop=GameMediaBackground(context)
    private val video=GameVideoPreview(context, cropToFill = true, audioPriority = audioPriority)
    private val shade=View(context).apply { setBackgroundColor(Color.argb(85,0,0,0));visibility=GONE }
    private var playing:Uri?=null
    /** The background currently shown (or being faded to), separate from a transition wrapper. */
    private var shown:Drawable=backdrop
    init {
        background=backdrop
        video.frameReadyChanged={ shade.visibility=if(it)VISIBLE else GONE }
        addView(video,LayoutParams(-1,-1));addView(shade,LayoutParams(-1,-1));addView(content,LayoutParams(-1,-1))
    }
    private fun show(next:Drawable) {
        if(next===shown)return
        val previous=shown
        shown=next
        background=if(artCrossfadeEnabled())CrossfadeDrawable(previous,next,ART_CROSSFADE_MS.toLong()).also { it.start() } else next
    }
    fun update(media:Map<String,Bitmap>,videoUri:Uri?) {
        val next=videoUri.takeIf {
            GameCustomArtwork.HERO !in media && ScreenMediaSettings.background(OrbitThemeArtwork.artworkScreen(context))=="videos"
        }
        show(backdrop)
        backdrop.update(media,next!=null)
        if(next!=playing) { video.show(next,0);playing=next }
        video.visibility=if(next==null)GONE else VISIBLE
        if(next==null)shade.visibility=GONE
    }
    fun pause() { video.stop();playing=null }
    fun showBrowse(drawable:Drawable) {
        pause();video.visibility=GONE;shade.visibility=GONE;show(drawable)
    }
}
