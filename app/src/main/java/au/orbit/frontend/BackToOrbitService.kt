package au.orbit.frontend

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional physical Back shortcut, scoped to an external app on the bottom display. */
class BackToOrbitService:AccessibilityService() {
    private val captured=mutableSetOf<Int>()
    override fun onAccessibilityEvent(event:AccessibilityEvent?) = Unit
    override fun onInterrupt() { captured.clear() }
    override fun onKeyEvent(event:KeyEvent):Boolean {
        if(event.keyCode !in listOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B))return false
        val key=event.keyCode
        if(event.action==KeyEvent.ACTION_UP && captured.remove(key)) {
            if(!event.isCanceled)DualScreenCoordinator.returnBottomToFrontend()
            return true
        }
        if(event.action!=KeyEvent.ACTION_DOWN)return false
        if(key in captured)return true
        if(!enabled(this) || android.os.Build.VERSION.SDK_INT<30)return false
        val display=DualScreenCoordinator.bottomDisplay() ?: return false
        // Android tells us the active input window; do not infer focus from a visible app.
        val root=rootInActiveWindow ?: return false
        val windowId=root.windowId
        val owner=root.packageName?.toString()
        @Suppress("DEPRECATION")
        root.recycle()
        if(owner==null || owner==packageName || owner=="com.android.systemui")return false
        val byDisplay=windowsOnAllDisplays
        var activeDisplay:Int?=null
        for(i in 0 until byDisplay.size()) {
            if(byDisplay.valueAt(i).any { it.id==windowId && it.isFocused && it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }) {
                activeDisplay=byDisplay.keyAt(i);break
            }
        }
        if(activeDisplay!=display)return false
        captured.add(key)
        return true
    }
    companion object {
        fun enabled(context:Context)=context.getSharedPreferences("back_to_orbit",0).getBoolean("enabled",true)
    }
}
