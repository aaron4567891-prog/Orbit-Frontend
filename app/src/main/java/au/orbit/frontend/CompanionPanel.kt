package au.orbit.frontend

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.WindowInsets
import android.widget.*

/** Reusable companion content; both physical display windows can host it. */
class CompanionPanel(private val activity:Activity) {
    private val content:LinearLayout
    internal val view:GameMediaSurface
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var status:TextView
    private val bottomControls=if(OrbitThemeArtwork.screen(activity)=="bottom")BottomScreenControls(activity) else null
    private val mediaHost=FrameLayout(activity)
    private val browse=CompanionBrowseView(activity)
    private lateinit var heading:TextView
    private var artwork:GameArtworkPanel?=null
    private var mediaActive=false
    private val warmArtwork=Runnable { if(mediaActive)prepareArtwork() }
    internal val manualVideoActive get()=artwork?.manualVideoActive==true
    fun toggleVideoPreview(system:SystemSpec,game:Game):Boolean {
        if(!mediaActive || DualScreenCoordinator.mediaSelection()!=(system to game))return false
        refreshMedia()
        val target=artwork ?: return false
        target.toggleVideoPreview(system,game)
        return true
    }
    fun finishVideoPreview() { artwork?.finishVideoPreview() }

    private fun prepareArtwork():GameArtworkPanel = artwork ?: GameArtworkPanel(
        activity,coverOnly=true, audioPriority = 0, mediaMode="screen"
    ).also {
        it.backgroundMediaChanged=view::update
        it.backgroundMediaPaused=view::pause
        mediaHost.addView(it.view,FrameLayout.LayoutParams(-1,-1))
        artwork=it
    }

    fun refreshMedia(refresh:Boolean=false) {
        if(!mediaActive)return
        val selection=DualScreenCoordinator.mediaSelection()
        if(selection==null) {
            artwork?.pause()
            val selected=DualScreenCoordinator.browseSelection()
            browse.visibility=if(selected!=null)android.view.View.VISIBLE else android.view.View.GONE
            if(selected!=null)browse.show(selected,refresh) else {
                browse.clear()
                view.showBrowse(OrbitThemeArtwork.background(activity))
            }
            mediaHost.visibility=android.view.View.GONE
            status.visibility=if(selected==null)android.view.View.VISIBLE else android.view.View.GONE
            heading.visibility=if(selected==null)android.view.View.VISIBLE else android.view.View.GONE
            heading.text="◉  ORBIT  /  SECOND SCREEN"
            selected?.system?.let { system ->
                val first = DualScreenCoordinator.firstGameForArtwork(system)
                if (first != null) preloadGame(system, first)
                else if (artwork == null) {
                    handler.removeCallbacks(warmArtwork)
                    handler.post(warmArtwork)
                }
            }
            return
        }
        showGame(selection.first,selection.second,refresh)
    }

    internal fun preloadGame(system:SystemSpec,game:Game) {
        if(mediaActive)prepareArtwork().preload(system,game)
    }

    internal fun showGame(system:SystemSpec,game:Game,refresh:Boolean=false) {
        if(!mediaActive)return
        browse.visibility=android.view.View.GONE
        val panel=prepareArtwork()
        status.visibility=android.view.View.GONE
        mediaHost.visibility=android.view.View.VISIBLE
        heading.visibility=android.view.View.GONE
        heading.text=game.title
        panel.show(system,game,refresh)
    }
    private val update=object:Runnable { override fun run() {
        status.text=GameSessionManager.lastGame?.let { "Last launch\n\n$it\n${GameSessionManager.displayName}\n\nUse the emulator's own controls during gameplay." }
            ?: "Ready for your next game.\n\nChoose a game from the library on the other display."
        refreshMedia();bottomControls?.refresh();handler.postDelayed(this,1500)
    } }
    init {
        content = LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            setOnApplyWindowInsetsListener { view,insets ->
                val top=if(android.os.Build.VERSION.SDK_INT>=30)insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
                view.setPadding(0,top,0,0)
                insets
            }
        }
        view=GameMediaSurface(activity, content, audioPriority = 0)
        browse.backdropChanged={ if(browse.visibility==android.view.View.VISIBLE)view.showBrowse(it) }
        heading=TextView(activity).apply { text = "◉  ORBIT  /  SECOND SCREEN"; textSize = 20f; maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(Color.rgb(113, 177, 255)) }
        content.addView(heading)
        status = TextView(activity).apply { textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(24, 32, 24, 32) }
        content.addView(status,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(mediaHost,LinearLayout.LayoutParams(-1,0,1f))
        mediaHost.visibility=android.view.View.GONE
        content.addView(browse,LinearLayout.LayoutParams(-1,0,1f))
        browse.visibility=android.view.View.GONE
        bottomControls?.let { content.addView(it,LinearLayout.LayoutParams(-1,-2)) }
    }
    fun start() { mediaActive=true;handler.removeCallbacks(update);handler.post(update) }
    fun stop() { mediaActive=false;handler.removeCallbacks(update);handler.removeCallbacks(warmArtwork);artwork?.pause() }
    fun destroy() { stop();browse.destroy();artwork?.destroy();artwork=null }
}
