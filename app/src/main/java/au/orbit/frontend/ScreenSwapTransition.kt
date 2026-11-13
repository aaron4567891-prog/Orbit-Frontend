package au.orbit.frontend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView

/** Both animations stay in their original windows; no task/display transitions are involved. */
object ScreenSwapTransition {
    fun capture(view:View):Bitmap=Bitmap.createBitmap(view.width.coerceAtLeast(1),view.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
    fun play(root:ViewGroup,previous:Bitmap,direction:Float) {
        val incoming=root.getChildAt(0) ?: return
        val cover=ImageView(root.context).apply {
            scaleType=ImageView.ScaleType.FIT_XY;setImageBitmap(previous)
        }
        root.addView(cover,ViewGroup.LayoutParams(-1,-1))
        root.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                incoming.translationY=-direction*root.height
                incoming.animate().translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
                cover.animate().translationY(direction*root.height).setDuration(280).setInterpolator(DecelerateInterpolator())
                    .withEndAction { root.removeView(cover);cover.setImageDrawable(null) }.start()
                return true
            }
        })
        root.requestLayout()
    }
}
