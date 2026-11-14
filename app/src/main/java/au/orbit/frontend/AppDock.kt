package au.orbit.frontend

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Portable dock preferences shared by the bottom screen and its settings. */
object AppDock {
    fun enabled(context:Context)=UserDataStore.cachedInt("appDockEnabled",1)==1
    fun slots(context:Context)=UserDataStore.cachedInt("appDockSlots",5).coerceIn(1,5)
    fun component(context:Context,slot:Int)=UserDataStore.cachedString("settings","appDockSlot$slot")
        ?.let(ComponentName::unflattenFromString)
    fun label(context:Context,slot:Int):String {
        val component=component(context,slot) ?: return "Empty"
        return runCatching { context.packageManager.getActivityInfo(component,0).loadLabel(context.packageManager).toString() }.getOrDefault("App unavailable")
    }
    fun choose(activity:Activity,slot:Int,changed:()->Unit={}) {
        BottomAppDrawer.show(activity) { component ->
            UserDataStore.put(activity,"settings","appDockSlot$slot",component?.flattenToString())
            changed()
        }
    }
    fun launch(activity:Activity,slot:Int) {
        val component=component(activity,slot) ?: return choose(activity,slot)
        launchApp(activity,component)
    }
    fun launchApp(activity:Activity,component:ComponentName,screen:String?=null):Boolean {
        return runCatching {
            val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            // Keep Orbit below ordinary app activities so Android Back returns here.
            // Apps declaring singleTask still control their own task and exit behavior.
            val current=activity.windowManager.defaultDisplay.displayId
            val display=screen?.let { DualScreenManager(activity).displayForScreen(it) } ?: current
            val source=DualScreenCoordinator.launchSource(activity,display)
            if(display!=current)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            check(source.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(source,display,intent)) {
                "This app cannot open on the selected screen."
            }
            source.startActivity(intent,DualScreenManager(source).options(display))
        }.onFailure { Toast.makeText(activity,"Unable to open app: ${it.message}",Toast.LENGTH_LONG).show() }.isSuccess
    }
}

/** Touch controls leave physical controller focus with the launcher or running game. */
class AppDockView(private val activity:Activity):LinearLayout(activity) {
    private var signature=""
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER;setPadding(dp(8),dp(4),dp(8),dp(4))
        background=GradientDrawable().apply { setColor(Color.rgb(235,239,245));cornerRadius=dp(18).toFloat() }
        elevation=dp(6).toFloat()
        isFocusable=false
        refresh()
    }
    fun refresh() {
        val count=AppDock.slots(activity)
        val next="${AppDock.enabled(activity)}/$count/"+(0 until count).joinToString { AppDock.component(activity,it)?.flattenToString().orEmpty() }
        if(next==signature)return
        signature=next
        visibility=if(AppDock.enabled(activity))View.VISIBLE else View.GONE
        removeAllViews()
        if(visibility!=View.VISIBLE)return
        fun shortcut(label:String,symbol:String,action:()->Unit) {
            addView(TextView(activity).apply {
                text=symbol;textSize=28f;gravity=Gravity.CENTER;setTextColor(Color.rgb(91,95,237))
                contentDescription=label;tooltipText=label;isFocusable=false
                setOnClickListener { action() }
            },LayoutParams(dp(44),dp(52)))
        }
        shortcut("Android apps","⠿") { BottomAppDrawer.show(activity) }
        repeat(count) { slot ->
            val component=AppDock.component(activity,slot)
            val label=if(component==null)"Add app to slot ${slot+1}" else AppDock.label(activity,slot)
            val cell=FrameLayout(activity).apply {
                isFocusable=false;contentDescription=label;tooltipText=label
                setOnClickListener { if(component==null)AppDock.choose(activity,slot,::refresh) else AppDock.launch(activity,slot) }
                setOnLongClickListener { AppDock.choose(activity,slot,::refresh);true }
            }
            val icon=runCatching { component?.let { activity.packageManager.getActivityIcon(it) } }.getOrNull()
            if(icon!=null)cell.addView(ImageView(activity).apply {
                setImageDrawable(icon);scaleType=ImageView.ScaleType.FIT_CENTER
            },FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
            else cell.addView(TextView(activity).apply {
                text="+";textSize=24f;gravity=Gravity.CENTER;setTextColor(Color.rgb(140,148,166))
                background=GradientDrawable().apply {
                    setColor(Color.rgb(222,228,237));shape=GradientDrawable.OVAL
                }
            },FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER))
            addView(cell,LayoutParams(dp(44),dp(52)))
        }

    }
}

/** Centers the app dock on the physical bottom screen in either layout. */
internal class BottomScreenControls(activity: MainActivity) : FrameLayout(activity) {
    private val dock = AppDockView(activity)

    init {
        addView(dock, LayoutParams(-2, -2, Gravity.CENTER))
        refresh()
    }

    fun refresh() {
        dock.refresh()
        minimumHeight = if (dock.visibility == View.VISIBLE) {
            (60 * resources.displayMetrics.density).toInt()
        } else 0
    }
}
