package au.orbit.frontend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import java.lang.ref.WeakReference

/** Maintains the second frontend activity while a frontend host is visible.
 * Existing panels are left in their task, so another app can use that display.
 */
object DualScreenCoordinator : DisplayManager.DisplayListener {
    private val handler = Handler(Looper.getMainLooper())
    private val hosts = linkedSetOf<Activity>()
    private var panel = WeakReference<MainActivity>(null)
    private var manager: DisplayManager? = null
    private var launching = false
    private var swapping = false
    private var exiting = false
    private var mainHost=WeakReference<MainActivity>(null)
    private val reconcile = Runnable { ensurePanel() }
    private var mediaRefreshScheduled=false
    private var forceMediaRefresh=false
    private var temporaryLibraryOnBottom:Boolean?=null
    private var gameReturnDisplay:Int?=null
    private const val THEME_LIBRARY_SCREEN="themeLibraryScreen"
    private const val LEGACY_LIBRARY_ON_BOTTOM="libraryOnBottom"

    private fun layoutPreferences(context:Context)=context.getSharedPreferences("dual_screen",Context.MODE_PRIVATE)
    private fun preferredBottom(context:Context)=temporaryLibraryOnBottom
        ?: (layoutPreferences(context).getString(THEME_LIBRARY_SCREEN,"top")=="bottom")

    internal fun hasLegacyBottomLayout(context:Context)=layoutPreferences(context).getBoolean(LEGACY_LIBRARY_ON_BOTTOM,false)
    internal fun cacheThemeLayout(context:Context,screen:String) {
        val preferences=layoutPreferences(context)
        if(preferences.getString(THEME_LIBRARY_SCREEN,null)!=screen || preferences.contains(LEGACY_LIBRARY_ON_BOTTOM)) {
            preferences.edit().putString(THEME_LIBRARY_SCREEN,screen).remove(LEGACY_LIBRARY_ON_BOTTOM).apply()
        }
    }

    internal fun themeApplied() {
        temporaryLibraryOnBottom=null
        ensurePanel()
    }

