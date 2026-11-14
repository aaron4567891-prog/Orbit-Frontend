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
    // Top-screen art crossfade: the previous game's image fading out over the new one.
    private var fadingFrom:Bitmap?=null
    private var fadingFromTheme = false
    private var fadingFromPhysicalMedia = false
    private var fadeProgress=1f
    private var fadeAnimator:android.animation.ValueAnimator?=null
    private fun currentBitmap():Bitmap? {
        val custom=media[GameCustomArtwork.HERO]
        if(custom==null && mode=="off")return null
        return custom ?: media[mode]
    }
    private fun isPhysicalMedia() = mode == "physicalmedia" && GameCustomArtwork.HERO !in media

    fun update(media: Map<String, Bitmap>) {
        syncSettings()
        val before=currentBitmap()
        val wasPhysicalMedia = isPhysicalMedia()
        this.media = media
        invalidateSelf()
        val after=currentBitmap()
        if (before === after) return
        val enteringMedia = before == null && after != null
        val leavingMedia = before != null && after == null
        val blendArtwork = mode != "fanart" && before != null &&
            GameMediaSurface.artCrossfadeEnabled(TransitionSettings.View.GAMES)
        fadeAnimator?.cancel()
        if (enteringMedia || leavingMedia || blendArtwork) {
            fadingFrom=before;fadeProgress=0f
            fadingFromTheme = enteringMedia
            fadingFromPhysicalMedia = wasPhysicalMedia
            fadeAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                duration=if (enteringMedia || leavingMedia) 140L else GameMediaSurface.ART_CROSSFADE_MS.toLong()
                addUpdateListener { fadeProgress=it.animatedValue as Float;invalidateSelf() }
                addListener(object:android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation:android.animation.Animator) {
                        fadingFrom=null; fadingFromTheme=false; invalidateSelf()
                    }
                })
                start()
            }
        }
    }
    fun syncSettings(): Boolean {
        val target = OrbitThemeArtwork.artworkScreen(context)
        val next = ScreenMediaSettings.background(target)
        if (target == screen && next == mode) return false
        if (target != screen) fallback = OrbitThemeArtwork.background(context)
        screen = target
        mode = next
        media = emptyMap()
        fadeAnimator?.cancel()
        fadingFrom = null
        fadingFromTheme = false
        invalidateSelf()
        return true
    }
    override fun draw(canvas:Canvas) {
        drawMedia(canvas)
        if (fadeProgress >= 1f || (fadingFrom == null && !fadingFromTheme)) return
        val layer=canvas.saveLayerAlpha(RectF(bounds),((1f-fadeProgress)*255).toInt())
        if (fadingFromTheme) {
            if (mode=="off") canvas.drawColor(OrbitThemes.current().background) else drawFallback(canvas)
        } else fadingFrom?.let { drawArtwork(canvas, it, fadingFromPhysicalMedia) }
        canvas.restoreToCount(layer)
    }
    private fun drawMedia(canvas:Canvas) {
        val custom=media[GameCustomArtwork.HERO]
        if(custom==null && mode=="off") { canvas.drawColor(OrbitThemes.current().background);return }
        val bitmap = currentBitmap()
        if(bitmap==null) drawFallback(canvas)
        else drawArtwork(canvas, bitmap, isPhysicalMedia())
    }
    private fun drawFallback(canvas: Canvas) {
        fallback.bounds = bounds
        fallback.draw(canvas)
    }
    private fun drawArtwork(canvas: Canvas, bitmap: Bitmap, physicalMedia: Boolean) {
        // Disc and cartridge art can be transparent. Keep the theme visible through the cutout.
        if (physicalMedia) {
            drawFallback(canvas)
        }
        OrbitThemeArtwork.drawImage(canvas, bitmap, RectF(bounds), cover = true)
        if (!physicalMedia) canvas.drawColor(Color.argb(85, 0, 0, 0))
    }
    override fun setAlpha(alpha:Int)=Unit
    override fun setColorFilter(filter:ColorFilter?)=Unit
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
