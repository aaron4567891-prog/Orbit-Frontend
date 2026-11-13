package au.orbit.frontend

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout

/** Presents the manual preview over the artwork and controls on the same display. */
internal class FullScreenGameVideo(
    private val activity: Activity,
    val uri: Uri,
    dismiss: () -> Unit,
    failed: () -> Unit
) : FrameLayout(activity) {
    private var restoreNonFocusable = false
    private var restoreBars = 0
    private var restoreStatusBar = false
    private val video = GameVideoPreview(activity).apply {
        frameReadyChanged = { ready ->
            if (ready && this@FullScreenGameVideo.alpha == 0f) {
                hideSystemBars()
                this@FullScreenGameVideo.alpha = 1f
            }
        }
        playbackFailed = failed
        playbackInterrupted = dismiss
    }

    init {
        alpha = 0f
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnClickListener { dismiss() }
        isFocusable = false
        contentDescription = "Full-screen game video. Tap to close."
    }

    fun open() {
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        video.show(uri, 0)
    }

    fun close() {
        video.stop()
        (parent as? FrameLayout)?.removeView(this)
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 30) {
            if (restoreBars != 0) window.insetsController?.show(restoreBars)
        } else if (restoreStatusBar) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        if (restoreNonFocusable) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        restoreBars = 0
        restoreStatusBar = false
        restoreNonFocusable = false
    }

    private fun hideSystemBars() {
        val window = activity.window
        // The companion normally leaves controller focus with the library. Its
        // window needs to be focusable to control its display's system bars.
        restoreNonFocusable = window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        if (restoreNonFocusable) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (Build.VERSION.SDK_INT >= 30) {
            val statusBars = WindowInsets.Type.statusBars()
            val navigationBars = WindowInsets.Type.navigationBars()
            val insets = rootWindowInsets
            if (insets?.isVisible(statusBars) != false) restoreBars = statusBars
            if (insets?.isVisible(navigationBars) == true) restoreBars = restoreBars or navigationBars
            window.insetsController?.hide(statusBars or navigationBars)
        } else {
            restoreStatusBar = window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }
}
