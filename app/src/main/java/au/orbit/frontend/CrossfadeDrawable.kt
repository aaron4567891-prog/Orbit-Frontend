package au.orbit.frontend

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Blends [from] into [to] over [durationMs]. It fades [from] out with a layer on top of [to] instead
 * of relying on setAlpha, which Orbit's background drawables do not implement.
 */
internal class CrossfadeDrawable(private val from:Drawable,private val to:Drawable,durationMs:Long):Drawable(),Drawable.Callback {
    private var progress=0f
    private val animator=ValueAnimator.ofFloat(0f,1f).apply {
        duration=durationMs
        addUpdateListener { progress=it.animatedValue as Float;invalidateSelf() }
    }
    init { from.callback=this;to.callback=this }

    fun start() { animator.start() }

    override fun onBoundsChange(bounds:Rect) { from.bounds=bounds;to.bounds=bounds }

    override fun draw(canvas:Canvas) {
        to.draw(canvas)
        if(progress>=1f)return
        val layer=canvas.saveLayerAlpha(RectF(bounds),((1f-progress)*255).toInt())
        from.draw(canvas)
        canvas.restoreToCount(layer)
    }

    override fun invalidateDrawable(who:Drawable) { invalidateSelf() }
    override fun scheduleDrawable(who:Drawable,what:Runnable,`when`:Long) { scheduleSelf(what,`when`) }
    override fun unscheduleDrawable(who:Drawable,what:Runnable) { unscheduleSelf(what) }
    override fun setAlpha(alpha:Int)=Unit
    override fun setColorFilter(filter:ColorFilter?)=Unit
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
