package au.orbit.frontend

import android.content.Context
import android.net.Uri
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/** Blend to the next clip on the bottom screen once its first frame is ready. */
internal class LibraryVideoPreview(
    context: Context,
    private val cropToFill: Boolean = false,
    private val audioPriority: Int = 1
) : FrameLayout(context) {
    private val keepFrame = OrbitThemeArtwork.screen(context) == "bottom"
    private var active: GameVideoPreview? = null
    private var held: GameVideoPreview? = null
    private val blendEase = DecelerateInterpolator()
    var frameReadyChanged: ((Boolean) -> Unit)? = null

    fun show(uri: Uri?, delay: Long) {
        if (uri == null) {
            stop()
            return
        }
        val current = active
        if (!keepFrame && current != null) {
            current.show(uri, delay)
            return
        }
        active = null
        current?.animate()?.cancel()
        val keepCurrent = current?.hasVisibleFrame == true &&
            (held?.hasVisibleFrame != true || current.alpha >= 0.5f)
        if (keepFrame && keepCurrent && current != null) {
            removeHeld()
            held = current
            current.alpha = 1f
            current.freezeFrame()
        } else if (current != null) {
            removeView(current)
        }

        val next = GameVideoPreview(context, cropToFill, audioPriority, waitForTextureFrame = keepFrame)
        active = next
        next.frameReadyChanged = { ready ->
            if (active === next) {
                if (ready) blendTo(next)
                updateFrameVisibility()
            } else if (held === next) {
                updateFrameVisibility()
            }
        }
        next.playbackFailed = {
            if (active === next) {
                removeHeld()
                updateFrameVisibility()
            }
        }
        next.playbackInterrupted = next.playbackFailed
        addView(next, LayoutParams(-1, -1))
        next.show(uri, delay)
    }

    private fun blendTo(next: GameVideoPreview) {
        if (!keepFrame) {
            removeHeld()
            return
        }
        val previous = held
        if (previous?.hasVisibleFrame != true) {
            removeHeld()
        }
        // Fade in over the held video frame or the existing artwork when there is no clip.
        next.alpha = 0f
        next.animate().alpha(1f).setDuration(140L).setInterpolator(blendEase).withEndAction {
            if (active === next && held === previous) removeHeld()
        }.start()
    }

    private fun updateFrameVisibility() {
        frameReadyChanged?.invoke(active?.hasVisibleFrame == true || held?.hasVisibleFrame == true)
    }

    private fun removeHeld() {
        val previous = held ?: return
        held = null
        previous.animate().cancel()
        removeView(previous)
    }

    fun fadeOut() {
        val current = active
        val outgoing = current?.takeIf { it.hasVisibleFrame && (held?.hasVisibleFrame != true || it.alpha >= 0.5f) }
            ?: held?.takeIf { it.hasVisibleFrame }
        if (outgoing == null) {
            stop()
            return
        }
        active = null
        if (held !== outgoing) removeHeld()
        held = outgoing
        if (current != null && current !== outgoing) removeView(current)
        outgoing.animate().cancel()
        outgoing.freezeFrame()
        outgoing.animate().alpha(0f).setDuration(140L).setInterpolator(blendEase).withEndAction {
            if (active == null && held === outgoing) {
                removeHeld()
                updateFrameVisibility()
            }
        }.start()
    }

    fun stop() {
        val current = active
        active = null
        current?.animate()?.cancel()
        if (current != null) removeView(current)
        removeHeld()
        updateFrameVisibility()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
