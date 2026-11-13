package au.orbit.frontend

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent

/** Compatibility entry point; tab navigation embeds the same panel in MainActivity. */
class AppsActivity : Activity() {
    private lateinit var panel: AppsPanel
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        panel = AppsPanel(this, { NavigationTabs.openMain(this, it) }, { finish() })
        setContentView(panel.view)
    }
    override fun onStart() { super.onStart(); DualScreenCoordinator.attach(this) }
    override fun onStop() { DualScreenCoordinator.detach(this); super.onStop() }
    override fun onResume() { super.onResume(); FrontendRefreshRate.apply(this); panel.resume() }
    override fun onDestroy() { panel.destroy(); super.onDestroy() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = NavigationTabs.direction(event.keyCode)
        if (direction != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) NavigationTabs.openMain(this, if (direction < 0) 0 else 2)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && panel.onKeyDown(event.keyCode, event)) return true
        return super.dispatchKeyEvent(event)
    }
}