    /** On a fresh launch, create the library directly on its preferred display. */
    fun startLibraryOnBottom(caller:MainActivity):Boolean {
        if(!preferredBottom(caller) || caller.windowManager.defaultDisplay.displayId!=Display.DEFAULT_DISPLAY)return false
        val bottom=DualScreenManager(caller).displays().firstOrNull {
            it.displayId!=Display.DEFAULT_DISPLAY && it.state!=Display.STATE_OFF
        } ?: return false
        val existing=mainHost.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
        if(existing!=null) {
            if(existing.windowManager.defaultDisplay.displayId!=bottom.displayId)return false
            val requested=caller.intent.getIntExtra("navigationTab",-1)
            if(requested>=0)openHostTab(caller,requested)
            return true
        }
        if(panel.get()?.let { !it.isDestroyed && !it.isFinishing && it.windowManager.defaultDisplay.displayId==bottom.displayId }==true)return false
        val intent=Intent(caller,CompanionActivity::class.java).putExtra("companionRole",false).putExtra("startLibrary",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if(caller.intent.hasExtra("navigationTab"))intent.putExtra("navigationTab",caller.intent.getIntExtra("navigationTab",0))
        return runCatching {
            caller.startActivity(intent,android.app.ActivityOptions.makeCustomAnimation(caller,0,0).setLaunchDisplayId(bottom.displayId).toBundle())
        }.isSuccess
    }

    internal fun libraryReady()=schedule()
    internal fun hasLibraryHost()=mainHost.get()?.let { !it.showingCompanion && !it.isDestroyed && !it.isFinishing }==true
    fun mediaSelection(): Pair<SystemSpec,Game>? = mainHost.get()?.companionSelection()
    internal fun prepareGameArtwork(host:MainActivity,system:SystemSpec,game:Game) {
        if(mainHost.get()===host)panel.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
            ?.preloadCompanionGame(system,game)
    }
    internal fun showGameArtwork(host:MainActivity,system:SystemSpec,game:Game) {
        if(mainHost.get()===host)panel.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
            ?.showCompanionGame(system,game)
    }
    internal fun toggleCompanionVideo(system:SystemSpec,game:Game):Boolean = panel.get()
        ?.takeIf { it.showingCompanion && !it.isDestroyed && !it.isFinishing }
        ?.toggleCompanionVideo(system,game)==true
    internal fun finishCompanionVideo() { panel.get()?.finishCompanionVideo() }
    internal val companionVideoActive get()=panel.get()?.companionVideoActive==true
    fun browseSelection():CompanionBrowse?=mainHost.get()?.companionBrowse()
    internal fun firstGameForArtwork(system: SystemSpec) = mainHost.get()?.firstGameForArtwork(system)
    fun companionDisplay():Int?=panel.get()?.takeIf { it.showingCompanion && !it.isDestroyed && !it.isFinishing }?.windowManager?.defaultDisplay?.displayId
    internal fun companionOnDisplay(displayId:Int):MainActivity?=panel.get()?.takeIf {
        it.showingCompanion && !it.isDestroyed && !it.isFinishing &&
            it.windowManager.defaultDisplay.displayId==displayId
    }
    internal fun launchSource(activity:Activity,displayId:Int):Activity {
        if(displayId!=Display.DEFAULT_DISPLAY || activity.windowManager.defaultDisplay.displayId==displayId)return activity
        return companionOnDisplay(displayId) ?: activity
    }
    internal fun gameLaunched(host:Activity,displayId:Int) {
        gameReturnDisplay=displayId.takeIf { mainHost.get()===host && companionOnDisplay(it)!=null }
    }
    internal fun companionResumed(activity:MainActivity) {
        if(gameReturnDisplay!=activity.windowManager.defaultDisplay.displayId)return
        gameReturnDisplay=null
        restoreHostFocus()
    }
    private fun bottomHost()=listOfNotNull(mainHost.get(),panel.get()).firstOrNull {
        !it.isDestroyed && !it.isFinishing && it.windowManager.defaultDisplay.displayId!=Display.DEFAULT_DISPLAY
    }
    fun bottomDisplay():Int?=bottomHost()?.windowManager?.defaultDisplay?.displayId
    internal fun openBottomSettings(bottom: MainActivity) {
        val host = mainHost.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
        host.showBottomScreenSettings(bottom)
    }
    fun refreshMedia(refresh:Boolean=false) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { handler.post { refreshMedia(refresh) };return }
        forceMediaRefresh=forceMediaRefresh || refresh
        if(mediaRefreshScheduled)return
        mediaRefreshScheduled=true
        // Page replacement can publish several temporary focus states in one UI turn.
        android.view.Choreographer.getInstance().postFrameCallback {
            mediaRefreshScheduled=false
            val force=forceMediaRefresh;forceMediaRefresh=false
            panel.get()?.takeIf { !it.isDestroyed && !it.isFinishing }?.refreshCompanionMedia(force)
        }
    }
    internal fun refreshBottomControls() { bottomHost()?.refreshBottomControls() }
    fun refreshNavigationBars() { hosts.forEach { FrontendNavigationBar.apply(it) } }
    fun returnBottomToFrontend() {
        val bottom=bottomHost() ?: return
        BottomAppDrawer.close()
        runCatching {
            bottom.startActivity(Intent(bottom,bottom.javaClass)
                .putExtra("companionRole",bottom.showingCompanion)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(bottom,0,0)
                    .setLaunchDisplayId(bottom.windowManager.defaultDisplay.displayId).toBundle())
            bottom.overridePendingTransition(0,0)
        }
        refreshMedia();restoreHostFocus()
    }
    fun attach(host: Activity) {
        if(host.isFinishing)return
        exiting=false
        hosts.add(host)
        if(host is MainActivity) {
            if(host.showingCompanion)panel=WeakReference(host) else {
                if(panel.get()===host)panel.clear()
                mainHost=WeakReference(host)
            }
        }
        if (manager == null) {
            manager = host.applicationContext.getSystemService(DisplayManager::class.java)
            manager?.registerDisplayListener(this, handler)
        }
        schedule()
    }
    fun detach(host: Activity) {
        hosts.remove(host)
        if (hosts.isEmpty()) {
            manager?.unregisterDisplayListener(this); manager = null
            handler.removeCallbacks(reconcile)
        }
    }
    fun panelCreated(activity: MainActivity) {
        panel = WeakReference(activity); launching = false
        // Starting an activity on another display changes Android's focused display,
        // even when its window cannot receive keys. Return focus to the original host
        // once the panel exists; this reuses its activity without rebuilding the UI.
        if(!swapping)restoreHostFocus()
    }
    fun restoreHostFocus() {
        handler.post {
            val host = mainHost.get()?.takeIf { it in hosts && !it.isFinishing && !it.isDestroyed } ?: return@post
            runCatching {
                host.startActivity(Intent(host.intent).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    android.app.ActivityOptions.makeCustomAnimation(host,0,0).setLaunchDisplayId(host.windowManager.defaultDisplay.displayId).toBundle())
                host.overridePendingTransition(0,0)
            }
        }
    }
    fun openHostTab(caller:Activity,tab:Int) {
        val host=mainHost.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val display=host?.windowManager?.defaultDisplay?.displayId
            ?: DualScreenManager(caller).displays().firstOrNull {
                it.displayId!=caller.windowManager.defaultDisplay.displayId && it.state!=Display.STATE_OFF
            }?.displayId ?: Display.DEFAULT_DISPLAY
        runCatching {
            caller.startActivity(Intent(caller,host?.javaClass ?: MainActivity::class.java).putExtra("navigationTab",tab).putExtra("companionRole",false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                DualScreenManager(caller).options(display))
        }.onFailure {
            android.widget.Toast.makeText(caller,"Could not open Orbit: ${it.message}",android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun assignRoles(source:MainActivity,target:MainActivity) {
        gameReturnDisplay=null
        target.copyLibraryFrom(source)
        source.showCompanionScreen()
        mainHost=WeakReference(target);panel=WeakReference(source)
    }
    /** Exchange content in fixed display windows so Android cannot add a task slide. */
    fun swapScreens(caller:Activity) {
        if(swapping)return
        val source=mainHost.get()?.takeIf { it.canPlaceLibrary() && !it.showingCompanion && !it.isFinishing && !it.isDestroyed }
        val target=panel.get()?.takeIf { it.showingCompanion && !it.isFinishing && !it.isDestroyed }
        if(source==null || target==null) {
            android.widget.Toast.makeText(caller,"Both Orbit screens need to be open before swapping.",android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val sourceRoot=source.findViewById<android.view.ViewGroup>(android.R.id.content)
        val targetRoot=target.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frames=runCatching { ScreenSwapTransition.capture(sourceRoot) to ScreenSwapTransition.capture(targetRoot) }
        if(frames.isFailure) {
            android.widget.Toast.makeText(caller,"Could not prepare the screen swap.",android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        swapping=true;handler.removeCallbacks(reconcile)
        val direction=if(source.windowManager.defaultDisplay.displayId==Display.DEFAULT_DISPLAY)1f else -1f
        temporaryLibraryOnBottom=target.windowManager.defaultDisplay.displayId!=Display.DEFAULT_DISPLAY
        assignRoles(source,target)
        ScreenSwapTransition.play(sourceRoot,frames.getOrThrow().first,direction)
        ScreenSwapTransition.play(targetRoot,frames.getOrThrow().second,-direction)
        handler.postDelayed({ swapping=false;schedule();restoreHostFocus() },300)
    }

    fun panelDestroyed(activity: MainActivity) {
        if (panel.get() === activity) panel.clear()
        launching = false; schedule()
    }
    private fun schedule() {
        handler.removeCallbacks(reconcile)
        if (hosts.isNotEmpty()) handler.postDelayed(reconcile, 200)
    }
    private fun ensurePanel() {
        if(exiting)return
        if(swapping)return
        val host = mainHost.get()?.takeIf { !it.showingCompanion && !it.isFinishing && !it.isDestroyed } ?: return
        val hostDisplay=host.windowManager.defaultDisplay.displayId
        val displays=DualScreenManager(host).displays().filter { it.state!=Display.STATE_OFF }
        val other=displays.firstOrNull { it.displayId!=hostDisplay }
        val existing = panel.get()?.takeIf { it.showingCompanion }
        if (other == null) { existing?.finish(); return }
        if (existing != null && !existing.isFinishing && !existing.isDestroyed && existing.windowManager.defaultDisplay.displayId == other.displayId) {
            val bottom=displays.firstOrNull { it.displayId!=Display.DEFAULT_DISPLAY }
            val preferred=if(preferredBottom(host))bottom?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY
            if(other.displayId==preferred && host in hosts && host.canPlaceLibrary()) {
                assignRoles(host,existing)
                restoreHostFocus()
                schedule()
            }
            return
        }
        if (launching) return
        launching = true
        runCatching {
            val component=if(host is CompanionActivity)MainActivity::class.java else CompanionActivity::class.java
            host.startActivity(Intent(host,component).putExtra("companionRole",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), DualScreenManager(host).options(other.displayId))
        }.onFailure {
            launching = false
            android.widget.Toast.makeText(host, "Could not open second screen: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
        }
        // Allow a later display/lifecycle event to retry if Android never creates the activity.
        handler.postDelayed({ launching = false }, 2000)
    }
    fun exit() {
        exiting=true;handler.removeCallbacks(reconcile)
        gameReturnDisplay=null
        BottomAppDrawer.close()
        val closing=hosts.toList()
        hosts.clear();mainHost.clear();panel.clear();launching=false;temporaryLibraryOnBottom=null
        manager?.unregisterDisplayListener(this);manager=null
        closing.forEach { if(!it.isDestroyed)it.finishAndRemoveTask() }
    }
    override fun onDisplayAdded(displayId: Int) = schedule()
    override fun onDisplayRemoved(displayId: Int) = schedule()
    override fun onDisplayChanged(displayId: Int) = schedule()
}
