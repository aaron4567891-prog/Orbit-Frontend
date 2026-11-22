package au.orbit.frontend

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController

internal object FrontendNavigationBar {
    const val HIDE_STATUS_BAR_KEY="hideStatusBar"
    fun statusBarHidden(activity:Activity)=
        UserDataStore.cachedInt(HIDE_STATUS_BAR_KEY,0)!=0 && OrbitThemeArtwork.screen(activity)=="top"
    fun apply(activity:Activity,window:Window?=activity.window) {
        window ?: return
        if(Build.VERSION.SDK_INT>=30) {
            window.insetsController?.apply {
                systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.navigationBars())
                // General → Status bar: the clock and Android icons can be hidden on the top screen.
                if(statusBarHidden(activity))hide(WindowInsets.Type.statusBars()) else show(WindowInsets.Type.statusBars())
            }
        } else {
            val flags=View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.decorView.systemUiVisibility=window.decorView.systemUiVisibility or flags
        }
    }
}
