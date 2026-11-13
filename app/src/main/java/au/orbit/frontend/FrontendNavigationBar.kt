package au.orbit.frontend

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

internal object FrontendNavigationBar {
    fun apply(activity:Activity,window:Window?=activity.window) {
        window ?: return
        if(Build.VERSION.SDK_INT>=30) {
            window.insetsController?.apply {
                systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.navigationBars())
            }
        } else {
            val flags=View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility=window.decorView.systemUiVisibility or flags
        }
    }
}
