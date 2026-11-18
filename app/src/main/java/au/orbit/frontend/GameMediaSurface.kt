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
        fun artCrossfadeEnabled(view: TransitionSettings.View) = TransitionSettings.flag(ART_CROSSFADE_KEY, view)
    }
    private val backdrop=GameMediaBackground(context)
    private val video=LibraryVideoPreview(context, cropToFill = true, audioPriority = audioPriority)
    private val shade=View(context).apply { setBackgroundColor(Color.argb(32,0,0,0));visibility=GONE }
    private var playing:Uri?=null
    /** The background currently shown (or being faded to), separate from a transition wrapper. */
    private var shown:Drawable=backdrop
    init {
        background=backdrop
        video.frameReadyChanged = { ready ->
            shade.animate().cancel()
            if (ready && shade.visibility != VISIBLE) {
                shade.alpha = 0f
                shade.visibility = VISIBLE
                shade.animate().alpha(1f).setDuration(140L).start()
            } else if (!ready) {
                shade.visibility = GONE
                shade.alpha = 1f
            } else if (playing != null) shade.animate().alpha(1f).setDuration(140L).start()
        }
        addView(video,LayoutParams(-1,-1));addView(shade,LayoutParams(-1,-1));addView(content,LayoutParams(-1,-1))
    }
    private fun show(next: Drawable, animationView: TransitionSettings.View?) {
        if(next===shown)return
        val previous=shown
        shown=next
        background = if (animationView != null && artCrossfadeEnabled(animationView)) {
            CrossfadeDrawable(previous, next, ART_CROSSFADE_MS.toLong()).also { it.start() }
        } else next
    }
    fun prepareGameView() {
        if (!backdrop.syncSettings()) return
        // A cached background or fade can still contain the previous media choice.
        pause()
        video.visibility = GONE
        shade.visibility = GONE
        if (shown === backdrop) background = backdrop
    }

    fun update(media:Map<String,Bitmap>,videoUri:Uri?,hideBackground:Boolean) {
        prepareGameView()
        val next=videoUri.takeIf {
            !hideBackground && GameCustomArtwork.HERO !in media && ScreenMediaSettings.background(OrbitThemeArtwork.artworkScreen(context))=="videos"
        }
        show(backdrop, TransitionSettings.View.GAMES)
        backdrop.update(if (hideBackground) emptyMap() else media)
        if (next != playing) {
            playing = next
            if (next == null) {
                shade.animate().alpha(0f).setDuration(140L).start()
                video.fadeOut()
            } else {
                video.visibility = VISIBLE
                video.show(next, 0)
            }
        }
    }
    fun pause() { video.stop();playing=null }
    fun setWheelScrolling(scrolling: Boolean) = video.setWheelScrolling(scrolling)
    fun showBrowse(drawable: Drawable, systemArtwork: Boolean = false) {
        pause();video.visibility=GONE;shade.visibility=GONE
        show(drawable, TransitionSettings.View.SYSTEMS.takeIf { systemArtwork })
    }
}
