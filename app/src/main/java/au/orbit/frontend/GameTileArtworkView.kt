package au.orbit.frontend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView

internal fun View.setGameTileSelected(selected: Boolean) {
    isSelected = selected
    ((this as? ViewGroup)?.getChildAt(1) as? GameTileArtworkView)?.setSpinSelected(selected)
}

/** Rotates the artwork layer while its selected tile is visible. */
internal class GameTileArtworkView(context: Context) : ImageView(context) {
    private var fixedBoundsUpdate = false
    private var disc: DiscArtwork.Center? = null
    private var spinSelected = false
    private var animation: ValueAnimator? = null
    private var pendingStart: Runnable? = null
    private var angle = 0f
    private var discCenterX = 0f
    private var discCenterY = 0f

    // Wheel cells are measured to a fixed size. Changing the drawable only changes its fit matrix.
    fun updateWheelDrawable(drawable: Drawable?) {
        fixedBoundsUpdate = isLaidOut && !isLayoutRequested && width > 0 && height > 0
        try {
            setImageDrawable(drawable)
        } finally {
            fixedBoundsUpdate = false
        }
    }

    override fun requestLayout() {
        if (!fixedBoundsUpdate) super.requestLayout()
    }

    fun showDisc(center: DiscArtwork.Center?) {
        disc = center
        updatePivot()
        updateSpin()
    }

    fun setSpinSelected(selected: Boolean) {
        spinSelected = selected
        updateSpin()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateSpin() else stopSpin()
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updatePivot()
    }

    override fun onDraw(canvas: Canvas) {
        if (angle == 0f) {
            super.onDraw(canvas)
        } else {
            val saved = canvas.save()
            canvas.rotate(angle, discCenterX, discCenterY)
            super.onDraw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    private fun scheduleSpin() {
        val start = object : Runnable {
            override fun run() {
                if (pendingStart !== this) return
                pendingStart = null
                if (!canSpin()) return
                animation = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 9000L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val next = it.animatedValue as Float
                        if (angle != next) {
                            angle = next
                            invalidate()
                        }
                    }
                    start()
                }
            }
        }
        pendingStart = start
        // Commit the first stationary tile before queuing rotation frames.
        if (isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback { postOnAnimation(start) }
            invalidate()
        } else postOnAnimation(start)
    }

    private fun updatePivot() {
        val center = disc ?: return
        val artwork = drawable ?: return
        val point = floatArrayOf(artwork.intrinsicWidth * center.x, artwork.intrinsicHeight * center.y)
        imageMatrix.mapPoints(point)
        discCenterX = paddingLeft + point[0]
        discCenterY = paddingTop + point[1]
    }

    private fun canSpin() = disc != null && spinSelected && isShown && isAttachedToWindow &&
        windowVisibility == View.VISIBLE && ValueAnimator.areAnimatorsEnabled()

    private fun updateSpin() {
        if (!canSpin()) stopSpin()
        else if (animation == null && pendingStart == null) scheduleSpin()
    }

    private fun stopSpin() {
        pendingStart?.let(::removeCallbacks)
        pendingStart = null
        animation?.cancel()
        animation = null
        if (angle != 0f) {
            angle = 0f
            invalidate()
        }
    }
}
