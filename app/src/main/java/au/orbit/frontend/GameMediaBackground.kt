package au.orbit.frontend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Selected game media fills the root, including the area behind the status indicators. */
internal class GameMediaBackground(private val context:Context):Drawable() {
    private var screen=OrbitThemeArtwork.artworkScreen(context)
    private var fallback=OrbitThemeArtwork.background(context)
    private var media=emptyMap<String,Bitmap>()
    private var mode=ScreenMediaSettings.background(screen)
    private var videoPoster:Bitmap?=null
    // Top-screen art crossfade: the previous game's image fading out over the new one.
    private var fadingFrom:Bitmap?=null
    private var fadeProgress=1f
    private var fadeAnimator:android.animation.ValueAnimator?=null
    private fun currentBitmap():Bitmap? {
        val custom=media[GameCustomArtwork.HERO]
        if(custom==null && mode=="off")return null
        return custom ?: media[mode] ?: if(mode=="videos")videoPoster else null
    }
    fun update(media:Map<String,Bitmap>,videoAvailable:Boolean) {
        val before=currentBitmap()
        applyUpdate(media,videoAvailable)
        val after=currentBitmap()
        if(GameMediaSurface.artCrossfadeEnabled() && before!=null && before!==after) {
            fadeAnimator?.cancel()
            fadingFrom=before;fadeProgress=0f
            fadeAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                duration=GameMediaSurface.ART_CROSSFADE_MS.toLong()
                addUpdateListener { fadeProgress=it.animatedValue as Float;invalidateSelf() }
                addListener(object:android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation:android.animation.Animator) { fadingFrom=null;invalidateSelf() }
                })
                start()
            }
        }
    }
    private fun applyUpdate(media:Map<String,Bitmap>,videoAvailable:Boolean) {
        val target=OrbitThemeArtwork.artworkScreen(context)
        val changed=target!=screen
        if(changed) {
            screen=target
            fallback=OrbitThemeArtwork.background(context)
        }
        val next=ScreenMediaSettings.background(screen)
        videoPoster=if(!changed && next=="videos" && videoAvailable) {
            if(mode=="videos")videoPoster else this.media[mode]
        } else null
        this.media=media;mode=next;invalidateSelf()
    }
    override fun draw(canvas:Canvas) {
        drawMedia(canvas)
        val previous=fadingFrom ?: return
        if(fadeProgress>=1f)return
        val layer=canvas.saveLayerAlpha(RectF(bounds),((1f-fadeProgress)*255).toInt())
        OrbitThemeArtwork.drawImage(canvas,previous,RectF(bounds),cover=true)
        canvas.drawColor(Color.argb(85,0,0,0))
        canvas.restoreToCount(layer)
    }
    private fun drawMedia(canvas:Canvas) {
        val custom=media[GameCustomArtwork.HERO]
        if(custom==null && mode=="off") { canvas.drawColor(OrbitThemes.current().background);return }
        val bitmap=custom ?: media[mode] ?: if(mode=="videos")videoPoster else null
        if(bitmap==null) { fallback.bounds=bounds;fallback.draw(canvas) }
        else {
            OrbitThemeArtwork.drawImage(canvas,bitmap,RectF(bounds),cover=true)
            canvas.drawColor(Color.argb(85,0,0,0))
        }
    }
    override fun setAlpha(alpha:Int)=Unit
    override fun setColorFilter(filter:ColorFilter?)=Unit
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
