package au.orbit.frontend

import android.net.Uri

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

open class MainActivity : Activity() {
    var showingCompanion=false
        private set
    private var companionPanel:CompanionPanel?=null
    private var gameMediaRoot:View?=null
    private var gameMediaSurface:GameMediaSurface?=null
    fun companionSelection(): Pair<SystemSpec,Game>? =
        if(ready && !showingCompanion && activeTab==0 && library && !scanning &&
            !EmulatorSettings.menuVisible(this) && activeSplitTileMenu()?.shortcutSelected!=true)
            games.getOrNull(gameCursor)?.let { system to it } else null
    fun refreshCompanionMedia(refresh:Boolean=false) { companionPanel?.refreshMedia(refresh) }
    internal fun preloadCompanionGame(system:SystemSpec,game:Game) { companionPanel?.preloadGame(system,game) }
    internal fun showCompanionGame(system:SystemSpec,game:Game) { companionPanel?.showGame(system,game) }
    internal fun toggleCompanionVideo(system:SystemSpec,game:Game)=companionPanel?.toggleVideoPreview(system,game)==true
    internal fun finishCompanionVideo() { companionPanel?.finishVideoPreview() }
    internal val companionVideoActive get()=companionPanel?.manualVideoActive==true
    fun companionBrowse():CompanionBrowse? {
        if(!ready || showingCompanion || scanning)return null
        EmulatorSettings.companionBrowse(this)?.let { return it }
        activeSplitTileMenu()?.shortcutBrowse()?.let { return it }
        if((activeTab==0 && !library) || activeTab==3) {
            val spec=systems.getOrNull(focusedSystemIndex)?.takeIf { it in populated } ?: populated.firstOrNull() ?: return null
            val theme=OrbitThemes.current()
            val identity="${theme.id}/${theme.hashCode()}"
            return CompanionBrowse("system/${spec.id}/$identity",spec.title,"${pages[spec.id]?.games?.size ?: 0} games",system=spec)
        }
        if(activeTab==1) {
            val app=appsPanel?.companionSelection()
            return CompanionBrowse("app/${app?.info?.activityInfo?.packageName}/${app?.info?.activityInfo?.name}",app?.label ?: "Android apps",icon=app?.icon,iconResource=android.R.drawable.sym_def_app_icon)
        }
        if(activeTab==2) {
            val names=settingsCategories
            val icons=listOf(android.R.drawable.ic_menu_preferences,android.R.drawable.ic_menu_view,android.R.drawable.ic_menu_gallery,android.R.drawable.ic_menu_edit,android.R.drawable.ic_menu_view)
            return CompanionBrowse("settings/$settingsCategory",names[settingsCategory.coerceIn(names.indices)],"Settings",iconResource=icons[settingsCategory.coerceIn(icons.indices)])
        }
        if(activeTab==4)return CompanionBrowse("scraping","Scraping","Game media and metadata",iconResource=android.R.drawable.ic_menu_search)
        if(activeTab==3)return CompanionBrowse("themes","Themes","Choose a look for your collection",iconResource=android.R.drawable.ic_menu_gallery)
        return null
    }

    private fun discardViews() {
        romLibraryScan?.onUpdate = null
        romLibraryScan?.cancel()
        mediaTransferPanel?.destroy(); mediaTransferPanel = null; mediaTransferBack = null
        themeBackdrop?.destroy();themeBackdrop=null;themeBackdropHost=null;themeBackdropKey=null
        startMenu?.dismiss();startMenu=null
        colorfulSystems?.destroy();colorfulSystems=null
        importedSystems?.destroy();importedSystems=null;importedScene?.destroy();importedScene=null
        artworkPanel?.destroy();artworkPanel=null
        appsPanel?.destroy();appsPanel=null
        gameLogos.cancelPreload();discardPreparedSplitGameView()
        gameMediaRoot=null;gameMediaSurface=null
        libraryDock=null;libraryDockContent=null;dockPadding.clear()
        screenHost=null;tabViews.clear();tabFocus.clear();settingsKeyHandlers.clear();gameList=null;settingsKeys=null
    }
    fun showCompanionScreen() {
        cancelSwapKey();generation++;discardViews();showingCompanion=true
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        companionPanel?.destroy()
        companionPanel=CompanionPanel(this)
        super.setContentView(companionPanel!!.view)
        applyWindowChrome(themed=true)
        companionPanel!!.view.requestApplyInsets()
        companionPanel!!.start()
    }
    fun copyLibraryFrom(source:MainActivity) {
        val cursor=source.gameCursor
        val splitCursor=source.activeSplitGrid()?.cursorPosition
        val first=source.gameList?.firstVisiblePosition ?: 0
        val offset=source.gameList?.getChildAt(0)?.top ?: 0
        selected=source.selected;focusedSystemIndex=source.focusedSystemIndex
        carouselPosition=source.carouselPosition;library=source.library;activeTab=source.activeTab
        gameCursor=cursor
        settingsCategory=source.settingsCategory
        displaySystemId=source.displaySystemId;displayGameUri=source.displayGameUri
        scrapingCategory=source.scrapingCategory
        pageHistory.clear();pageHistory.addAll(source.pageHistory)
        games=source.games;pages=source.pages;populated=source.populated;error=source.error
        repository=LibraryRepository(this);startupFinished=true;ready=true;scanning=false
        companionPanel?.destroy();companionPanel=null
        discardViews();showingCompanion=false
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        render()
        val splitGrid=activeSplitGrid()
        if(splitGrid!=null) {
            if(splitCursor!=null)splitGrid.select(splitCursor,animate=false)
            else if(library)splitGrid.selectLibrary(cursor)
        } else if(library && activeTab==0)gameList?.post {
            moveGameCursor(cursor)
            gameList?.setSelectionFromTop(first,offset)
        }
        FrontendRefreshRate.apply(this)
    }
    private val blue get()=OrbitThemes.current().accent
    private val muted get()=OrbitThemes.current().muted
    internal fun canPlaceLibrary()=ready && !scanning
    private var nativeSystems:OrbitSystemsView?=null
    private var selected = 0
    private var focusedSystemIndex = 0
    private var carouselPosition = 0
    private var library = false
    private var activeTab = 0
    private data class PageDestination(val tab:Int,val library:Boolean=false)
    private val pageHistory=mutableListOf<PageDestination>()
    private val settingsCategories=listOf("General","Display","Media","Customisation","Dual Screen")
    private var displaySystemId:String?=null
    private var displayGameUri:String?=null
    private var appsPanel: AppsPanel? = null
    private var scraperSettingsPanel:ScraperSettingsPanel?=null
    private var screenHost: FrameLayout? = null
    private var libraryDock:BottomScreenControls?=null
    private var libraryDockContent:View?=null
    private val dockPadding=java.util.WeakHashMap<View,Int>()
    private fun refreshLibraryDock() {
        val dock=libraryDock ?: return
        dock.visibility=if(ready && OrbitThemeArtwork.screen(this)=="bottom")View.VISIBLE else View.GONE
        dock.refresh()
        libraryDockContent?.let { padForLibraryDock(it,dock) }
    }
    private fun padForLibraryDock(content:View,dock:BottomScreenControls) {
        val base=dockPadding.getOrPut(content) { content.paddingBottom }
        val bottom=base+if(dock.visibility==View.VISIBLE)dock.minimumHeight else 0
        if(content.paddingBottom!=bottom)content.setPadding(content.paddingLeft,content.paddingTop,content.paddingRight,bottom)
    }
    private var themeBackdropHost:FrameLayout?=null
    private var themeBackdrop:EsDeScene?=null
    private var themeBackdropKey:String?=null
    private var themeGallery:ThemeGalleryPanel?=null
    private var themeImageResult:((Uri?)->Unit)?=null
    fun pickThemeImage(result:(Uri?)->Unit) {
        themeImageResult=result
        runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),108,popupLaunchOptions()) }
            .onFailure { themeImageResult=null;showDataError(it) }
    }
    private var romLibraryScan: RomLibraryScan? = null
    private var romFolderResult: ((Uri?) -> Unit)? = null
    private var mediaTransferPanel: MediaTransferPanel? = null
    private var mediaTransferBack: (() -> Unit)? = null
    private val tabViews = mutableMapOf<Int, View>()
    private val tabFocus = mutableMapOf<Int, View>()
    private var warmingTabs = false
    private fun refreshThemeBackdrop() {
    val external=if(ready && activeTab==0)ImportedThemes.active(this) else null
    themeBackdropHost?.visibility=if(external!=null)View.VISIBLE else View.GONE
    if(external!=null) {
        val spec=if(library)system else systems.getOrNull(focusedSystemIndex) ?: system
        val key="${external.id}/${spec.id}/${ImportedThemes.options(this,external)}"
        if(themeBackdropKey!=key) {
            themeBackdropKey=key
            val backdrop=EsDeScene(this,external,"system",backgroundLayer=true)
            val previous=themeBackdrop
            themeBackdrop=backdrop
            backdrop.onReady={
                if(themeBackdrop===backdrop) {
                    themeBackdropHost?.removeAllViews();themeBackdropHost?.addView(backdrop,FrameLayout.LayoutParams(-1,-1));previous?.destroy()
                } else backdrop.destroy()
            }
            backdrop.showSystem(spec,pages[spec.id]?.games?.size ?: 0)
        }
    }
    }
    private fun applyWindowChrome(themed:Boolean) {
        window.decorView.systemUiVisibility=if(themed)View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION else 0
        window.statusBarColor=if(themed)Color.TRANSPARENT else Color.BLACK
        window.navigationBarColor=if(themed)Color.TRANSPARENT else Color.BLACK
        window.isStatusBarContrastEnforced=false
        window.isNavigationBarContrastEnforced=false
        FrontendNavigationBar.apply(this)
    }
    private fun pageBackground(view:View):android.graphics.drawable.Drawable {
        val theme=OrbitThemes.current()
        // The bottom grid's Systems and game pages can each have their own background image.
        val pageRole=when {
            activeTab!=0 || view.findViewWithTag<View>(SplitTileMenu.TAG)==null -> null
            view.findViewWithTag<View>("orbit-game-list")!=null -> "gamesBackground"
            view.findViewWithTag<View>("orbit-systems-grid")!=null -> "systemsBackground"
            else -> null
        }
        val fallback=OrbitThemeArtwork.background(this,theme,pageRole=pageRole)
        if(activeTab!=0 || library || scanning || OrbitThemeArtwork.screen(this)=="bottom" ||
            view.findViewWithTag<View>("orbit-systems-grid")!=null)return fallback
        val spec=systems.getOrNull(focusedSystemIndex)?.takeIf { it in populated } ?: populated.firstOrNull() ?: return fallback
        val screen=OrbitThemeArtwork.artworkScreen(this)
        if(theme.heroImage(screen,spec.id)==null)return fallback
        return OrbitThemeArtwork.systemHeroBackground(this,theme,spec,screen,fallback).also { hero ->
            OrbitThemeArtwork.requestHero(this,theme,spec,screen,hero::invalidateSelf)
        }
    }
    private fun focusSystem(spec:SystemSpec) {
        focusedSystemIndex=systems.indexOf(spec)
        if(activeTab==0 && !library)tabViews[0]?.let { it.background=pageBackground(it) }
        DualScreenCoordinator.refreshMedia()
    }
    private fun present(contentView: View) {
        finishCrossfade()
        val view=if(contentView===gameMediaRoot)gameMediaSurface ?: contentView else contentView
        val host = screenHost ?: FrameLayout(this).also { content ->
            screenHost=content
            val shell=FrameLayout(this).apply {
                setBackgroundColor(Color.rgb(12,17,25))
                val backdrop=FrameLayout(this@MainActivity)
                themeBackdropHost=backdrop
                addView(backdrop,FrameLayout.LayoutParams(-1,-1))
                addView(content,FrameLayout.LayoutParams(-1,-1))
                if(OrbitThemeArtwork.screen(this@MainActivity)=="bottom") {
                    val dock=BottomScreenControls(this@MainActivity)
                    libraryDock=dock
                    addView(dock,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
                }
            }
            super.setContentView(shell)
        }
        if(!warmingTabs) {
            if(library || activeTab!=0) {
                gameLogos.cancelPreload()
                discardPreparedSplitGameView()
            }
            refreshThemeBackdrop()
            val topSettings=activeTab==2 && OrbitThemeArtwork.screen(this)=="top"
            val themed=ready && (activeTab==0 || topSettings || OrbitThemeArtwork.image(this,OrbitThemes.current(),"background")!=null)
            val splitPage=activeTab==0 && view.findViewWithTag<View>(SplitTileMenu.TAG)!=null
            val previousSplitPage=tabViews[if(library)0 else 6]
            val switchingSplitPages=splitPage && previousSplitPage?.isShown==true &&
                previousSplitPage.findViewWithTag<View>(SplitTileMenu.TAG)!=null
            if(!switchingSplitPages)applyWindowChrome(themed)
            // Remember the outgoing page and its background so it can fade out over the new page;
            // the panel then never sees the brightness change in a single frame.
            // The chosen page transition applies to every Systems <-> game page change, in every
            // theme and layout and on either screen. "Instant" skips the animation.
            // The chosen transition plays only between the system list and a game list, in every theme
            // and on either screen. With Dip, going back to the system list is instant; hard cuts
            // *into* a game list flashed on the Thor, even with a gap.
            val dipBack=pageTransition()==Transition.DIP && !library
            if(activeTab==0 && previousSplitPage!=null && previousSplitPage!==view && previousSplitPage.isShown &&
                pageTransition()!=instantTransition && !dipBack) {
                crossfadeOutgoing=previousSplitPage
                crossfadeBackground=host.background
                crossfadeOpening=library
            }
            val bottomGrid=libraryDock!=null && splitPage
            if(bottomGrid) {
                host.background=pageBackground(view)
                if(view.background!=null)view.background=null
            } else {
                if(host.background!=null)host.background=null
                if(ready && view !is GameMediaSurface)view.background=pageBackground(view)
            }
            val padded=if(view is GameMediaSurface)view.content else view
            if(themed) {
                val topPadding=if(bottomGrid)bottomGridTopPadding() else {
                    val statusInset=resources.getIdentifier("status_bar_height","dimen","android").let { if(it!=0)resources.getDimensionPixelSize(it) else 0 }
                    statusInset+if(library)dp(12) else 0
                }
                if(padded.paddingTop!=topPadding)padded.setPadding(padded.paddingLeft,topPadding,padded.paddingRight,padded.paddingBottom)
            }
            libraryDockContent=padded;refreshLibraryDock()
            (host.layoutParams as FrameLayout.LayoutParams).apply {
                if(topMargin!=0) {
                    topMargin=0
                    host.layoutParams=this
                }
            }
        }
        val slot = if (!ready) -1 else if (activeTab == 0 && library) 6 else activeTab
        val previous = tabViews.put(slot, view)
        if (previous != null && previous !== view) host.removeView(previous)
        if (view.parent == null) host.addView(view, FrameLayout.LayoutParams(-1, -1))
        if(!warmingTabs)DualScreenCoordinator.refreshMedia()
        if (warmingTabs) view.visibility = View.INVISIBLE
        else for(i in 0 until host.childCount) {
            val child=host.getChildAt(i)
            child.visibility=if(child===view)View.VISIBLE else View.INVISIBLE
        }
        val outgoing=crossfadeOutgoing
        crossfadeOutgoing=null
        if(!warmingTabs && outgoing!=null && outgoing.parent===host)startCrossfade(host,outgoing,crossfadeBackground,view,opening=crossfadeOpening)
        crossfadeBackground=null
    }
    // 150 ms reads as a background fade on the Thor; 90 ms already reads as a flash.
    private val pageCrossfadeMs=150L
    private var crossfadeOutgoing:View?=null
    /** Direction of the page change: true going forward (e.g. into a game list), false going back. */
    private var crossfadeOpening=true
    private var crossfadeBackground:android.graphics.drawable.Drawable?=null
    private var crossfade:android.animation.ValueAnimator?=null
    private var crossfadeEnd:(()->Unit)?=null

    private val pageSlideMs=220L
    // A full-screen slide reads smoother a little longer, on Material's standard curve.
    private val pageSlideSmoothMs=220L
    private val slideEase=android.view.animation.DecelerateInterpolator(1.6f)
    private val pageTransitionKey="pageTransition"
    // Stored values stay stable for saved settings; the menu cycles them in [pageTransitionOrder].
    // Value 2 is the old "Slide up and down"; it now shows as Slide, with a Direction of From bottom.
    private val pageTransitionLabels=listOf("Fade","Slide","Slide","Instant","Zoom","Scale","Flip","Dip")
    private val pageTransitionOrder=listOf(0,1,4,5,6,7,3)
    private val instantTransition=3
    private object Transition { const val FADE=0; const val SLIDE_X=1; const val SLIDE_Y=2; const val ZOOM=4; const val SCALE=5; const val FLIP=6; const val DIP=7 }
    // Dip: the old page fades away, the background holds briefly, then the new page fades in.
    // Systems fading out is the big brightness drop, so it keeps 100 ms; the blank and the game list
    // fading in (mostly background) can be shorter.
    private val dipOutMs=100L
    private val dipHoldMs=25L
    private val dipGameInMs=80L
    private val dipInMs=100L
    // Going back is quicker: the mostly-empty game page fades out fast and the blank is short. The
    // Systems page fading in is the big brightness change, so it keeps the full 100 ms.
    private val dipBackOutMs=80L
    private val dipBackHoldMs=20L
    private fun pageTransition()=TransitionSettings.int(pageTransitionKey,0).coerceIn(pageTransitionLabels.indices)
    private fun nextPageTransition(current:Int):Int {
        val position=pageTransitionOrder.indexOf(if(current==Transition.SLIDE_Y)Transition.SLIDE_X else current)
        return pageTransitionOrder[(position+1)%pageTransitionOrder.size]
    }
    /** The Direction choices for a style, or null when the style has no direction. */
    private fun transitionDirectionLabels(style:Int):List<String>? = when(style) {
        Transition.SLIDE_X,Transition.SLIDE_Y -> listOf("From right","From left","From bottom","From top")
        Transition.FLIP -> flipDirectionLabels
        Transition.ZOOM -> listOf("From tile","From centre")
        Transition.SCALE -> listOf("Grow in","Shrink in")
        else -> null
    }
    private fun transitionDirectionKey(style:Int)=when(style) {
        Transition.FLIP -> flipDirectionKey
        Transition.SLIDE_X,Transition.SLIDE_Y -> "transitionDirection.${Transition.SLIDE_X}"
        else -> "transitionDirection.$style"
    }
    /** The chosen Direction for a style (0 when it has none). The old up-and-down slide defaults to From bottom. */
    private fun transitionDirection(style:Int):Int {
        val labels=transitionDirectionLabels(style) ?: return 0
        val default=if(style==Transition.SLIDE_Y)2 else 0
        return TransitionSettings.int(transitionDirectionKey(style),default).coerceIn(labels.indices)
    }
    private val transitionSpeedKey="transitionSpeed"
    private val transitionSpeedLabels=listOf("Slow","Normal","Fast","Very fast")
    private val transitionSpeedFactors=listOf(1.4f,1f,0.75f,0.55f)
    private fun transitionSpeed()=TransitionSettings.int(transitionSpeedKey,1).coerceIn(transitionSpeedLabels.indices)
    private fun transitionSpeedFactor()=transitionSpeedFactors[transitionSpeed()]
    private val flipDirectionKey="flipDirection"
    private val flipDirectionLabels=listOf("Sideways","Forwards","Backwards")
    /** 0 = sideways (around the vertical axis), 1 = forwards (top tips toward you), 2 = backwards. */
    private fun flipDirection()=TransitionSettings.int(flipDirectionKey,0).coerceIn(flipDirectionLabels.indices)

    /** The selected tile on [page], as a rectangle in [host]'s coordinates, for the zoom transition. */
    private fun selectedTileRect(page:View,host:View):android.graphics.RectF? {
        val grid=page.findViewWithTag<View>("orbit-systems-grid") as? SplitLibraryGrid ?: return null
        val tile=grid.cursorTile ?: return null
        if(tile.width==0)return null
        val a=IntArray(2);val b=IntArray(2)
        tile.getLocationInWindow(a);host.getLocationInWindow(b)
        val left=(a[0]-b[0]).toFloat();val top=(a[1]-b[1]).toFloat()
        return android.graphics.RectF(left,top,left+tile.width,top+tile.height)
    }

    /**
     * Animates a Systems <-> game page change in the chosen style. Every style changes the overall
     * picture gradually (fading, moving or turning), so the Thor's panel never sees the brightness
     * change in a single frame. The outgoing page keeps its old background while it animates.
     *
     * Fade: the outgoing page fades out on top. Slides: both pages move together, the game page
     * entering from the right (or bottom) and leaving that way. Zoom: the game page grows out of the
     * selected system tile, and shrinks back into it. Scale: the incoming page eases up from 92%
     * while the old one fades. Flip: the page turns over like a card.
     */
    private fun startCrossfade(host:FrameLayout,outgoing:View,background:android.graphics.drawable.Drawable?,
        incoming:View,opening:Boolean) {
        val style=pageTransition()
        val params=outgoing.layoutParams
        host.removeView(outgoing)
        host.addView(outgoing,params)
        // Bottom-grid pages share the host's background, so lend the old one to the outgoing page;
        // other layouts draw their own page background and keep it.
        val ownBackground=outgoing.background
        if(background!=null)outgoing.background=background
        outgoing.visibility=View.VISIBLE
        outgoing.alpha=1f
        val pages=listOf(outgoing,incoming)
        fun reset() { for(page in pages) {
            page.alpha=1f;page.translationX=0f;page.translationY=0f;page.scaleX=1f;page.scaleY=1f;page.rotationY=0f;page.rotationX=0f
            page.pivotX=page.width/2f;page.pivotY=page.height/2f
        } }
        // Slide Direction: 0 from right, 1 from left, 2 from bottom, 3 from top.
        val slideDirection=if(style==Transition.SLIDE_X || style==Transition.SLIDE_Y)transitionDirection(style) else 0
        val vertical=slideDirection>=2
        val slideSign=if(slideDirection==1 || slideDirection==3)-1f else 1f
        val distance=(if(vertical)host.height else host.width).toFloat()
        fun View.offset(value:Float) { if(vertical)translationY=value else translationX=value }
        // Opening: the game page comes from the chosen side and Systems leaves the other way; back reverses.
        val direction=(if(opening)1f else -1f)*slideSign
        // Zoom: the game page is the one that grows or shrinks; it is drawn above the Systems page.
        val gamePage=if(opening)incoming else outgoing
        val systemsPage=if(opening)outgoing else incoming
        // Zoom Direction: 0 grows out of the selected tile, 1 grows from the centre.
        val tile=if(style==Transition.ZOOM && transitionDirection(style)==0)selectedTileRect(systemsPage,host) else null
        // Scale Direction: 0 grows in from slightly smaller, 1 shrinks in from slightly larger.
        val scaleFrom=if(transitionDirection(Transition.SCALE)==0)0.92f else 1.08f
        val zoomFrom=tile?.let { maxOf(it.width()/host.width,it.height()/host.height).coerceIn(0.05f,1f) } ?: 0.3f
        when(style) {
            Transition.SLIDE_X,Transition.SLIDE_Y -> {
                incoming.offset(distance*direction)
                // Slide cached images of both pages instead of redrawing every tile each frame.
                for(page in pages)page.setLayerType(View.LAYER_TYPE_HARDWARE,null)
            }
            Transition.ZOOM -> {
                gamePage.pivotX=tile?.centerX() ?: host.width/2f
                gamePage.pivotY=tile?.centerY() ?: host.height/2f
                if(opening) { incoming.bringToFront();incoming.alpha=0f;incoming.scaleX=zoomFrom;incoming.scaleY=zoomFrom }
            }
            Transition.SCALE -> if(opening) { incoming.scaleX=scaleFrom;incoming.scaleY=scaleFrom }
            Transition.FLIP -> {
                // A distant camera keeps the turning page from stretching at its edges, and hardware
                // layers turn a cached image of each page instead of redrawing every tile per frame.
                val camera=14000*resources.displayMetrics.density
                for(page in pages) { page.cameraDistance=camera;page.setLayerType(View.LAYER_TYPE_HARDWARE,null) }
                incoming.alpha=0f
            }
            Transition.DIP -> incoming.alpha=0f
        }
        var done=false
        val end={
            if(!done) {
                done=true
                crossfadeEnd=null
                outgoing.background=ownBackground
                reset()
                if(style==Transition.FLIP || style==Transition.SLIDE_X || style==Transition.SLIDE_Y)
                    for(page in pages)page.setLayerType(View.LAYER_TYPE_NONE,null)
                // Stays in the page host, hidden unless it became the current page meanwhile.
                outgoing.visibility=if(outgoing===activeHostPage())View.VISIBLE else View.INVISIBLE
            }
        }
        crossfadeEnd=end
        // Speed scales every duration. Fades keep a floor, because fading faster than about 100 ms
        // brought the Thor's flash back; motion (slide, zoom, scale, flip) can go all the way.
        val speed=transitionSpeedFactor()
        fun scaled(ms:Long,floor:Long=0L)=maxOf(floor,(ms*speed).toLong())
        val fadeMs=scaled(pageCrossfadeMs,100L)
        val dipOut=scaled(if(opening)dipOutMs else dipBackOutMs,if(opening)100L else 80L)
        val dipHold=scaled(if(opening)dipHoldMs else dipBackHoldMs)
        val dipIn=scaled(if(opening)dipGameInMs else dipInMs,if(opening)80L else 100L)
        val total=when(style) {
            Transition.FADE -> fadeMs
            Transition.FLIP -> scaled(380L)
            Transition.SLIDE_X,Transition.SLIDE_Y -> scaled(pageSlideSmoothMs)
            Transition.DIP -> dipOut+dipHold+dipIn
            else -> scaled(pageSlideMs)
        }
        val ease=slideEase
        // Material's standard curve: a soft start and a long, gentle settle.
        val flipEase=android.view.animation.PathInterpolator(0.4f,0f,0.2f,1f)
        // Sideways turns around the vertical axis; forwards and backwards tip over the horizontal axis
        // (a negative X rotation brings the top edge toward the viewer).
        val flip=flipDirection()
        val flipSign=when(flip) { 1 -> -1f; 2 -> 1f; else -> 1f }*(if(opening)1f else -1f)
        fun View.turn(degrees:Float) { if(flip==0)rotationY=degrees*flipSign else rotationX=degrees*flipSign }
        crossfade=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
            duration=total
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t=it.animatedValue as Float
                // The fading part always runs linearly over the fade time, so brightness changes evenly.
                val fade=(t*total/fadeMs).coerceIn(0f,1f)
                val move=ease.getInterpolation(t)
                when(style) {
                    Transition.FADE -> outgoing.alpha=1f-t
                    Transition.SLIDE_X,Transition.SLIDE_Y -> {
                        val glide=flipEase.getInterpolation(t)
                        incoming.offset(distance*direction*(1f-glide))
                        outgoing.offset(-distance*direction*glide)
                    }
                    Transition.ZOOM -> {
                        val scale=if(opening)zoomFrom+(1f-zoomFrom)*move else 1f-(1f-zoomFrom)*move
                        gamePage.scaleX=scale;gamePage.scaleY=scale
                        if(opening) { incoming.alpha=fade;outgoing.alpha=1f-fade } else outgoing.alpha=1f-fade
                    }
                    Transition.SCALE -> {
                        outgoing.alpha=1f-fade
                        val scale=if(opening)scaleFrom+(1f-scaleFrom)*move else 1f+(scaleFrom-1f)*move
                        val page=if(opening)incoming else outgoing
                        page.scaleX=scale;page.scaleY=scale
                    }
                    Transition.DIP -> {
                        val elapsed=t*total
                        outgoing.alpha=1f-(elapsed/dipOut).coerceIn(0f,1f)
                        incoming.alpha=((elapsed-dipOut-dipHold)/dipIn).coerceIn(0f,1f)
                    }
                    Transition.FLIP -> {
                        val turn=flipEase.getInterpolation(t)
                        // The card lifts back slightly as it turns edge-on, then settles.
                        val lift=1f-0.08f*kotlin.math.sin(Math.PI*turn).toFloat()
                        for(page in pages) { page.scaleX=lift;page.scaleY=lift }
                        if(turn<0.5f) {
                            outgoing.alpha=1f;incoming.alpha=0f
                            outgoing.turn(180f*turn)
                        } else {
                            outgoing.alpha=0f;incoming.alpha=1f
                            incoming.turn(-180f*(1f-turn))
                        }
                    }
                }
            }
            addListener(object:android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:android.animation.Animator) { end() }
            })
            start()
        }
    }
    /**
     * Dip into a game list where only the background dips: the game tiles show at once on top, the
     * Systems page (tiles and old background) fades out underneath, a blank holds briefly, then the
     * new background fades in. Brightness still changes gradually, so the Thor's panel does not blink.
     */
    private fun activeHostPage():View?=tabViews[if(!ready) -1 else if(activeTab==0 && library) 6 else activeTab]
    private fun finishCrossfade() { crossfade?.cancel();crossfade=null;crossfadeEnd?.invoke() }

    private fun bottomGridTopPadding():Int {
        val insets=window.decorView.rootWindowInsets
        val statusInset=if(android.os.Build.VERSION.SDK_INT>=30)insets?.getInsets(WindowInsets.Type.statusBars())?.top else insets?.systemWindowInsetTop
        return maxOf(dp(12),statusInset ?: 0)
    }
    private fun warmTabViews() {
        val oldTab = activeTab; val oldLibrary = library; val oldRoot = root
        warmingTabs = true
        try {
            activeTab = 3; renderThemesPage()
            activeTab = 2; renderSettingsPage()
            activeTab = 4; renderSettingsPage()
            activeTab = 1
            val panel = appsPanel ?: AppsPanel(this, ::navigateTab, ::returnToPreviousPage,showNavigation=false).also { appsPanel = it }
            present(panel.view)
            tabFocus[1] = panel.initialFocus
            panel.resume()
        } finally { activeTab = oldTab; library = oldLibrary; root = oldRoot; warmingTabs = false }
    }
    private fun selectedScrapingSystems():List<SystemSpec> {
        val selected=getSharedPreferences("scraper_credentials",0).getStringSet("scrape.systems",emptySet()).orEmpty()
        return ScrapePolicy.systemsForSelection(populated,selected)
    }
    private fun openScrapingPage(category:Int=1) {
        scrapingCategory=category
        tabViews.remove(4)?.let { screenHost?.removeView(it) };tabFocus.remove(4)
        navigateTab(4)
    }
    private fun scraperPanel():ScraperSettingsPanel = scraperSettingsPanel ?: ScraperSettingsPanel(
        this,::selectedScrapingSystems,{ spec -> pages[spec.id]?.games?.size ?: 0 }
    ) {
        GameArtworkPanel.clearMediaCache();EsDeScene.clearMediaCache();gameLogos.clear();preloadKey=null
        DualScreenCoordinator.refreshMedia(true)
    }.also { scraperSettingsPanel=it }
    private var scrapingSidebar:View?=null
    private var scrapingDetails:View?=null
    private var scrapingCategory = 1
    private var settingsCategory = 0
    private var settingsCategoryView: View? = null
    private var settingsDetailView: View? = null
    private val settingsKeyHandlers=mutableMapOf<Int,(KeyEvent)->Boolean>()
    private var settingsKeys: ((KeyEvent) -> Boolean)? = null
    private var games = emptyList<Game>()
    private var scanning = false
    private var error: String? = null
    private var generation = 0
    private var pickingSystem: SystemSpec? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val libraryRefreshWorker=Executors.newSingleThreadExecutor()
    private lateinit var repository: LibraryRepository
    private lateinit var root: LinearLayout
    private var ready = false
    private var populated = emptyList<SystemSpec>()
    private data class LibraryPage(val games: List<Game>, val errors: List<String>)
    private var pages = emptyMap<String, LibraryPage>()
    private var gameList: AbsListView? = null
    private var artworkPanel: GameArtworkPanel? = null
    private var colorfulSystems: ColorfulSystemsView? = null
    private var importedSystems:ImportedSystemsView?=null
    private var importedScene:EsDeScene?=null
    private var gamePreloader:EsDeScene?=null
    private var preloadKey:String?=null
    private fun preloadFirstGame(spec:SystemSpec) {
        if(activeTab!=0 || library)return
        val theme=ImportedThemes.active(this) ?: return
        val page=pages[spec.id] ?: return
        val first=page.games.firstOrNull() ?: return
        val key="${theme.id}/${spec.id}/${first.uri}/${ImportedThemes.options(this,theme)}"
        if(preloadKey==key)return
        preloadKey=key;gamePreloader?.destroy()
        val loader=EsDeScene(this,theme,"gamelist",preloadOnly=true,backgroundLayer=false)
        gamePreloader=loader
        loader.onReady={
            loader.destroy();if(gamePreloader===loader)gamePreloader=null
            val props=ImportedThemes.model(this,theme,spec.id).views["gamelist"]?.firstOrNull { it.type in setOf("carousel","grid","textlist") }?.props.orEmpty()
            val type=props["imageType"]?.split(Regex("[,\\s]+"))?.firstOrNull()?.takeIf { it!="none" }
            if(type!=null)page.games.take(4).forEach { game ->
                val label=TextView(this);val row=FrameLayout(this).apply { addView(label) }
                gameLogos.bind(row,label,spec,game,true,type)
            }
        }
        loader.initialise(spec,page.games.size,first)
    }
    private val gameLogos=GameLogoRows(this)
    private var gameCursor = 0
    private fun moveGameCursor(position: Int) {
        if(library)activeSplitGrid()?.let { it.selectLibrary(position);return }
        val list = gameList ?: return
        if (list.count == 0) return
        gameCursor = position.coerceIn(0, list.count - 1)
        // Keep the viewport anchored while the highlight moves between visible rows.
        // ListView.setSelection() and adapter notifications can otherwise pull each game
        // toward Android's preferred selection position even when it is already visible.
        val first=list.firstVisiblePosition
        val row=list.getChildAt(gameCursor-first)
        val rowHeight=row?.height ?: list.getChildAt(0)?.height ?: dp(54)
        val top=list.paddingTop
        val bottom=(list.height-list.paddingBottom-rowHeight).coerceAtLeast(top)
        val targetTop=when {
            row!=null -> row.top.coerceIn(top,bottom)
            gameCursor<first -> top
            else -> bottom
        }
        list.setSelectionFromTop(gameCursor,targetTop-top)
        for(index in 0 until list.childCount) {
            val position=first+index
            if(position<list.count)list.adapter.getView(position,list.getChildAt(index),list)
        }
        artworkPanel?.show(system, games.getOrNull(gameCursor))
        importedScene?.showGame(games.getOrNull(gameCursor))
        DualScreenCoordinator.refreshMedia()
    }
    private fun page(report: RomScanResult) = LibraryPage(report.games.map { Game(it.title, android.net.Uri.parse(it.uri), it.fileName,it.directory) }, report.errors)
    private fun openSystem(index: Int) {
        rememberPage(PageDestination(0,true))
        activeTab = 0; selected = index; library = true
        val cached = pages[system.id]
        if (cached == null) { scan(); return }
        generation++; scanning = false; games = cached.games
        error = cached.errors.takeIf { it.isNotEmpty() }?.let { "${games.size} games · ${it.size} folder errors. Check ROM library settings." }
        render()
    }

    private var pendingSplitSystemOpen:Any?=null
    private fun openSplitSystem(grid:SplitLibraryGrid,index:Int) {
        if(android.os.Build.VERSION.SDK_INT<33 || libraryDock==null || DualScreenCoordinator.companionDisplay()==null) {
            openSystem(index)
            return
        }
        if(pendingSplitSystemOpen!=null)return
        val request=Any()
        pendingSplitSystemOpen=request
        val choreographer=Choreographer.getInstance()
        choreographer.postVsyncCallback(object:Choreographer.VsyncCallback {
            private var skippedEarlyFrame=false
            override fun onVsync(data:Choreographer.FrameData) {
                if(pendingSplitSystemOpen!==request)return
                if(isFinishing || isDestroyed || library || activeSplitGrid()!==grid || !grid.isShown) {
                    pendingSplitSystemOpen=null
                    return
                }
                // Some displays also run an input-driven frame without a compositor timeline.
                if(!skippedEarlyFrame && data.preferredFrameTimeline.vsyncId<0) {
                    skippedEarlyFrame=true
                    choreographer.postVsyncCallback(this)
                    return
                }
                pendingSplitSystemOpen=null
                openSystem(index)
            }
        })
    }
    private var startMenu: AlertDialog? = null
    private var remoteSettingsDialog: AlertDialog? = null
    /** The activity on the other screen that shows Settings, while Settings is open there. */
    private var remoteSettingsHost: Activity? = null
    /** Popups opened from Settings belong on the screen Settings is showing on. */
    internal fun popupContext(): Activity =
        remoteSettingsHost?.takeIf { remoteSettingsDialog?.isShowing == true && !it.isFinishing && !it.isDestroyed } ?: this
    /** Popups shown on the other screen; controller buttons still arrive here and are passed on. */
    private val remotePopups = mutableListOf<android.app.Dialog>()
    internal fun trackPopup(dialog: android.app.Dialog) {
        remotePopups.removeAll { !it.isShowing }
        if (popupContext() !== this) remotePopups.add(dialog)
    }
    /** Launch options that open system pickers (files, folders) on the screen Settings is showing on. */
    internal fun popupLaunchOptions(): android.os.Bundle? {
        val host = popupContext().takeIf { it !== this } ?: return null
        return android.app.ActivityOptions.makeBasic().setLaunchDisplayId(host.windowManager.defaultDisplay.displayId).toBundle()
    }
    private var pendingSettingsStart: Long? = null
    private val skipStartup = java.util.concurrent.atomic.AtomicBoolean(false)
    private var startupFinished = false
    private var startupNotice: String? = null
    private lateinit var startupStatus: TextView
    private lateinit var startupProgress: ProgressBar
    private val system get() = systems[selected]
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        showingCompanion=state?.getBoolean("companionRole") ?: intent.getBooleanExtra("companionRole",this is CompanionActivity)
        if(intent.getBooleanExtra("startLibrary",false)) {
            intent.removeExtra("startLibrary")
            showingCompanion=false
        } else if(this !is CompanionActivity && !intent.getBooleanExtra("companionRole",false)) {
            showingCompanion=DualScreenCoordinator.startLibraryOnBottom(this)
        }
        if(showingCompanion) {
            showCompanionScreen()
            DualScreenCoordinator.panelCreated(this)
            return
        }
        startLibrary(state)
    }
    private fun startLibrary(state:Bundle?) {
        val startupBegan=android.os.SystemClock.elapsedRealtime()
        startupFinished = state?.getBoolean("startupFinished") ?: false
        val loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(48), dp(24), dp(48), dp(24)); setBackgroundColor(Color.rgb(12, 17, 25))
        }
        loading.addView(text("◉  ORBIT", 32f, blue))
        loading.addView(text("Scanning game directories", 22f))
        startupStatus = text("Preparing systems and your data folder…", 16f, muted)
        loading.addView(startupStatus)
        startupProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        loading.addView(startupProgress, LinearLayout.LayoutParams(-1, dp(24)))
        val foundCount=text("",18f)
        val foundSystems=text("",14f,muted).apply { maxLines=5 }
        loading.addView(foundCount);loading.addView(foundSystems)
        loading.addView(button("Skip scan") { skipStartup.set(true); startupStatus.text = "Opening saved library…" })
        present(loading)
        worker.execute {
            var scannedOnStartup=false
            val result = runCatching {
                SystemCatalog.initialize(this)
                UserDataStore.initialize(this)
                repository = LibraryRepository(this)
                val startupReports = mutableMapOf<SystemSpec, RomScanResult>()
                val scanOnStartup=!startupFinished && UserDataStore.int(this,"scanOnStartup",1)!=0
                scannedOnStartup=scanOnStartup
                if(scanOnStartup) {
                    var foundGames=0
                    val foundNames=mutableListOf<String>()
                    runOnUiThread { if(!isDestroyed)startupStatus.text="Preparing emulator profiles…" }
                    prepareLaunchProfiles()
                    try {
                        val warnings=StartupScanner.scan(repository,{ skipStartup.get() },{ spec,report ->
                            startupReports[spec]=report
                            if(report.games.isNotEmpty())foundNames.add("${spec.title} · ${report.games.size} games")
                            foundGames+=report.games.size
                            val count="$foundGames games · ${foundNames.size} systems found"
                            val names=foundNames.takeLast(5).joinToString("\n")
                            runOnUiThread { if(!isDestroyed && !ready) { foundCount.text=count;foundSystems.text=names } }
                        }) { message,done,total ->
                            runOnUiThread { if(!isDestroyed && !ready && !skipStartup.get()) {
                                startupStatus.text=message
                                startupProgress.isIndeterminate=total==0
                                startupProgress.max=total.coerceAtLeast(1);startupProgress.progress=done
                            } }
                        }
                        startupNotice=warnings.takeIf { it.isNotEmpty() }?.let { "Startup scan: ${it.size} folder errors. Check ROM library settings." }
                    } catch(_:java.util.concurrent.CancellationException) { }
                }
                val available = repository.populatedSystems(startupReports)
                val requested = systems[(state?.getInt("system") ?: 0).coerceIn(systems.indices)]
                val first = requested.takeIf { it in available } ?: available.firstOrNull() ?: requested
                val loaded = available.associate { spec -> spec.id to page(startupReports[spec] ?: repository.cached(spec) ?: RomScanResult(emptyList(), emptyList())) }
                runCatching { InstalledApps.load(applicationContext) }
                runCatching { OrbitThemes.refresh(this) }
                runCatching { OrbitConsoleImages.load(this,first) }
                Triple(available, first, loaded)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                result.onSuccess { (available, first, loaded) ->
                    populated = available
                    pages = loaded
                    selected = systems.indexOf(first)
                    games = loaded[first.id]?.games.orEmpty()
                    error = loaded[first.id]?.errors?.takeIf { it.isNotEmpty() }?.let { "${games.size} games · ${it.size} folder errors. Check ROM library settings." }
                    library = state?.getBoolean("library") ?: false
                    pickingSystem = systems.find { it.id == state?.getString("picking") }
                    startupFinished = true; ready = true; scanning = false; render()
                    android.util.Log.i("OrbitStartup","Library ready in ${android.os.SystemClock.elapsedRealtime()-startupBegan} ms; startupScan=$scannedOnStartup")
                    FrontendRefreshRate.apply(this)
                    warmTabViews()
                    intent.takeIf { it.hasExtra("navigationTab") }?.let { navigateTab(it.getIntExtra("navigationTab", 0)); it.removeExtra("navigationTab") }
                    DualScreenCoordinator.libraryReady()
                    if(!scannedOnStartup)libraryRefreshWorker.execute { runCatching { prepareLaunchProfiles() } }
                }.onFailure { showDataError(it) }
            }
        }
    }
    private fun prepareLaunchProfiles() {
        val began=android.os.SystemClock.elapsedRealtime()
        val profiles=EmulatorProfileRepository(this)
        val playStation=systems.first { it.id=="psx" }
        if(profiles.selected(playStation)==null)profiles.forSystem(playStation).firstOrNull {
            it.component.packageName=="com.github.stenzek.duckstation" && it.stringExtras["bootPath"] in listOf("{file.uri}","{romUri}") && it.installed(this)
        }?.let { runCatching { profiles.select(playStation,it) } }
        android.util.Log.i("OrbitStartup","Emulator profiles ready in ${android.os.SystemClock.elapsedRealtime()-began} ms")
    }
    private fun publishScannedSystem(spec:SystemSpec,report:RomScanResult) {
        val scannedPage = page(report)
        val previous = pages[spec.id]
        if (showingCompanion || previous == scannedPage) return
        pages = pages + (spec.id to scannedPage)
        populated = if (scannedPage.games.isEmpty()) populated - spec else systems.filter { it == spec || it in populated }
        val countChanged = (previous?.games?.size ?: 0) != scannedPage.games.size
        if (countChanged) {
            tabViews.remove(0)?.let { screenHost?.removeView(it) };tabFocus.remove(0)
        }
        if (spec == system) {
            games = scannedPage.games
            error = scannedPage.errors.takeIf { it.isNotEmpty() }
                ?.let { "${games.size} games · ${it.size} folder errors. Check ROM folders & scanner." }
            tabViews.remove(6)?.let { screenHost?.removeView(it) };tabFocus.remove(6)
        }
        if (activeTab == 0 && (if (library) spec == system else countChanged)) render()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean("companionRole",showingCompanion); out.putBoolean("startupFinished", startupFinished); out.putInt("system", selected); out.putBoolean("library", library); out.putString("picking", pickingSystem?.id) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val startLibrary=intent.getBooleanExtra("startLibrary",false)
        intent.removeExtra("startLibrary")
        val reopenLibrary=showingCompanion && this !is CompanionActivity && !intent.getBooleanExtra("companionRole",false) &&
            !DualScreenCoordinator.hasLibraryHost() && !DualScreenCoordinator.startLibraryOnBottom(this)
        if(showingCompanion && (startLibrary || reopenLibrary)) {
            companionPanel?.destroy();companionPanel=null
            showingCompanion=false;ready=false
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            DualScreenCoordinator.attach(this)
            startLibrary(null)
            return
        }
        DualScreenCoordinator.attach(this)
        if(showingCompanion) { DualScreenCoordinator.restoreHostFocus();return }
        if (ready && intent.hasExtra("navigationTab")) {
            navigateTab(intent.getIntExtra("navigationTab", 0)); intent.removeExtra("navigationTab")
        }
    }
    override fun onStart() { super.onStart();companionPanel?.start(); DualScreenCoordinator.attach(this) }
    override fun onResume() {
        super.onResume()
        FrontendNavigationBar.apply(this)
        if(showingCompanion) {
            DualScreenCoordinator.companionResumed(this)
            return
        }
        refreshLibraryDock()
        if(ready)FrontendRefreshRate.apply(this)
        if(ready && activeTab==1)appsPanel?.resume()
        if(ready && library && activeTab==0) {
            artworkPanel?.show(system,games.getOrNull(gameCursor),true)
            importedScene?.showGame(games.getOrNull(gameCursor),true)
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) { super.onWindowFocusChanged(hasFocus);if(hasFocus)FrontendNavigationBar.apply(this) }
    override fun onPause() {
        pendingSplitSystemOpen=null;cancelSwapKey();artworkPanel?.pause();importedScene?.pause()
        if(!showingCompanion)DualScreenCoordinator.finishCompanionVideo()
        super.onPause()
    }
    override fun onStop() { companionPanel?.stop();DualScreenCoordinator.detach(this); super.onStop() }
    override fun onDestroy() { remoteSettingsDialog?.dismiss();mediaTransferPanel?.destroy();scraperSettingsPanel?.destroy();themeBackdrop?.destroy();gamePreloader?.destroy();importedSystems?.destroy();importedScene?.destroy();gameLogos.destroy();companionPanel?.destroy();if(showingCompanion)DualScreenCoordinator.panelDestroyed(this);colorfulSystems?.destroy(); artworkPanel?.destroy(); appsPanel?.destroy(); skipStartup.set(true); generation++; libraryRefreshWorker.shutdownNow();worker.shutdownNow(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
    }
    private fun surface(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(color); cornerRadius = dp(18).toFloat(); setStroke(dp(1), stroke) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        defaultFocusHighlightEnabled = false
        backgroundTintList = null
        text = label; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        background = surface(Color.TRANSPARENT, Color.rgb(38,49,65)); setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnFocusChangeListener { v, focus -> v.background = surface(Color.TRANSPARENT, if (focus) blue else Color.rgb(38,49,65)) }
        setOnClickListener { action() }
    }
    private fun LinearLayout.addButton(label: String, action: () -> Unit) { addView(button(label, action), LinearLayout.LayoutParams(-2, dp(52)).apply { setMargins(0, 0, dp(10), 0) }) }
    private fun render() {
        if (activeTab == 1) {
            val panel = appsPanel ?: AppsPanel(this, ::navigateTab, ::returnToPreviousPage,showNavigation=false).also { appsPanel = it }
            present(panel.view)
            panel.resume()
            return
        }
        if (activeTab == 3) { renderThemesPage();return }
        if (activeTab == 2 || activeTab==4) { renderSettingsPage(); return }
        nativeSystems=null
        if(!library)preloadKey=null
        importedScene?.destroy();importedScene=null
        importedSystems?.destroy();importedSystems=null
        artworkPanel?.destroy(); artworkPanel = null
        gameMediaRoot=null;gameMediaSurface=null
        gameList = null
        val nativeTheme=OrbitThemes.current()
        val screen=OrbitThemeArtwork.screen(this)
        val splitDisplays=nativeTheme.libraryScreen=="bottom" && (screen=="bottom" || DualScreenManager(this).displays().any {
            it.displayId!=android.view.Display.DEFAULT_DISPLAY && it.state!=android.view.Display.STATE_OFF
        })
        if(splitDisplays && !scanning && populated.isNotEmpty() && (!library || games.isNotEmpty())) {
            renderSplitLibrary(nativeTheme)
            return
        }
        val showSystemHero=screen!="bottom"
        if((nativeTheme.systemLayout=="artwork" || OrbitThemeArtwork.image(this,nativeTheme,"hero",target=screen)!=null) && !library && populated.isNotEmpty() && !scanning) {
            val scene=OrbitSystemsView(this,populated,populated.indexOf(systems.getOrNull(focusedSystemIndex)).coerceAtLeast(0),
                { pages[it.id]?.games?.size ?: 0 },::focusSystem,{ openSystem(systems.indexOf(it)) },showHero=showSystemHero)
            root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(nativeTheme.background);addView(scene,LinearLayout.LayoutParams(-1,-1)) }
            nativeSystems=scene;present(root);tabFocus[0]=scene
            scene.post { if(activeTab==0 && !library)scene.requestFocus() }
            return
        }
        val externalTheme=ImportedThemes.active(this)
        if(externalTheme!=null && !library && populated.isNotEmpty() && !scanning) {
            val scene=ImportedSystemsView(this,externalTheme,populated,populated.indexOf(systems.getOrNull(focusedSystemIndex)).coerceAtLeast(0),
                { pages[it.id]?.games?.size ?: 0 },{ focusedSystemIndex=systems.indexOf(it);preloadFirstGame(it);refreshThemeBackdrop();DualScreenCoordinator.refreshMedia() },{ openSystem(systems.indexOf(it)) })
            root=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                addView(scene,LinearLayout.LayoutParams(-1,-1))
            }
            importedSystems=scene;present(root);tabFocus[0]=scene
            scene.post { if(activeTab==0 && !library)scene.requestFocus() }
            return
        }
        val colorful = externalTheme==null && EsDeTheme.enabled(this)
        if (colorful && !library && populated.isNotEmpty() && !scanning) {
            colorfulSystems?.destroy()
            root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12,17,25)) }
            val view = ColorfulSystemsView(this,populated,populated.indexOf(systems.getOrNull(focusedSystemIndex)).coerceAtLeast(0),
                { pages[it.id]?.games?.size ?: 0 }, { focusedSystemIndex=systems.indexOf(it);DualScreenCoordinator.refreshMedia() }, { openSystem(systems.indexOf(it)) })
            colorfulSystems=view
            root.addView(view,LinearLayout.LayoutParams(-1,0,1f));present(root)
            tabFocus[0]=view;view.post { if(activeTab==0 && !library)view.requestFocus() }
            return
        }
        val colorfulModel = if (colorful && library) EsDeTheme.model(this,system.id) else null
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(if (library) 16 else 28), dp(12), dp(if (library) 16 else 28), dp(12)); setBackgroundColor(nativeTheme.background) }
        if(externalTheme!=null) { root.setPadding(0,0,0,0);root.setBackgroundColor(Color.TRANSPARENT) }
        if (colorfulModel != null) {
            root.setPadding(0,0,0,0)
            root.setBackgroundColor(colorfulModel.color("gamelist","background","color",colorfulModel.variable("systemColor","326BB1")))
        }
        if(library && externalTheme==null && colorfulModel==null) {
            root.setBackgroundColor(Color.TRANSPARENT)
            gameMediaRoot=root;gameMediaSurface=GameMediaSurface(this,root)
        }
        if(externalTheme==null || !library || populated.isEmpty() || scanning)present(root)
        if (library && colorfulModel == null && externalTheme==null) root.addView(text(system.title, 22f, blue).apply { typeface = Typeface.DEFAULT_BOLD })
        val scroll = ScrollView(this).apply { defaultFocusHighlightEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, if (library) -2 else 0, if (library) 0f else 1f))
        UserDataStore.accessError?.let { body.addView(text(it, 13f, Color.rgb(255, 185, 110))) }
        startupNotice?.let { body.addView(text(it, 13f, Color.rgb(255, 185, 110))) }
        if (!library) body.addView(text("COLLECTION  /  CHOOSE A SYSTEM", 12f, muted))
        if (populated.isEmpty()) {
            body.addView(text(if (scanning) "Loading your collection…" else "No games found. Add your ROM folders and scan from ROM library.", 20f, muted))
            body.addView(button("Settings") { openSettings() })
            return
        }
        if (!library) {
            val rail = SystemCarousel(this)
            val cards = LinearLayout(this)
            val focused=systems.getOrNull(focusedSystemIndex)?.takeIf { it in populated } ?: system
            populated.map { systems.indexOf(it) }.forEach { index ->
                val spec = systems[index]
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                    defaultFocusHighlightEnabled = false
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = surface(nativeTheme.surface, Color.rgb(38, 49, 65))
                    val artworkScreen=OrbitThemeArtwork.artworkScreen(this@MainActivity)
                    val showCardArt=if(showSystemHero)nativeTheme.systemHeroes[spec.id]?.containsKey(artworkScreen)==true ||
                        nativeTheme.systemImages[spec.id]?.containsKey(artworkScreen)==true
                    else nativeTheme.showSystemArtwork(spec.id,artworkScreen)
                    if(showCardArt) {
                        val console=ImageView(this@MainActivity).apply {
                            scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=spec.title
                            setImageDrawable(OrbitSystemArtwork.drawable(spec,nativeTheme,this@MainActivity,showHero=false))
                        }
                        addView(console,LinearLayout.LayoutParams(-1,0,1f))
                        OrbitThemeArtwork.requestSystemImage(this@MainActivity,nativeTheme,spec) { console.invalidate() }
                        OrbitConsoleImages.request(this@MainActivity,spec) { console.invalidate() }
                    }
                    addView(text(spec.title, 15f))
                    setOnFocusChangeListener { v, focused ->
                        if (focused) { tabFocus[0] = v; rail.reveal(v);focusSystem(spec) }
                        v.background = surface(nativeTheme.surface, if (focused) blue else Color.rgb(38, 49, 65))
                    }
                    setOnClickListener { carouselPosition = rail.scrollX; openSystem(index) }
                }
                cards.addView(card, LinearLayout.LayoutParams(dp(190), dp(164)).apply { setMargins(0, dp(10), dp(12), dp(12)) })
                if (spec == focused) tabFocus[0] = card
            }
            rail.addView(cards); body.addView(rail)
            rail.restore(carouselPosition) {
                if (startMenu?.isShowing != true) cards.getChildAt(populated.indexOf(focused).coerceAtLeast(0))?.requestFocus()
            }
        }
        if (library && (scanning || error != null)) {
            body.addView(text(if(scanning) "Scanning your folder…" else error!!, 12f, muted))
        }
        if (library && !scanning) {
            if (games.isEmpty() && error == null) body.addView(text("No games yet. Add a folder containing ${system.extensions.joinToString { ".$it" }} files.", 14f, muted))
            val entries = games
            gameCursor = 0
            val list = ListView(this).apply {
                defaultFocusHighlightEnabled = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusableInTouchMode = true
                isSmoothScrollbarEnabled = false
                setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                if(colorfulModel!=null) { divider=null;dividerHeight=0 }
                adapter = object : ArrayAdapter<Game>(this@MainActivity, android.R.layout.simple_list_item_1, entries) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val row=convertView as? FrameLayout ?: FrameLayout(this@MainActivity).apply {
                            addView(TextView(this@MainActivity),FrameLayout.LayoutParams(-1,-1))
                        }
                        val logoMode=UserDataStore.int(this@MainActivity,"nativeListLogos",0)==1
                        val label=(row.getChildAt(0) as TextView).apply {
                            textSize = UserDataStore.int(this@MainActivity, "gameListTextSize", 16).toFloat(); setTextColor(nativeTheme.text); gravity = Gravity.CENTER_VERTICAL
                            isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END
                            setPadding(dp(18), dp(8), dp(18), dp(8)); minHeight = dp(54)
                            text = entries[position].title
                            if(colorfulModel!=null) {
                                isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END
                                typeface=EsDeTheme.font(this@MainActivity,colorfulModel.property("gamelist","gamelist-textlist","fontPath","_inc/fonts/Gilroy-Regular.ttf"))
                                setTextColor(colorfulModel.color("gamelist","gamelist-textlist",if(position==gameCursor) "selectedColor" else "primaryColor"))
                                val themeFontSize=resources.displayMetrics.heightPixels*(colorfulModel.property("gamelist","gamelist-textlist","fontSize").toFloatOrNull() ?: 0.0407f)
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,themeFontSize)
                                minHeight=(themeFontSize*1.69f).toInt()
                                setPadding(dp(8),dp(3),dp(8),dp(3))
                            } else if(externalTheme!=null) {
                                val props=importedScene?.textListProperties().orEmpty()
                                setTextColor(EsDeTheme.parseColor(props[if(position==gameCursor) "selectedColor" else "primaryColor"] ?: props["textColor"] ?: "ffffff"))
                                val pixels=resources.displayMetrics.heightPixels*(props["fontSize"]?.toFloatOrNull() ?: 0.035f)
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,pixels);minHeight=(pixels*1.7f).toInt()
                                isSingleLine=true;ellipsize=android.text.TextUtils.TruncateAt.END
                            }
                        }
                        val importedProps=importedScene?.textListProperties().orEmpty()
                        val importedImage=importedProps["imageType"]?.split(Regex("[,\\s]+"))?.firstOrNull()?.takeIf { it!="none" }
                        val imageHeight=importedProps["itemSize"]?.split(Regex("\\s+"))?.getOrNull(1)?.toFloatOrNull()
                        row.layoutParams=AbsListView.LayoutParams(-1,if(externalTheme!=null && importedImage!=null)
                            ((imageHeight ?: 0.18f)*resources.displayMetrics.heightPixels).toInt().coerceAtLeast(dp(60))
                            else if(logoMode)dp(84) else label.minHeight)
                        gameLogos.bind(row,label,system,entries[position],logoMode || importedImage!=null,importedImage ?: "marquee")
                        row.background=if(position==gameCursor)GradientDrawable().apply {
                                setColor(Color.TRANSPARENT)
                                setStroke(dp(2),blue)
                                cornerRadius=dp(3).toFloat()
                            } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        return row
                    }
                }
                setOnItemClickListener { _, _, position, _ -> moveGameCursor(position);chooseEmulator(entries[position]) }
            }
            list.tag="orbit-game-list"
            gameList = list
            list.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (key == KeyEvent.KEYCODE_DPAD_UP && gameCursor == 0) false
                    else {
                        if (event.action == KeyEvent.ACTION_DOWN) moveGameCursor(gameCursor + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                        true
                    }
                } else false
            }
            if(externalTheme!=null) {
                val scene=EsDeScene(this,externalTheme,"gamelist",list,backgroundLayer=false)
                val preparedRoot=root
                scene.onReady={ if(!isDestroyed && activeTab==0 && library)present(preparedRoot) }
                importedScene=scene
                root.addView(scene,LinearLayout.LayoutParams(-1,0,1f))
                scene.initialise(system,entries.size,entries.firstOrNull())
            } else {
            val artwork = GameArtworkPanel(this, nativeTheme.systemLayout=="artwork", colorfulModel).also { artworkPanel = it }
            gameMediaSurface?.let { surface ->
                artwork.backgroundMediaChanged=surface::update
                artwork.backgroundMediaPaused=surface::pause
            }
            if(colorfulModel != null) {
                val content=ThemeFrame(this)
                content.place(View(this).apply { setBackgroundColor(colorfulModel.color("gamelist","gamelist-background","color","00000000")) },colorfulModel,"gamelist","gamelist-background","0 0 0.401 1")
                val title=text(colorfulModel.variable("systemName",system.title),22f).apply {
                    setTextColor(colorfulModel.color("gamelist","system-name","color"))
                    typeface=EsDeTheme.font(this@MainActivity,colorfulModel.property("gamelist","system-name","fontPath","_inc/fonts/Gilroy-Bold.ttf"))
                }
                content.place(title,colorfulModel,"gamelist","system-name","0.046875 0.074 0.3073 0.093",colorfulModel.property("gamelist","system-name","fontSize").toFloatOrNull() ?: 0.0426f)
                content.place(list,colorfulModel,"gamelist","gamelist-textlist","0.046875 0.213 0.3073 0.62")
                content.place(artwork.view,colorfulModel,"gamelist","orbit-cover-area",if(colorfulModel.variant.contains("metadata")) "0.401 0 0.599 1" else "0.401 0.08 0.599 0.84")
                root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
            } else {
                val content = LinearLayout(this)
                content.addView(list, LinearLayout.LayoutParams(0, -1, nativeTheme.listFraction))
                content.addView(artwork.view, LinearLayout.LayoutParams(0, -1, 1f-nativeTheme.listFraction))
                root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            artwork.show(system, entries.firstOrNull())
            }
            list.post { if (startMenu?.isShowing != true) { list.requestFocus(); if (entries.isNotEmpty()) list.setSelection(0) } }
        }
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        if (!library) footer.addView(text("D-PAD  Navigate     A  Open     SELECT  System settings     START  Menu", 11f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        if (!library) root.addView(footer)
    }

    private fun splitTileArrangement(prefix:String,key:String,fallback:SplitLibraryGrid.Arrangement):SplitLibraryGrid.Arrangement {
        val layouts=SplitLibraryGrid.Arrangement.entries
        return layouts[UserDataStore.cachedInt(prefix+key,fallback.ordinal).coerceIn(layouts.indices)]
    }

    private val physicalMediaKey="splitGameTilePhysicalMedia"
    private fun physicalMediaTiles()=UserDataStore.cachedInt(physicalMediaKey,0)==1

    private fun preloadSplitGames(grid:SplitLibraryGrid) {
        val spec=systems.getOrNull(focusedSystemIndex)
        val entries=spec?.let { pages[it.id]?.games }
        if(grid.scrollState!=RecyclerView.SCROLL_STATE_IDLE || grid.shortcutSelected || spec==null || entries.isNullOrEmpty()) {
            gameLogos.cancelPreload()
            return
        }
        gameLogos.preloadTiles(spec,physicalMediaTiles(),onReady={
            if(grid.isShown && activeTab==0 && !library && grid.scrollState==RecyclerView.SCROLL_STATE_IDLE &&
                matchingSplitGamePage(preparedSplitGameView,spec,entries,OrbitThemes.current())!=null)
                stagePreparedSplitGameView()
        }) {
            val tile=grid.cursorTile
            if(!grid.isShown || activeTab!=0 || library || grid.scrollState!=RecyclerView.SCROLL_STATE_IDLE ||
                grid.width==0 || grid.height==0 || tile==null || tile.right<=0 || tile.left>=grid.width)emptyList()
            else {
                val prefix="splitGameTile"
                val legacy=splitTileArrangement(prefix,"Size",SplitLibraryGrid.Arrangement.TWO_ROWS)
                val layout=splitTileArrangement(prefix,"Layout",legacy)
                // Leave room for the system title above the game grid.
                val columnWidth=layout.columnWidth(grid.width,(grid.height-dp(48)).coerceAtLeast(1))
                val visibleSlots=(((grid.width+columnWidth-1)/columnWidth)*layout.rows).coerceAtMost(32)
                val theme=OrbitThemes.current()
                val cached=listOfNotNull(tabViews[6],preparedSplitGameView)
                    .firstNotNullOfOrNull { matchingSplitGamePage(it,spec,entries,theme) }
                val slots=cached?.slots ?: SplitTileSlots(this,"games/${spec.id}",entries.map { it.uri.toString() }).also {
                    discardPreparedSplitGameView()
                    preparedSplitGameView=createSplitGrid(theme,true,spec,entries,it).first
                }
                slots.libraryIndex(0)?.let(entries::get)?.let { DualScreenCoordinator.prepareGameArtwork(this,spec,it) }
                (0 until visibleSlots).mapNotNull { slots.libraryIndex(it)?.let(entries::get) }
            }
        }
    }

    private data class SplitGamePage(
        val system:SystemSpec,val entries:List<Game>,val theme:OrbitThemes.Theme,
        val artworkGeneration:Int,val slots:SplitTileSlots,val grid:SplitLibraryGrid
    )
    private var preparedSplitGameView:LinearLayout?=null

    private fun stagePreparedSplitGameView() {
        val view=preparedSplitGameView ?: return
        val dock=libraryDock ?: return
        val host=screenHost ?: return
        if(view.parent!=null)return
        view.setPadding(view.paddingLeft,bottomGridTopPadding(),view.paddingRight,view.paddingBottom)
        padForLibraryDock(view,dock)
        view.visibility=View.INVISIBLE
        // An invisible page still measures, lays out and binds the first viewport.
        host.addView(view,FrameLayout.LayoutParams(-1,-1))
    }

    private fun discardPreparedSplitGameView() {
        preparedSplitGameView?.let { screenHost?.removeView(it) }
        preparedSplitGameView=null
    }

    private fun matchingSplitGamePage(view:View?,spec:SystemSpec,entries:List<Game>,theme:OrbitThemes.Theme) =
        (view?.tag as? SplitGamePage)?.takeIf {
            it.system==spec && it.entries===entries && it.theme===theme && it.artworkGeneration==gameLogos.generation
        }

    private fun showSplitGrid(grid:SplitLibraryGrid,initialSlot:Int,reused:Boolean=false,openingGames:Boolean=false) {
        if(openingGames) {
            val page=root.tag as? SplitGamePage
            val first=page?.slots?.libraryIndex(initialSlot)?.let(page.entries::get)
            if(first!=null)DualScreenCoordinator.showGameArtwork(this,page.system,first)
        }
        present(root)
        tabFocus[if(library)6 else 0]=grid
        if(reused)grid.reopen() else grid.select(initialSlot,animate=false)
        if(openingGames)grid.cascadeIn()
        if(grid.isShown && startMenu?.isShowing!=true && !grid.requestFocus())
            grid.post { if(grid.isShown && startMenu?.isShowing!=true)grid.requestFocus() }
    }

    private fun renderSplitLibrary(theme:OrbitThemes.Theme) {
        val inGames=library
        val openingGames=inGames && tabViews[0]?.isShown==true
        val initialSlot=if(inGames)0 else activeSplitGrid()?.cursorPosition ?: 0
        val entries=games
        val spec=system
        if(openingGames) {
            val staged=preparedSplitGameView
            val available=listOfNotNull(tabViews[6] as? LinearLayout,staged)
            for(view in available) {
                val page=matchingSplitGamePage(view,spec,entries,theme) ?: continue
                if(view===staged)preparedSplitGameView=null else discardPreparedSplitGameView()
                gameCursor=0
                root=view
                showSplitGrid(page.grid,0,reused=view.parent!=null,openingGames=true)
                return
            }
            discardPreparedSplitGameView()
        }
        val libraryKeys=if(inGames)entries.map { it.uri.toString() } else populated.map { it.id }
        val slots=SplitTileSlots(this,if(inGames)"games/${spec.id}" else "systems",libraryKeys)
        val (view,grid)=createSplitGrid(theme,inGames,spec,entries,slots)
        if(inGames)gameCursor=0
        root=view
        showSplitGrid(grid,initialSlot,openingGames=openingGames)
    }

    private fun createSplitGrid(
        theme:OrbitThemes.Theme,inGames:Boolean,spec:SystemSpec,entries:List<Game>,slots:SplitTileSlots
    ):Pair<LinearLayout,SplitLibraryGrid> {
        val systemsHere=populated
        val prefix=if(inGames)"splitGameTile" else "splitSystemTile"
        val scaleKey=prefix+"Scale"
        val discSpinKey="splitGameDiscSpin"
        fun spinDiscs()=UserDataStore.cachedInt(discSpinKey,0)==1
        val legacyLayout=splitTileArrangement(prefix,"Size",SplitLibraryGrid.Arrangement.TWO_ROWS)
        val initialLayout=splitTileArrangement(prefix,"Layout",legacyLayout)
        val sizes=SplitLibraryGrid.TileSize.entries
        val oldScale=if(legacyLayout>=SplitLibraryGrid.Arrangement.ONE_ROW)
            SplitLibraryGrid.TileSize.EXTRA_LARGE else SplitLibraryGrid.defaultSize
        val initialSize=sizes[UserDataStore.cachedInt(scaleKey,oldScale.ordinal).coerceIn(sizes.indices)]
        lateinit var tileMenu:SplitTileMenu
        lateinit var grid:SplitLibraryGrid
        val changeTileSize:(SplitLibraryGrid.TileSize)->Unit={ next ->
            runCatching { UserDataStore.put(this,"settings",scaleKey,next.ordinal) }
                .onSuccess { grid.resize(next) }.onFailure(::showDataError)
        }
        val changeLayout:(SplitLibraryGrid.Arrangement,Boolean)->Unit={ next,animate ->
            runCatching { UserDataStore.put(this,"settings",prefix+"Layout",next.ordinal) }
                .onSuccess { grid.arrange(next,animate) }.onFailure(::showDataError)
        }
        grid=SplitLibraryGrid(this,theme,slots,
            title={ if(inGames)entries[it].title else systemsHere[it].title },
            bind={ cell,label,index,artworkPending ->
                if(inGames) {
                    val physicalMedia=physicalMediaTiles()
                    gameLogos.bind(cell,label,spec,entries[index],true,
                        mediaType=if(physicalMedia)"physicalmedia" else "cover",
                        fallbackMediaType=if(physicalMedia)"cover" else null,
                        spinDiscs=physicalMedia && spinDiscs(),showTitleWhileLoading=false,
                        onArtworkPending=artworkPending)
                } else {
                    artworkPending(false)
                    bindSplitConsole(cell,label,systemsHere[index],theme)
                }
            },onSelected={ position ->
                slots.libraryIndex(position)?.let { index ->
                    if(inGames)gameCursor=index
                    else focusedSystemIndex=systems.indexOf(systemsHere[index])
                }
                if(!inGames)preloadSplitGames(grid)
                DualScreenCoordinator.refreshMedia()
            },initialSize=initialSize,initialArrangement=initialLayout,open={ position ->
                val index=slots.libraryIndex(position)
                when {
                    tileMenu.isOpen -> tileMenu.close()
                    grid.emptyShortcutSelected -> tileMenu.show()
                    index==null -> slots.openApp(position)
                    inGames -> chooseEmulator(entries[index])
                    else -> openSplitSystem(grid,systems.indexOf(systemsHere[index]))
                }
            },hold={ tileMenu.show() },changeLayout={ changeLayout(it,true) })
        grid.tag=if(inGames)"orbit-game-list" else "orbit-systems-grid"
        if(!inGames)grid.addOnScrollListener(object:RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView:RecyclerView,newState:Int) {
                if(!grid.isShown || activeTab!=0 || library)return
                if(newState==RecyclerView.SCROLL_STATE_IDLE)preloadSplitGames(grid)
                else gameLogos.cancelPreload()
            }
        })
        fun targetSystem()=if(inGames)spec else systemsHere[requireNotNull(grid.selectedLibraryIndex)]
        fun targetGame()=if(inGames)grid.selectedLibraryIndex?.let(entries::get) else null
        tileMenu=SplitTileMenu(this,theme,grid,inGames,
            launchScreen={
                if(grid.shortcutSelected)OrbitThemeArtwork.screen(this)
                else GameLaunchDisplay.resolve(targetGame()?.let { GameLaunchDisplay.game(this,spec,it) },GameLaunchDisplay.system(this,targetSystem()))
            },
            chooseScreen={ screen ->
                runCatching { GameLaunchDisplay.save(this,targetSystem(),targetGame(),screen) }.onFailure(::showDataError)
            },launchOnce={ screen ->
                if(grid.shortcutSelected)slots.openApp(grid.cursorPosition,screen)
                else targetGame()?.let { chooseEmulator(it,screen) }
            },
            resizeTiles={ changeTileSize(grid.size.next()) },
            changeLayout={ changeLayout(grid.arrangement.next(),false) },
            tileArtworkLabel={ if(physicalMediaTiles())"Physical media" else "Box art" },
            toggleTileArtwork={
                runCatching { UserDataStore.put(this,"settings",physicalMediaKey,if(physicalMediaTiles())0 else 1) }
                    .onSuccess { grid.refreshTiles() }.onFailure(::showDataError)
            },discSpinEnabled=::spinDiscs,toggleDiscSpin={
                runCatching { UserDataStore.put(this,"settings",discSpinKey,if(spinDiscs())0 else 1) }
                    .onSuccess { grid.refreshTiles() }.onFailure(::showDataError)
            },openSettings={ showSystemMenu(targetSystem(),settingsShortcutHost()) })
        val view=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            val bottom=OrbitThemeArtwork.screen(this@MainActivity)=="bottom"
            setPadding(0,0,0,dp(if(bottom && !inGames)29 else 3))
            if(inGames)addView(text(spec.title,20f,theme.accent),LinearLayout.LayoutParams(-1,-2))
            addView(tileMenu,LinearLayout.LayoutParams(-1,0,1f).apply { bottomMargin=dp(3) })
            if(inGames)tag=SplitGamePage(spec,entries,theme,gameLogos.generation,slots,grid)
        }
        return view to grid
    }

    private fun bindSplitConsole(cell:FrameLayout,label:TextView,spec:SystemSpec,theme:OrbitThemes.Theme) {
        val image=(cell.getChildAt(1) as? ImageView) ?: ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            cell.addView(this,FrameLayout.LayoutParams(-1,-1))
        }
        cell.tag=spec.id
        val screen=OrbitThemeArtwork.artworkScreen(this)
        val visible=theme.showSystemArtwork(spec.id,screen)
        fun bind() {
            if(cell.tag!=spec.id)return
            val bitmap=if(visible)OrbitThemeArtwork.systemImage(this,theme,spec,screen) ?: OrbitConsoleImages.cached(spec) else null
            image.setImageBitmap(bitmap)
            image.visibility=if(bitmap==null)View.INVISIBLE else View.VISIBLE
            label.visibility=if(bitmap==null)View.VISIBLE else View.INVISIBLE
        }
        bind()
        if(visible) {
            OrbitThemeArtwork.requestSystemImage(this,theme,spec,screen,::bind)
            OrbitConsoleImages.request(this,spec,::bind)
        }
    }
    private fun chooseSystem() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val search = EditText(this).apply { hint = "Search ${populated.size} systems"; setSingleLine() }
        val list = ListView(this)
        var matches = systems.indices.toList()
        fun update(query: String) {
            matches = populated.map { systems.indexOf(it) }.filter { systems[it].title.contains(query, true) || systems[it].id.contains(query, true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, matches.map { systems[it].title })
        }
        panel.addView(search); panel.addView(list, LinearLayout.LayoutParams(-1, dp(270)))
        val dialog = AlertDialog.Builder(this).setTitle("Choose system").setView(panel).setNegativeButton("Close", null).create()
        list.setOnItemClickListener { _, _, position, _ -> dialog.dismiss(); openSystem(matches[position]) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        update(""); dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN); dialog.show()
    }
    private fun scan(force: Boolean = false) {
        val spec = system; val ticket = ++generation
        scanning = true; error = null; games = emptyList(); render()
        worker.execute {
            val result = runCatching { if (force) repository.scanReport(spec) else repository.cached(spec) ?: repository.scanReport(spec) }
            val available = runCatching { repository.populatedSystems() }.getOrDefault(populated)
            val next = if (spec in available || available.isEmpty()) spec else available.first()
            val displayResult = if (next == spec) result else runCatching { repository.cached(next) ?: RomScanResult(emptyList(), emptyList()) }
            val loaded = available.associate { entry -> entry.id to page((if (entry == spec) result.getOrNull() else null) ?: repository.cached(entry) ?: RomScanResult(emptyList(), emptyList())) }
            runOnUiThread {
                if (ticket == generation && !isDestroyed) {
                    populated = available; selected = systems.indexOf(next)
                    pages = loaded
                    scanning = false; games = displayResult.getOrNull()?.games.orEmpty().map { Game(it.title, android.net.Uri.parse(it.uri), it.fileName) }
                    error = displayResult.exceptionOrNull()?.let { "Could not scan ROM folders: ${it.message}" } ?: displayResult.getOrNull()?.errors?.takeIf { it.isNotEmpty() }?.let { "${games.size} games · ${it.size} folder errors. Open ROM folders & scanner for details." }; render()
                    DualScreenCoordinator.libraryReady()
                }
            }
        }
    }
    @Deprecated("Platform folder picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==108) {
            val callback=themeImageResult;themeImageResult=null
            callback?.invoke(if(resultCode==RESULT_OK)data?.data else null)
            return
        }
        if(requestCode==107) {
            val callback=romFolderResult;romFolderResult=null
            val uri=if(resultCode==RESULT_OK)data?.data else null
            if(uri==null)callback?.invoke(null)
            else runCatching { RomAccessManager.persistFolderAccess(this,data!!,uri) }
                .onSuccess { callback?.invoke(uri) }.onFailure { showDataError(it);callback?.invoke(null) }
            return
        }
        if (requestCode == 151) {
            if (resultCode == RESULT_OK && data != null) mediaTransferPanel?.folderSelected(data)
            return
        }
        if (requestCode == 106) {
            if (resultCode == RESULT_OK) refreshTransferredMedia()
            return
        }
        if (requestCode == 103) { scan(); return }
        if (requestCode == 102) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            worker.execute {
                val result = runCatching {
                    val source = contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024) { "System JSON must be smaller than 2 MB" }
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Could not read JSON")
                    UserDataStore.importSystem(this, source).also { EmulatorProfileRepository(this) }
                }
                runOnUiThread {
                    if (!isDestroyed) result.onSuccess { showSettingsMessage("System profiles imported", it) }
                        .onFailure { showDataError(it) }
                }
            }
            return
        }
        if (requestCode == 101) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { showDataError(it); return }
            val progress = ProgressDialog(this).apply {
                setTitle("Moving user data")
                setMessage("Copying and verifying files…")
                isIndeterminate = true
                setCancelable(false)
                styleArtworkWindow(this@MainActivity)
                show()
            }
            worker.execute {
                val result = runCatching { UserDataStore.migrate(this, uri) }
                runOnUiThread {
                    if (!isDestroyed) {
                        progress.dismiss()
                        result.onSuccess { name ->
                            scan()
                            showSettingsMessage("User data folder changed",
                                "Your frontend data is now directly in $name. Platform JSON files are in its systems folder. The previous copy has been kept.")
                        }.onFailure { showDataError(it) }
                    }
                }
            }
            return
        }
        if (requestCode != 100) return
        val spec = pickingSystem ?: system; pickingSystem = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            RomAccessManager.persistFolderAccess(this, data, uri)
            repository.save(spec, uri)
        }.onFailure { Toast.makeText(this, "Folder access could not be saved. Please choose another folder.", Toast.LENGTH_LONG).show() }
        scan()
    }
    private fun chooseEmulator(game: Game, screenOverride:String?=null) {
        val emulatorRepository=EmulatorProfileRepository(this)
        val savedProfile=emulatorRepository.selectedForGame(system,game)
        val profile = savedProfile ?: InstalledEmulators.available(this,emulatorRepository.forSystem(system))
            .filter { candidate -> candidate.installed(this) &&
                (candidate.filenameRegex?.let { Regex(it,RegexOption.IGNORE_CASE).matches(game.fileName) }
                    ?: (game.fileName.substringAfterLast('.').lowercase() in candidate.extensions)) }
            .sortedBy { it.component.packageName.startsWith("com.retroarch") }
            .firstOrNull()
        if (profile != null) {
            runCatching {
                EmulatorLaunchManager(this).launch(game, GameLaunchDisplay.display(this,system,game,screenOverride), profile)
                if(savedProfile==null)emulatorRepository.select(system,profile)
            }
                .onFailure { AlertDialog.Builder(this).setTitle("Unable to launch ${profile.name}").setMessage(it.message).setPositiveButton("OK", null).show() }
            return
        }
        if (emulatorRepository.forSystem(system).isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("No compatible emulator")
                .setMessage("No visible, installed emulator accepts ${game.fileName}. Choose an emulator in Game Settings or install a compatible emulator.")
                .setPositiveButton("OK",null).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(game.uri, "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val matches = packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != packageName }
        if (matches.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No compatible ROM handler").setMessage("Install an emulator that accepts ROM content URIs through Android's Open with interface. Emulators requiring custom launch commands need a dedicated integration in a later version.").setPositiveButton("OK", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("Open ${game.title}").setItems(matches.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
            val info = matches[index].activityInfo
            runCatching { EmulatorLaunchManager(this).launch(game, GameLaunchDisplay.display(this,system,game,screenOverride), ComponentName(info.packageName, info.name)) }
                .onFailure { AlertDialog.Builder(this).setTitle("Unable to launch").setMessage(it.message ?: "Emulator launch failed").setPositiveButton("OK", null).show() }
        }.show()
    }
    private fun selectEmulator(onSelected: (() -> Unit)? = null) {
        val menu=EmulatorSettings.MenuWindow(this)
        EmulatorSettings.showForSystemIn(menu,system,{
            if(onSelected!=null) {
                menu.dialog.dismiss()
                onSelected()
            }
        }, { menu.dialog.dismiss() })
    }
    private fun pickSystemProfile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 102, popupLaunchOptions())
    }
    private fun showDataError(error: Throwable) {
        showSettingsMessage("User data could not be saved", error.message ?: "Check folder access and try again.")
    }
    private fun showSettingsMessage(title: String, message: String) {
        val host = popupContext()
        val dialog = AlertDialog.Builder(host).setTitle(title).setMessage(message).setPositiveButton("OK", null).create()
        dialog.styleArtworkWindow(host)
        dialog.show()
        trackPopup(dialog)
        SelectionStyle.apply(dialog.getButton(AlertDialog.BUTTON_POSITIVE))
        FrontendNavigationBar.apply(host, dialog.window)
    }
    private fun exitOrbit() {
        startMenu?.dismiss()
        DualScreenCoordinator.exit()
        getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
    }
    private fun pickRomFolder(result: (Uri?) -> Unit) {
        romFolderResult = result
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),107,popupLaunchOptions())
        }.onFailure { romFolderResult = null; showDataError(it) }
    }
    private fun selectedTileSettingsScreen():String? {
        val grid=activeSplitGrid() ?: return null
        if(grid.shortcutSelected)return null
        val spec=if(library)system else systems.getOrNull(focusedSystemIndex) ?: return null
        val gameScreen=if(library)games.getOrNull(gameCursor)?.let { GameLaunchDisplay.game(this,spec,it) } else null
        return gameScreen ?: GameLaunchDisplay.system(this,spec)
    }
    /** The screen Settings should open on: the Settings shortcuts choice always wins when it is set. */
    private fun settingsShortcutScreen():String? =
        UserDataStore.cachedString("settings","settingsShortcutScreen") ?: selectedTileSettingsScreen()
    private fun settingsShortcutHost():Activity {
        val screen=settingsShortcutScreen() ?: return this
        if(screen==OrbitThemeArtwork.screen(this))return this
        val display=runCatching { DualScreenManager(this).displayForScreen(screen) }.getOrNull() ?: return this
        return DualScreenCoordinator.companionOnDisplay(display)
            ?.takeIf { it.window.decorView.windowVisibility==View.VISIBLE } ?: this
    }
    private var settingsRetryPending=false
    private fun openSettings(host:Activity=settingsShortcutHost()) {
        // Right after a restart the other screen may not be ready yet; wait briefly once before
        // falling back to this screen, so the chosen screen is still used.
        val wanted=settingsShortcutScreen()
        if(host===this && wanted!=null && wanted!=OrbitThemeArtwork.screen(this) && !settingsRetryPending) {
            settingsRetryPending=true
            window.decorView.postDelayed({
                settingsRetryPending=false
                if(!isFinishing && !isDestroyed) {
                    val retry=settingsShortcutHost()
                    if(retry!==this)openSettings(retry) else navigateTab(2)
                }
            },350)
            return
        }
        if(host===this) {
            navigateTab(2)
            return
        }
        startMenu?.dismiss()
        val menu=EmulatorSettings.MenuWindow(host,fullScreen=true)
        startMenu=menu.dialog
        remoteSettingsDialog=menu.dialog
        remoteSettingsHost=host
        menu.dialog.setOnDismissListener { settingsWindowClosed(menu.dialog) }
        renderSettingsPage(menu,menu.dialog::dismiss)
    }
    private fun settingsWindowClosed(dialog:AlertDialog) {
        if(startMenu===dialog)startMenu=null
        if(remoteSettingsDialog===dialog) {
            remoteSettingsDialog=null
            remoteSettingsHost=null
            DualScreenCoordinator.restoreHostFocus()
        }
        if(activeTab==0 && library)(gameList ?: activeSplitGrid())?.requestFocus()
        DualScreenCoordinator.refreshMedia()
    }
    private fun rememberPage(destination:PageDestination) {
        val current=PageDestination(activeTab,activeTab==0 && library)
        if(current==destination)return
        if(pageHistory.lastOrNull()!=current)pageHistory.add(current)
        if(pageHistory.size>50)pageHistory.removeAt(0)
    }
    private fun returnToPreviousPage() {
        val previous=if(pageHistory.isNotEmpty())pageHistory.removeAt(pageHistory.lastIndex) else PageDestination(0)
        showPage(previous)
    }
    private fun navigateTab(tab: Int) {
        val destination=PageDestination(tab)
        rememberPage(destination)
        showPage(destination)
    }
    private fun showPage(destination:PageDestination) {
        activeSplitTileMenu()?.close()
        activeSplitGrid()?.cancelMove()
        val tab=destination.tab
        artworkPanel?.pause();importedScene?.pause()
        currentFocus?.let { tabFocus[if(activeTab==0 && library)6 else activeTab] = it }
        val closingMenu=startMenu?.takeIf { it.isShowing }
        activeTab = tab
        library = destination.library
        val slot=if(tab==0 && library)6 else tab
        // System views own their controller handlers; rebuild them after leaving a game list.
        val cached = tabViews[slot].takeUnless {
            slot==0 && nativeSystems==null && importedSystems==null && colorfulSystems==null && it?.findViewWithTag<View>("orbit-systems-grid")==null
        }
        if (cached != null) {
            if(slot==6)gameList=cached.findViewWithTag<View>("orbit-game-list") as? AbsListView
            present(cached)
            if(slot==0)activeSplitGrid()?.let(::preloadSplitGames)
            if(tab==3)themeGallery?.initialFocus?.requestFocus()
            else if (tabFocus[slot]?.requestFocus() != true) cached.requestFocus()
            if(slot==6) {
                artworkPanel?.show(system,games.getOrNull(gameCursor))
                importedScene?.showGame(games.getOrNull(gameCursor))
            }
        } else {
            val cursor=gameCursor
            render()
            if(slot==6)(gameList ?: activeSplitGrid())?.post { moveGameCursor(cursor) }
        }
        if(closingMenu!=null) {
            val destination=screenHost ?: window.decorView
            val finish=Runnable {
                if(closingMenu.isShowing) {
                    closingMenu.window?.setWindowAnimations(0)
                    closingMenu.dismiss()
                }
                if(!isDestroyed && activeTab==tab) {
                    if(tab==3)themeGallery?.initialFocus?.requestFocus()
                    else tabFocus[slot]?.requestFocus()
                }
            }
            // Keep the menu covering the old system frame until the destination is rendered.
            if(destination.isHardwareAccelerated) {
                destination.viewTreeObserver.registerFrameCommitCallback { runOnUiThread(finish) }
                destination.invalidate()
            } else destination.postOnAnimation(finish)
        }
    }
    private fun themeChanged(keepSettings:Boolean=false) {
        gameLogos.cancelPreload()
        discardPreparedSplitGameView()
        nativeSystems=null
        colorfulSystems?.destroy();colorfulSystems=null
        importedSystems?.destroy();importedSystems=null;importedScene?.destroy();importedScene=null
        for(slot in if(keepSettings)listOf(0,3,6) else listOf(0,2,6)) {
            tabViews.remove(slot)?.let { screenHost?.removeView(it) };tabFocus.remove(slot)
        }
        preloadKey=null;gamePreloader?.destroy();gamePreloader=null
        val slot=if(activeTab==0 && library)6 else activeTab
        tabViews[slot]?.let { present(it) } ?: if(activeTab==0)render() else Unit
        DualScreenCoordinator.refreshMedia(true)
    }
    private fun themeApplied(keepSettings:Boolean=false) {
        themeChanged(keepSettings)
        DualScreenCoordinator.themeApplied()
    }
    fun refreshGameMediaOptions() {
        if(ready && library)artworkPanel?.show(system,games.getOrNull(gameCursor))
        DualScreenCoordinator.refreshMedia()
    }
    private fun refreshTransferredMedia() {
        GameArtworkPanel.clearMediaCache(); EsDeScene.clearMediaCache(); gameLogos.clear(); preloadKey = null
        artworkPanel?.show(system, games.getOrNull(gameCursor), true)
        importedScene?.showGame(games.getOrNull(gameCursor), true)
        refreshGameTiles()
        DualScreenCoordinator.refreshMedia(true)
    }
    fun mediaArtworkVisibilityChanged(themeId:String) {
        tabViews.remove(3)?.let { screenHost?.removeView(it) };tabFocus.remove(3)
        if(OrbitThemes.current().id!=themeId)return
        fun redraw(view:View) {
            view.invalidate()
            if(view is ViewGroup)for(i in 0 until view.childCount)redraw(view.getChildAt(i))
        }
        screenHost?.let(::redraw)
        DualScreenCoordinator.refreshMedia()
    }
    fun mediaArtworkChanged(themeId:String) {
        if(OrbitThemes.current().id==themeId)themeChanged(keepSettings=true)
        else {
            tabViews.remove(3)?.let { screenHost?.removeView(it) };tabFocus.remove(3)
        }
    }
    private fun renderThemesPage() {
        val gallery=ThemeGalleryPanel(this) {
            themeApplied()
        }
        themeGallery=gallery
        present(gallery.view);tabFocus[3]=gallery.initialFocus
        if(!warmingTabs)gallery.initialFocus.post { if(activeTab==3)gallery.initialFocus.requestFocus() }
    }
    private fun renderSettingsPage(popup:EmulatorSettings.MenuWindow?=null,back:()->Unit={}) {
        val scrapingPage=popup==null && activeTab==4
        val topTabs = !scrapingPage
        val wideOptions = topTabs && (popup?.activity ?: this).resources.configuration.screenWidthDp >= 680
        val pageTab=activeTab
        var pageCategory=if(scrapingPage)scrapingCategory else settingsCategory
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (topTabs) setPadding(dp(16), dp(8), dp(16), dp(8))
            else setPadding(dp(28), dp(12), dp(28), dp(12))
            setBackgroundColor(Color.rgb(12, 17, 25))
        }
        if (scrapingPage) root.addView(text("Scraping", 24f).apply { typeface = Typeface.DEFAULT_BOLD })
        val categories = LinearLayout(this).apply { orientation = if (topTabs) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val sidebar = if (topTabs) null else ScrollView(this).apply { defaultFocusHighlightEnabled = false; addView(categories) }
        val detailScroll = ScrollView(this).apply {
            defaultFocusHighlightEnabled = false
            isFillViewport = topTabs
        }
        val detailHost=FrameLayout(this).apply { addView(detailScroll,FrameLayout.LayoutParams(-1,-1)) }
        if(popup==null) { if(!scrapingPage)settingsDetailView = detailHost else scrapingDetails=detailHost }
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (topTabs) setPadding(dp(12), dp(6), dp(12), dp(8))
            else setPadding(dp(20), dp(10), dp(20), dp(16))
            background = surface(Color.rgb(17, 25, 37))
        }
        detailScroll.addView(detail)
        if (sidebar == null) {
            val tabBar = HorizontalScrollView(this).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                defaultFocusHighlightEnabled = false
                addView(categories)
            }
            root.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            root.addView(detailHost, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val columns = LinearLayout(this)
            columns.addView(sidebar, LinearLayout.LayoutParams(dp(245), -1).apply { rightMargin = dp(22) })
            columns.addView(detailHost, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val scraperCategories = listOf("Account", "Scraping", "Scrape Mode", "Systems", "Region", "Languages")
        val names = if (scrapingPage) scraperCategories else settingsCategories
        val categoryButtons = mutableListOf<Button>()
        val optionRows = mutableListOf<View>()
        var updatingCategory=false
        data class Choice(val title: String, val description: String = "", val value: String = "",
            val section: String? = null, val choose: () -> Unit)
        data class Subpage(val title: String, val back: () -> Unit, val choices: List<Choice>? = null, val description: String = "")
        var returnFromSubpage: (() -> Unit)? = null
        var embeddedView: View? = null
        var embeddedFocus: View? = null
        var embeddedKeys: ((KeyEvent) -> Boolean)? = null
        var embeddedConfirmDown: Long? = null
        fun clearEmbeddedPage() {
            embeddedView?.let(detailHost::removeView)
            embeddedView = null
            embeddedFocus = null
            embeddedKeys = null
            embeddedConfirmDown = null
        }
        fun embedPage(content: View, back: () -> Unit, focus: View? = null, keys: (KeyEvent) -> Boolean = { false },
            title: String? = null, showBack: Boolean = true) {
            updatingCategory = true
            clearEmbeddedPage()
            returnFromSubpage = back
            detailScroll.visibility = View.GONE
            val frame = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = surface(Color.rgb(17, 25, 37))
                if (showBack) {
                    addView(LinearLayout(this@MainActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(button("‹ Back", back), LinearLayout.LayoutParams(dp(120), dp(44)))
                        if (title != null) {
                            addView(text(title, 20f).apply { setPadding(dp(12), 0, dp(12), 0) },
                                LinearLayout.LayoutParams(0, -2, 1f))
                        }
                    })
                }
                (content.parent as? ViewGroup)?.removeView(content)
                addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            embeddedView = frame
            embeddedFocus = focus
            embeddedKeys = keys
            detailHost.addView(frame, FrameLayout.LayoutParams(-1, -1))
            (focus ?: content).requestFocus()
            updatingCategory = false
        }
        fun showCategory(index: Int, enter: Boolean = false, screenMediaRow: Int? = null, restoreOption: Int? = null,
            romFoldersOption: Int? = null, subpage: Subpage? = null) {
            updatingCategory=true
            val sameCategory = pageCategory == index && optionRows.isNotEmpty() &&
                screenMediaRow == null && romFoldersOption == null && subpage == null && returnFromSubpage == null
            returnFromSubpage = subpage?.back
            clearEmbeddedPage()
            if (!scrapingPage) romLibraryScan?.onUpdate = null
            val focusedOption = optionRows.indexOfFirst { it.hasFocus() }
            val previousScroll = if (sameCategory) detailScroll.scrollY else 0
            // Keep focus from falling back to the category buttons while replacing option rows.
            detail.isFocusableInTouchMode = true
            if (focusedOption >= 0) detail.requestFocus()
            pageCategory = index
            if(!scrapingPage)settingsCategory=index else scrapingCategory=index
            if(!warmingTabs && activeTab==2)DualScreenCoordinator.refreshMedia()
            if(popup==null) { if(!scrapingPage)settingsCategoryView = categoryButtons.getOrNull(index) else scrapingSidebar=categoryButtons.getOrNull(index) }
            categoryButtons.forEachIndexed { i, row -> row.background = surface(Color.TRANSPARENT, if (i == index) blue else Color.rgb(38,49,65)) }
            detail.removeAllViews(); detailScroll.scrollTo(0, 0)
            detailScroll.visibility=View.VISIBLE
            optionRows.clear()
            val heading = when {
                subpage != null -> subpage.title
                romFoldersOption != null -> "ROM folders & scanner"
                screenMediaRow != null -> "Screen media"
                else -> names[index]
            }
            if (!topTabs || subpage != null || romFoldersOption != null || screenMediaRow != null) {
                val headingText = text(heading, if (topTabs) 20f else 24f).apply { typeface = Typeface.DEFAULT_BOLD }
                if (subpage == null) detail.addView(headingText)
                else detail.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(headingText, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(button("‹ Back") { subpage.back() }.apply { isFocusable = false },
                        LinearLayout.LayoutParams(dp(112), dp(42)))
                })
            }
            var first: View? = null
            fun option(title: String, description: String, value: String = "", action: () -> Unit):LinearLayout {
                val row = LinearLayout(this).apply {
                    orientation = if (wideOptions && description.isNotBlank()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                    defaultFocusHighlightEnabled = false
                    if (topTabs) {
                        minimumHeight = dp(48)
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                    } else setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = surface(Color.TRANSPARENT, Color.rgb(38,49,65))
                    val titleView = text(if (value.isBlank()) title else "$title  ·  $value", 16f)
                    val descriptionView = description.takeIf { it.isNotBlank() }?.let { text(it, 12f, muted) }
                    if (topTabs) {
                        titleView.setPadding(0, 0, 0, 0)
                        descriptionView?.setPadding(0, 0, 0, 0)
                    }
                    if (orientation == LinearLayout.HORIZONTAL && descriptionView != null) {
                        addView(titleView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
                        addView(descriptionView, LinearLayout.LayoutParams(0, -2, 1f))
                    } else {
                        addView(titleView)
                        descriptionView?.let { addView(it) }
                    }
                    setOnFocusChangeListener { v, focus -> v.background = surface(Color.TRANSPARENT, if (focus) blue else Color.rgb(38,49,65)) }
                    setOnClickListener { action() }
                }
                detail.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (topTabs) 4 else 10) })
                optionRows.add(row)
                if (first == null) first = row
                return row
            }
            fun screenMediaLink() {
                val rowIndex = optionRows.size
                option("Screen media", "Choose backgrounds and media for the top and bottom screens.") {
                    showCategory(index, enter = true, screenMediaRow = rowIndex)
                }
            }
            fun showChoices(title: String, parent: () -> Unit, choices: List<Choice>, description: String = "",
                focused: Int? = null) {
                showCategory(index, enter = true, restoreOption = focused, subpage = Subpage(title, parent, choices, description))
            }
            fun chooseFromList(title: String, labels: List<String>, selected: Int, parent: () -> Unit, choose: (Int) -> Unit) {
                val list = ListView(this).apply {
                    isFocusableInTouchMode = true
                    defaultFocusHighlightEnabled = false
                    selector = artworkOutline(this@MainActivity)
                    setDrawSelectorOnTop(true)
                    adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1, labels) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                            (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE) }
                    }
                    setOnItemClickListener { _, _, position, _ -> choose(position) }
                    setSelection(selected.coerceAtLeast(0))
                }
                embedPage(list, parent, list, keys = { event ->
                    if (event.keyCode != KeyEvent.KEYCODE_BUTTON_A) false
                    else {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
                            choose(list.selectedItemPosition.takeIf { it in labels.indices } ?: selected.coerceAtLeast(0))
                        }
                        true
                    }
                }, title = title)
            }
            fun returnToParent(row: Int): () -> Unit = { showCategory(index, enter = true, restoreOption = row) }
            fun isCurrent(parent: () -> Unit): Boolean =
                activeTab == pageTab && root.isShown && returnFromSubpage === parent
            val screenTargets = listOf(setOf("top", "bottom"), setOf("top"), setOf("bottom"))
            fun showArtworkImages(parent: () -> Unit, targets: MutableMap<String, Set<String>> = mutableMapOf(),
                focused: Int? = null) {
                val theme = OrbitThemes.current()
                val choices = mutableListOf<Choice>()
                // Theme artwork is stored per layout slot ("top" = the library screen), which is the
                // opposite of the physical screens when the library is on the bottom. "Apply to" is
                // physical, so convert in both directions here.
                fun slots(physical: Set<String>) = physical.map { OrbitThemeArtwork.artworkScreen(this, it, theme) }.toSet()
                for ((role, title) in listOf("background" to "Background image", "hero" to "Hero image",
                    "systemsBackground" to "Systems page background", "gamesBackground" to "Game page background")) {
                    val pageImage = role == "systemsBackground" || role == "gamesBackground"
                    // Page backgrounds sit behind the library grid, so default them to that screen.
                    val physicalScreens = targets[role] ?: if (pageImage) setOf(theme.libraryScreen) else setOf("top")
                    val screens = slots(physicalScreens)
                    val screenLabel = ThemeMediaSettings.screensLabel(physicalScreens)
                    val row = choices.size
                    val assignedSlots = theme.artwork.filter { it.role == role }.map { it.screen }.toSet()
                    val assigned = if (assignedSlots.isEmpty()) "None" else ThemeMediaSettings.screensLabel(slots(assignedSlots))
                    fun update(focus: Int): () -> Unit = {
                        if (isCurrent(parent)) showArtworkImages(parent, targets, focus)
                    }
                    choices += Choice("Apply to", "Press A to cycle screens. Saved image · $assigned.", screenLabel, section = title) {
                        targets[role] = screenTargets[(screenTargets.indexOf(physicalScreens) + 1) % screenTargets.size]
                        showArtworkImages(parent, targets, row)
                    }
                    choices += Choice("Search artwork", "Find and download an image sized for the selected screens.") {
                        val backToImages = { showArtworkImages(parent, targets, row + 1) }
                        ArtworkSearchPicker(this, if (pageImage) "background" else role, "", screens) { bytes ->
                            ThemeMediaSettings.applyDownloaded(this, theme, role, screens, null, bytes, update(row + 1))
                        }.show(
                            embed = { content, focus, keys, back -> embedPage(content, back, focus, keys, "$title · Search artwork") },
                            closePage = backToImages
                        )
                    }
                    choices += Choice("Choose from device", "Select a PNG, JPEG or WebP image for the selected screens.") {
                        pickThemeImage { uri ->
                            if (uri != null) ThemeMediaSettings.setImage(this, theme, role, screens, uri, update(row + 2))
                        }
                    }
                    if (theme.artwork.any { it.role == role && it.screen in screens }) {
                        choices += Choice("Remove image", "Clear this image from the selected screens.") {
                            ThemeMediaSettings.setImage(this, theme, role, screens, null, update(row + 2))
                        }
                    }
                }
                showChoices("Artwork images", parent, choices, focused = focused)
            }
            fun showAnimationSettings(parent: () -> Unit, focused: Int? = null) {
                fun flag(key: String) = TransitionSettings.flag(key)
                fun onOff(key: String) = if (flag(key)) "On" else "Off"
                fun save(key: String, value: Int, row: Int, after: () -> Unit = {}) {
                    runCatching { TransitionSettings.put(this, key, value) }
                        .onSuccess { after(); if (isCurrent(parent)) showAnimationSettings(parent, row) }
                        .onFailure(::showDataError)
                }
                // Grids redraw their selection outline when the gliding setting changes.
                fun refreshGrids() = listOf(0, 6).forEach { slot ->
                    (tabViews[slot]?.findViewWithTag<View>(if (slot == 0) "orbit-systems-grid" else "orbit-game-list") as? SplitLibraryGrid)?.refreshTiles()
                }
                val transition = pageTransition()
                // Rows are numbered as they are added, because Direction only appears for some styles.
                val choices = mutableListOf<Choice>()
                fun add(choice: (row: Int) -> Choice) { choices += choice(choices.size) }
                add { row ->
                    Choice("Transition style", "Press A to switch how the game list opens: Fade, Slide, Zoom, Scale, Flip, Dip (blank, then the next page) or Instant. Instant can flash on the Thor.",
                        pageTransitionLabels[transition]) { save(pageTransitionKey, nextPageTransition(transition), row) }
                }
                // Direction always shows; styles that do not move in a direction show "Not used".
                val directions = transitionDirectionLabels(transition)
                add { row ->
                    if (directions == null) Choice("Direction", "${pageTransitionLabels[transition]} has no direction. Slide, Zoom, Scale and Flip do.", "Not used") { }
                    else {
                        val current = transitionDirection(transition)
                        Choice("Direction", "Press A to change which way the ${pageTransitionLabels[transition]} transition moves.",
                            directions[current]) {
                            save(transitionDirectionKey(transition), (current + 1) % directions.size, row)
                        }
                    }
                }
                add { row ->
                    val speed = transitionSpeed()
                    Choice("Speed", "Press A to switch between Slow, Normal, Fast and Very fast. Fade and Dip stop short of the fastest fades, which flash on the Thor.",
                        transitionSpeedLabels[speed]) {
                        save(transitionSpeedKey, (speed + 1) % transitionSpeedLabels.size, row)
                    }
                }
                add { row ->
                    Choice("Apply to", "All themes share these settings, or each theme remembers its own. With Each theme, changes here only affect ${OrbitThemes.current().name}.",
                        if (TransitionSettings.perTheme()) "Each theme (${OrbitThemes.current().name})" else "All themes") {
                        runCatching {
                            UserDataStore.put(this, "settings", TransitionSettings.SCOPE_KEY, if (TransitionSettings.perTheme()) 0 else 1)
                        }.onSuccess { refreshGrids(); if (isCurrent(parent)) showAnimationSettings(parent, row) }.onFailure(::showDataError)
                    }
                }
                add { row ->
                    Choice("Tile cascade", "Game tiles ripple in one after another when a game list opens.",
                        onOff(SplitLibraryGrid.TILE_CASCADE_KEY)) {
                        save(SplitLibraryGrid.TILE_CASCADE_KEY, if (flag(SplitLibraryGrid.TILE_CASCADE_KEY)) 0 else 1, row)
                    }
                }
                add { row ->
                    Choice("Gliding selection", "The selection outline slides smoothly from tile to tile.",
                        onOff(SplitLibraryGrid.GLIDING_SELECTION_KEY)) {
                        save(SplitLibraryGrid.GLIDING_SELECTION_KEY, if (flag(SplitLibraryGrid.GLIDING_SELECTION_KEY)) 0 else 1, row, ::refreshGrids)
                    }
                }
                add { row ->
                    Choice("Top screen art crossfade", "The top screen's artwork blends into the next instead of cutting.",
                        onOff(GameMediaSurface.ART_CROSSFADE_KEY)) {
                        save(GameMediaSurface.ART_CROSSFADE_KEY, if (flag(GameMediaSurface.ART_CROSSFADE_KEY)) 0 else 1, row)
                    }
                }
                showChoices("Page transition", parent, choices, focused = focused)
            }
            fun showSystemArtwork(parent: () -> Unit, focused: Int? = null) {
                val theme = OrbitThemes.current()
                val choices = (screenTargets + listOf(emptySet())).mapIndexed { position, screens ->
                    val title = ThemeMediaSettings.screensLabel(screens) + if (screens.size == 1) " only" else ""
                    Choice(title, value = if (theme.systemArtworkScreens == screens) "Selected" else "") {
                        ThemeMediaSettings.setSystemArtwork(this, theme, screens) {
                            if (isCurrent(parent)) showSystemArtwork(parent, position)
                        }
                    }
                }
                showChoices("Show system artwork", parent, choices,
                    "Choose where the foreground console image is visible for this theme.", focused)
            }
            fun showScraperSections(parent: () -> Unit, focused: Int? = null) {
                val choices = scraperCategories.mapIndexed { position, section ->
                    Choice(section) {
                        showCategory(index, enter = true, subpage = Subpage(section, { showScraperSections(parent, position) }))
                    }
                }
                showChoices("Scraping", parent, choices, focused = focused)
            }
            val section = subpage?.title ?: names[index]
            fun redrawSection() {
                val focused = optionRows.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 && subpage != null }
                showCategory(index, enter = true, subpage = subpage, restoreOption = focused)
            }
            fun showUserData(parent: () -> Unit) {
                fun showProfiles() {
                    showChoices("System profiles", { showUserData(parent) }, listOf(
                        Choice("Import JSON", "Import a Cocoon or Daijishō system definition.", choose = ::pickSystemProfile),
                        Choice("Reload", "Read the system JSON files again.") {
                            val backToProfiles: () -> Unit = { showProfiles() }
                            showChoices("Profile compatibility", backToProfiles, emptyList(), "Loading profiles…")
                            worker.execute {
                                val result = runCatching {
                                    EmulatorProfileRepository.invalidate()
                                    EmulatorProfileRepository(this).errors
                                }
                                runOnUiThread {
                                    if (!isDestroyed && isCurrent(backToProfiles)) {
                                        val status = result.fold(
                                            { issues -> if (issues.isEmpty()) "Profiles loaded successfully." else issues.joinToString("\n\n") },
                                            { it.message ?: "Could not load profiles." }
                                        )
                                        showChoices("Profile compatibility", backToProfiles, emptyList(), status)
                                    }
                                }
                            }
                        }
                    ))
                }
                showChoices("User data folder", parent, listOf(
                    Choice("Change user data folder", "Move your Orbit settings, media and profiles to another folder.") {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        ), 101, popupLaunchOptions())
                    },
                    Choice("System profiles", "Manage emulator definitions in the systems folder.", choose = ::showProfiles)
                ), UserDataStore.accessError ?: UserDataStore.location(this))
            }
            val subpageChoices = subpage?.choices
            if (subpageChoices != null) {
                if (subpage.description.isNotBlank()) detail.addView(text(subpage.description, 14f, muted))
                subpageChoices.forEach { choice ->
                    choice.section?.let { sectionTitle ->
                        detail.addView(text(sectionTitle, 18f).apply {
                            typeface = Typeface.DEFAULT_BOLD
                            setPadding(0, dp(if (optionRows.isEmpty()) 4 else 16), 0, dp(2))
                        })
                    }
                    option(choice.title, choice.description, choice.value, choice.choose)
                }
            } else if (romFoldersOption != null) {
                val scanner = romLibraryScan ?: RomLibraryScan(this, repository, worker, ::publishScannedSystem)
                    .also { romLibraryScan = it }
                val status = text(scanner.status, 14f, muted)
                fun showRomFolders() {
                    if (activeTab == pageTab && root.isShown && status.parent === detail) {
                        showCategory(index, enter = true, romFoldersOption = romFoldersOption)
                    }
                }
                returnFromSubpage = { showCategory(index, enter = true, restoreOption = romFoldersOption) }
                option("Back", "Return to General.") { returnFromSubpage?.invoke() }
                first = null
                detail.addView(status)
                val add = option("Add ROM folder", "Choose a global folder. Orbit will look for system folders inside it.") {
                    if (!scanner.busy) pickRomFolder { uri ->
                        if (uri != null) runCatching { repository.addCollectionRoot(uri) }
                            .onSuccess { showRomFolders(); scanner.start() }
                            .onFailure { showDataError(it) }
                    }
                }
                val scan = option("Scan all games", "Scan your global ROM folders and update the library.") { scanner.start() }
                val cancel = option("Cancel scan", "Stop scanning and keep the results found so far.") { scanner.cancel() }
                val folders = repository.collectionRoots().map { uri ->
                    val path = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(uri.toString())
                    option("ROM folder · $path", "Remove this folder from automatic discovery.") {
                        val backToFolders = { showCategory(index, enter = true, romFoldersOption = romFoldersOption) }
                        showChoices("Remove ROM folder?", backToFolders, listOf(
                            Choice("Keep folder", choose = backToFolders),
                            Choice("Remove folder reference") {
                                runCatching { repository.removeCollectionRoot(uri) }
                                    .onSuccess { backToFolders() }.onFailure { showDataError(it) }
                            }
                        ), "$path\n\nExisting system assignments and ROM files are kept.")
                    }
                }
                val notes = text("", 13f, muted).apply { visibility = View.GONE }
                val scanDetails = option("Scan details", "Show the scanned systems and any folder errors.") {
                    notes.visibility = if (notes.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                detail.addView(notes)
                fun updateScanner() {
                    status.text = scanner.status
                    if (scanner.busy) {
                        cancel.visibility = View.VISIBLE
                        cancel.isEnabled = !scanner.cancelling
                        if (add.hasFocus() || scan.hasFocus()) cancel.requestFocus()
                        add.visibility = View.GONE
                        scan.visibility = View.GONE
                    } else {
                        add.visibility = View.VISIBLE
                        scan.visibility = View.VISIBLE
                        if (cancel.hasFocus()) scan.requestFocus()
                        cancel.visibility = View.GONE
                    }
                    folders.forEach { it.visibility = add.visibility }
                    scanDetails.visibility = if (scanner.details.isEmpty()) View.GONE else View.VISIBLE
                    if (scanner.details.isEmpty()) notes.visibility = View.GONE
                    else notes.text = scanner.details.joinToString("\n")
                }
                scanner.onUpdate = ::updateScanner
                updateScanner()
                first = if (scanner.busy) cancel else add
            } else if (screenMediaRow != null) {
                returnFromSubpage = returnToParent(screenMediaRow)
                for (screen in listOf("top", "bottom")) {
                    detail.addView(text(GameLaunchDisplay.label(screen), 18f).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(if (screen == "top") 4 else 16), 0, dp(2))
                    })
                    GameMediaSettingsPanel.options(this, screen, ::refreshGameMediaOptions).forEach { mediaOption ->
                        lateinit var row: LinearLayout
                        row = option(mediaOption.title, mediaOption.description, mediaOption.value()) {
                            mediaOption.choose {
                                (row.getChildAt(0) as TextView).text = "${mediaOption.title}  ·  ${mediaOption.value()}"
                            }
                        }
                    }
                }
            } else when (section) {
                "General" -> {
                    val startup = UserDataStore.int(this, "scanOnStartup", 1) != 0
                    option("Scan on startup", "Refresh saved ROM folders when Orbit starts.", if (startup) "On" else "Off") {
                        runCatching { UserDataStore.put(this, "settings", "scanOnStartup", if (startup) 0 else 1) }
                            .onSuccess { showCategory(0, true) }.onFailure { showDataError(it) }
                    }
                    val userDataRow = optionRows.size
                    option("User data folder", "Move your frontend data to another Android folder.") {
                        showUserData(returnToParent(userDataRow))
                    }
                    UserDataStore.accessError?.let { detail.addView(text(it, 12f, muted)) }
                    val romFoldersRow = optionRows.size
                    option("ROM folders & scanner", "Add global ROM folders and scan your collection.") {
                        showCategory(index, enter = true, romFoldersOption = romFoldersRow)
                    }
                    detail.addView(text("Emulators", 18f).apply { setPadding(0, dp(8), 0, dp(2)); typeface = Typeface.DEFAULT_BOLD })
                    val emulatorsRow = optionRows.size
                    option("Choose default emulators", "Set the emulator for each scanned system.") {
                        val host = object : EmulatorSettings.PageHost {
                            override val activity = this@MainActivity
                            override val isTabbed = true
                            override fun selectCompanionOption(label: String) = Unit
                            override fun showPage(title: String, content: View, backLabel: String,
                                back: () -> Unit, handle: (Int, KeyEvent) -> Boolean) {
                                embedPage(content, back, keys = { handle(it.keyCode, it) }, title = title)
                            }
                        }
                        EmulatorSettings.showIn(host, populated, back = returnToParent(emulatorsRow))
                    }
                    option("Import system JSON", "Import a Cocoon or Daijishō system definition.") {
                        pickSystemProfile()
                    }
                    option("Exit Orbit", "Close Orbit on both screens.") { exitOrbit() }
                }
                "Display" -> {
                    val refresh = FrontendRefreshRate.selected(this)
                    option("Refresh rate", "Press A to switch between 60 and 120 Hz.", "$refresh Hz") {
                        val rate = if (refresh == 60) 120 else 60
                        if (!FrontendRefreshRate.supported(this, rate)) {
                            Toast.makeText(this, "$rate Hz is not supported on this display", Toast.LENGTH_SHORT).show()
                        } else runCatching {
                            FrontendRefreshRate.save(this, rate)
                            FrontendRefreshRate.apply(this)
                        }.onSuccess { showCategory(index, true) }.onFailure { showDataError(it) }
                    }
                    val available=populated
                    val spec=available.firstOrNull { it.id==displaySystemId }
                        ?: available.firstOrNull { it==system } ?: available.firstOrNull()
                    if(spec==null)detail.addView(text("Scan your ROM folders to set launch screens for systems and games.",14f,muted))
                    else {
                        displaySystemId=spec.id
                        val systemRow = optionRows.size
                        option("System","Choose a system to configure.",spec.title) {
                            val parent = returnToParent(systemRow)
                            chooseFromList("Choose system", available.map { it.title }, available.indexOf(spec), parent) { choice ->
                                displaySystemId = available[choice].id
                                displayGameUri = null
                                parent()
                            }
                        }
                        val screen=GameLaunchDisplay.system(this,spec) ?: "top"
                        option("System launch display","Press A to switch the launch screen for this system.",GameLaunchDisplay.label(screen)) {
                            runCatching { GameLaunchDisplay.save(this,spec,null,if(screen=="top")"bottom" else "top") }
                                .onSuccess { showCategory(index,true) }.onFailure { showDataError(it) }
                        }
                        val collection=pages[spec.id]?.games.orEmpty()
                        val game=collection.firstOrNull { it.uri.toString()==displayGameUri }
                            ?: games.getOrNull(gameCursor)?.takeIf { spec==system && it in collection }
                            ?: collection.firstOrNull()
                        if(game!=null) {
                            displayGameUri=game.uri.toString()
                            val gameRow = optionRows.size
                            option("Game","Choose a game for an individual launch-screen override.",game.title) {
                                val parent = returnToParent(gameRow)
                                chooseFromList("Choose game", collection.map { it.title }, collection.indexOf(game), parent) { choice ->
                                    displayGameUri = collection[choice].uri.toString()
                                    parent()
                                }
                            }
                            val gameScreen=GameLaunchDisplay.game(this,spec,game)
                            option("Game launch display","Press A to cycle system default, top screen and bottom screen.",GameLaunchDisplay.label(gameScreen)) {
                                val next=when(gameScreen) { null -> "top";"top" -> "bottom";else -> null }
                                runCatching { GameLaunchDisplay.save(this,spec,game,next) }
                                    .onSuccess { showCategory(index,true) }.onFailure { showDataError(it) }
                            }
                        }
                    }
                }
                "Customisation" -> {
                    val theme = OrbitThemes.current()
                    val themesRow = optionRows.size
                    option("Themes", "Open the theme gallery to preview, apply or create a theme.", theme.name) {
                        val parent = returnToParent(themesRow)
                        val gallery = ThemeGalleryPanel(this, columns = 2, exit = parent) { themeApplied(keepSettings = true) }
                        embedPage(gallery.view, { if (!gallery.back()) parent() }, gallery.initialFocus,
                            gallery::handleNavigation, showBack = false)
                    }
                    screenMediaLink()
                    val imagesRow = optionRows.size
                    option("Artwork images", "Set background and hero images for this theme.") {
                        showArtworkImages(returnToParent(imagesRow))
                    }
                    val animationRow = optionRows.size
                    option("Page transition", "Transition style, tile cascade, gliding selection and top screen art crossfade.",
                        pageTransitionLabels[pageTransition()]) {
                        showAnimationSettings(returnToParent(animationRow))
                    }
                    val visibilityRow = optionRows.size
                    option("Show system artwork","Show or remove the foreground console image for this theme.",ThemeMediaSettings.screensLabel(theme.systemArtworkScreens)) {
                        showSystemArtwork(returnToParent(visibilityRow))
                    }
                }
                "Dual Screen" -> {
                    val shortcutScreen=UserDataStore.cachedString("settings","settingsShortcutScreen")
                    val shortcutLabel=shortcutScreen?.let(GameLaunchDisplay::label) ?: "Current screen"
                    option("Settings shortcuts","Choose where Start and Select open settings. Top or Bottom always wins; Current screen follows the tile's saved launch screen.",shortcutLabel) {
                        val next=when(shortcutScreen) { null -> "top";"top" -> "bottom";else -> null }
                        runCatching { UserDataStore.put(this,"settings","settingsShortcutScreen",next) }
                            .onSuccess { showCategory(index,true) }.onFailure(::showDataError)
                    }
                    val dockEnabled=AppDock.enabled(this)
                    option("App dock", "Show the app dock on the bottom screen.", if(dockEnabled) "On" else "Off") {
                        UserDataStore.put(this,"settings","appDockEnabled",if(dockEnabled)0 else 1)
                        refreshLibraryDock()
                        redrawSection()
                    }
                    val dockSlots=AppDock.slots(this)
                    option("App dock slots", "Press A to choose 1–5 slots.", dockSlots.toString()) {
                        UserDataStore.put(this,"settings","appDockSlots",dockSlots%5+1)
                        refreshLibraryDock()
                        showCategory(index,true)
                    }
                    detail.addView(text("Thor’s Focus Mode controls which screen receives the physical buttons. Use Auto-lock to follow the active screen.", 13f, muted))
                }
                "Account" -> {
                    val providerPreferences=getSharedPreferences("scraper_credentials",0)
                    val providers=listOf("ScreenScraper","SteamGridDB","TheGamesDB","NeoAssets")
                    val provider=providerPreferences.getString("scrape.provider","ScreenScraper") ?: "ScreenScraper"
                    option("Scraper provider","Choose the service used by Start scraping.",provider) {
                        providerPreferences.edit().putString("scrape.provider",providers[(providers.indexOf(provider)+1)%providers.size]).apply()
                        redrawSection()
                    }
                    detail.addView(text("Logins & API keys", 24f).apply {
                        typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(24), 0, dp(5))
                    })
                    listOf("ScreenScraper", "SteamGridDB", "TheGamesDB").forEach { provider ->
                        detail.addView(text(provider, 21f).apply {
                            typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(20), 0, dp(5))
                        })
                        val credentials = getSharedPreferences("scraper_credentials", 0)
                        if (provider != "NeoAssets") {
                            if (provider == "ScreenScraper" && ScraperCredentials.hasDeveloperAccount)
                                detail.addView(text("Developer connection configured. Add your personal ScreenScraper account below if needed.", 13f, muted))
                            val fields = if (provider == "ScreenScraper" && ScraperCredentials.hasDeveloperAccount) listOf(
                                "username" to "Username", "password" to "Password"
                            ) else if (provider == "ScreenScraper") listOf(
                                "developerId" to "Developer ID", "developerPassword" to "Developer password",
                                "username" to "Username", "password" to "Password"
                            ) else listOf("apiKey" to "API key")
                            val editors = fields.map { (key, title) ->
                                detail.addView(text(title, 13f, muted))
                                val editor = EditText(this).apply {
                                    isSingleLine = true; textSize = 16f; setTextColor(Color.WHITE)
                                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                        if (key.contains("password", true) || key == "apiKey") android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                        else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                                    setText(credentials.getString("$provider.$key", ""))
                                    hint = title; setHintTextColor(muted)
                                }
                                detail.addView(editor, LinearLayout.LayoutParams(-1, dp(52)))
                                optionRows.add(editor)
                                key to editor
                            }
                            option("Save $provider credentials", "Save the account details entered above.") {
                                credentials.edit().apply {
                                    editors.forEach { (key, editor) -> putString("$provider.$key", editor.text.toString().trim()) }
                                }.apply()
                                Toast.makeText(this, "$provider credentials saved", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                "Scraping" -> {
                    val panel=scraperPanel()
                    panel.attach(detail) {
                        if (subpage == null) openScrapingPage(3)
                        else showCategory(index, enter = true, subpage = Subpage("Systems", subpage.back))
                    }
                    optionRows.addAll(panel.navigationRows)
                    first=panel.navigationRows.firstOrNull { it.isEnabled && it.visibility==View.VISIBLE }
                }
                "Media" -> {
                    val scrapingRow = optionRows.size
                    option("Scraping","Open scraper accounts, scan options and download progress.") {
                        showScraperSections(returnToParent(scrapingRow))
                    }
                    screenMediaLink()
                    val packsRow = optionRows.size
                    option("NeoAssets artwork packs","Browse downloadable system artwork packs.") {
                        val parent = returnToParent(packsRow)
                        val scraper = scraperPanel()
                        showChoices("NeoAssets artwork packs", parent, emptyList(), "Loading packs…")
                        scraper.loadPacks { result ->
                            if (isCurrent(parent)) {
                                result.onSuccess { packs ->
                                    val gallery = NeoAssetsPackGallery(this, packs, parent) { folder ->
                                        showCategory(index, enter = true, subpage = Subpage("Scraping", parent))
                                        scraper.downloadPack(folder)
                                    }
                                    embedPage(gallery.view, gallery::back, gallery.initialFocus, gallery::handle, "NeoAssets artwork packs")
                                }.onFailure { showChoices("NeoAssets artwork packs", parent, emptyList(), it.message.orEmpty()) }
                            }
                        }
                    }
                    val transferRow = optionRows.size
                    option("Media transfer", "Copy or move media from NeoStation, your personal NeoStation build or ES-DE into Orbit.") {
                        mediaTransferBack = returnToParent(transferRow)
                        val panel = mediaTransferPanel ?: MediaTransferPanel(
                            this, { populated }, { mediaTransferBack?.invoke() }, ::refreshTransferredMedia
                        ) { picker -> startActivityForResult(picker, 151, popupLaunchOptions()) }.also { mediaTransferPanel = it }
                        embedPage(panel.view, panel::back, panel.initialFocus)
                    }
                    detail.addView(text("Media types", 21f).apply { typeface = Typeface.DEFAULT_BOLD })
                    val mediaPreferences = getSharedPreferences("scraper_credentials", 0)
                    val enabledMedia = mediaPreferences.getStringSet("media.categories", ScraperMediaTypes.categories.map { it.folder }.toSet()).orEmpty()
                    ScraperMediaTypes.categories.forEach { category ->
                        val enabled = category.folder in enabledMedia
                        option(category.title, "Include when scraping, where available.", if (enabled) "On" else "Off") {
                            val next = enabledMedia.toMutableSet()
                            if (enabled) next.remove(category.folder) else next.add(category.folder)
                            mediaPreferences.edit().putStringSet("media.categories", next).apply()
                            showCategory(index, true)
                        }
                    }
                }
                "Scrape Mode" -> {
                    val preferences=getSharedPreferences("scraper_credentials",0)
                    val mode=ScrapePolicy.mode(this)
                    val requested=ScrapePolicy.requestedConcurrency(this)
                    val concurrencyRow = optionRows.size
                    option("Games at once","Default: account maximum. Manual amounts are capped by the account allowance.",if(requested==0)"Account maximum" else requested.toString()) {
                        val parent = { showCategory(index, enter = true, restoreOption = concurrencyRow, subpage = subpage) }
                        val choices = (0..6).map { count ->
                            Choice(if (count == 0) "Account maximum" else count.toString(),
                                value = if (requested == count) "Selected" else "") {
                                preferences.edit().putInt("scrape.gamesAtOnce", count).apply()
                                parent()
                            }
                        }
                        showChoices("Games at once", parent, choices, focused = requested.coerceIn(0, 6))
                    }
                    listOf(Triple("all","All selected games","Scrape every selected game and replace selected media."),
                        Triple("missing","Missing media only","Fill missing media types while keeping existing artwork."),
                        Triple("new","Games without media","Skip games that already have downloaded artwork.")).forEach { (id,title,description) ->
                        option(title,description,if(mode==id) "Selected" else "") {
                            preferences.edit().putString("scrape.mode",id).apply();redrawSection()
                        }
                    }
                }
                "Region", "Languages" -> {
                    val region=section=="Region"
                    val preferences=getSharedPreferences("scraper_credentials",0)
                    val selected=if(region)ScrapePolicy.region(this) else ScrapePolicy.language(this)
                    val asset=if(region) "screenscraper-regions.json" else "screenscraper-langues.json"
                    val choices=ScraperClient.objects(org.json.JSONArray(assets.open(asset).bufferedReader().use { it.readText() }))
                        .let { entries -> if(region)entries else entries.sortedWith(compareBy<org.json.JSONObject> { if(it.optString("code")==selected)0 else 1 }.thenBy { it.optString("name") }) }
                    detail.addView(text(if(region) "Preferred artwork region. USA is the default; other regions are used when unavailable." else "Preferred artwork and description language. English is the default.",13f,muted))
                    choices.forEach { choice ->
                        val code=choice.getString("code")
                        val title=if(region && code=="us")"USA" else choice.getString("name")
                        option(title,code.uppercase(java.util.Locale.ROOT),if(code==selected)"Selected" else "") {
                            preferences.edit().putString(if(region)"scrape.region" else "scrape.language",code).apply()
                            GameArtworkPanel.clearMediaCache();EsDeScene.clearMediaCache()
                            redrawSection()
                        }
                    }
                }
                "Systems" -> {
                    val preferences=getSharedPreferences("scraper_credentials",0)
                    val choices=populated.sortedBy { it.title }
                    val selected=preferences.getStringSet("scrape.systems",emptySet()).orEmpty().intersect(choices.map { it.id }.toSet()).toMutableSet()
                    val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                    heading.addView(text("None selected = all systems",13f,muted),LinearLayout.LayoutParams(0,-2,1f))
                    val toggle=button(if(selected.size==choices.size && choices.isNotEmpty())"Deselect all" else "Select all") { }.apply { isFocusableInTouchMode=true }
                    heading.addView(toggle,LinearLayout.LayoutParams(dp(150),dp(46)))
                    detail.addView(heading);optionRows.add(toggle)
                    if(choices.isEmpty())detail.addView(text("Scan your ROM folders first.",16f))
                    else {
                        val tileArt=mutableMapOf<String,android.graphics.Bitmap?>()
                        val loadingArt=mutableSetOf<String>()
                        val grid=GridView(this).apply {
                            numColumns=3;horizontalSpacing=dp(12);verticalSpacing=dp(12)
                            stretchMode=GridView.STRETCH_COLUMN_WIDTH;isFocusable=true;isFocusableInTouchMode=true
                            defaultFocusHighlightEnabled=false;setDrawSelectorOnTop(true)
                            selector=GradientDrawable().apply { setColor(Color.TRANSPARENT);cornerRadius=dp(12).toFloat();setStroke(dp(2),blue) }
                            adapter=object:BaseAdapter() {
                                override fun getCount()=choices.size
                                override fun getItem(position:Int)=choices[position]
                                override fun getItemId(position:Int)=position.toLong()
                                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                                    val spec=choices[position]
                                    return FrameLayout(this@MainActivity).apply {
                                        isFocusable=false
                                        layoutParams=AbsListView.LayoutParams(-1,dp(124));setPadding(dp(2),dp(2),dp(2),dp(2))
                                        background=surface(Color.TRANSPARENT,Color.rgb(38,49,65))
                                        addView(ImageView(this@MainActivity).apply {
                                            scaleType=ImageView.ScaleType.FIT_XY;contentDescription="${spec.title} artwork"
                                            tag="system-art:${spec.id}"
                                            setImageBitmap(tileArt[spec.id])
                                        },FrameLayout.LayoutParams(-1,-1))
                                        addView(text(if(spec.id in selected) "✓" else "",18f,Color.rgb(65,200,115)).apply {
                                            gravity=Gravity.CENTER;tag="system-tick:${spec.id}"
                                            setShadowLayer(dp(2).toFloat(),0f,0f,Color.BLACK)
                                        },FrameLayout.LayoutParams(dp(26),dp(26),Gravity.TOP or Gravity.RIGHT))
                                        if(!tileArt.containsKey(spec.id) && loadingArt.add(spec.id))worker.execute {
                                            val bitmap=runCatching {
                                                OrbitConsoleImages.load(this@MainActivity,spec)
                                                OrbitSystemArtwork.bitmap(spec,OrbitThemes.current())
                                            }.getOrNull()
                                            runOnUiThread { if(!isDestroyed) {
                                                tileArt[spec.id]=bitmap
                                                parent.findViewWithTag<ImageView>("system-art:${spec.id}")?.setImageBitmap(bitmap)
                                            } }
                                        }
                                    }
                                }
                            }
                        }
                        fun saveSelection() {
                            preferences.edit().putStringSet("scrape.systems",selected.toSet()).apply()
                            toggle.text=if(selected.size==choices.size)"Deselect all" else "Select all"
                            choices.forEach { spec ->
                                grid.findViewWithTag<TextView>("system-tick:${spec.id}")?.text=if(spec.id in selected)"✓" else ""
                            }
                        }
                        toggle.setOnClickListener {
                            if(selected.size==choices.size)selected.clear() else selected.addAll(choices.map { it.id })
                            saveSelection()
                        }
                        grid.setOnItemClickListener { _,_,position,_ ->
                            val id=choices[position].id
                            if(!selected.add(id))selected.remove(id)
                            saveSelection()
                        }
                        detail.addView(grid,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels-dp(210)).coerceAtLeast(dp(200))))
                        optionRows.add(grid);first=grid
                    }
                }

            }
            detail.isFocusableInTouchMode = false
            detail.isFocusable = false
            if (enter) {
                val preferred = restoreOption ?: focusedOption.takeIf { sameCategory }
                val target = preferred?.let(optionRows::getOrNull)?.takeIf { it.isEnabled && it.visibility==View.VISIBLE } ?: first
                val destination=target ?: optionRows.firstOrNull { it.isEnabled && it.visibility==View.VISIBLE }
                destination?.requestFocus()
                destination?.post {
                    if(pageCategory==index && activeTab==pageTab && destination.isAttachedToWindow) {
                        destination.requestFocus()
                        if (restoreOption != null) destination.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, destination.width, destination.height), true
                        )
                    }
                }
                detailScroll.scrollTo(0, previousScroll)
            }
            updatingCategory=false
        }
        names.forEachIndexed { index, name ->
            val row = button(name) { showCategory(index, true) }
            row.isFocusableInTouchMode = true
            row.defaultFocusHighlightEnabled = false
            row.stateListAnimator = null
            row.backgroundTintList = null
            row.textSize = 19f
            row.setOnFocusChangeListener { _, focus -> if (focus && !updatingCategory && pageCategory != index) showCategory(index) }
            val size = if (topTabs) LinearLayout.LayoutParams(-2, dp(48), 1f).apply {
                if (index < names.lastIndex) marginEnd = dp(8)
            } else LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(8) }
            categories.addView(row, size)
            categoryButtons.add(row)
        }
        showCategory(pageCategory.coerceIn(names.indices))
        fun reveal(scroll: ScrollView, row: View) {
            row.requestFocus()
            scroll.fling(0)
            if (row.top < scroll.scrollY) scroll.scrollTo(0, row.top)
            else if (row.bottom > scroll.scrollY + scroll.height) scroll.scrollTo(0, row.bottom - scroll.height)
        }
        fun focusCategory(index: Int) {
            val row = categoryButtons[index]
            if (sidebar != null) reveal(sidebar, row)
            else {
                row.requestFocus()
                row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), true)
            }
        }
        fun returnToCategory() {
            updatingCategory=true
            try { focusCategory(pageCategory) }
            finally { updatingCategory=false }
        }
        fun focusDetail() {
            val embedded = embeddedView
            if (embedded != null) {
                if (embeddedFocus?.requestFocus() != true) embedded.requestFocus()
            } else optionRows.firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }?.let { reveal(detailScroll, it) }
        }
        fun focusedView()=popup?.dialog?.currentFocus ?: currentFocus
        val handleSettings: (KeyEvent)->Boolean = { event ->
            val key = event.keyCode
            val focusedGrid=optionRows.filterIsInstance<GridView>().firstOrNull { it.hasFocus() }
            val tabStep = if (!topTabs) 0 else NavigationTabs.direction(key).takeIf { it != 0 } ?: when {
                focusedGrid != null || embeddedView?.hasFocus() == true || focusedView() is EditText -> 0
                key == KeyEvent.KEYCODE_DPAD_LEFT -> -1
                key == KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            if (tabStep != 0) {
                if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
                    val count = categoryButtons.size
                    focusCategory((pageCategory + tabStep + count) % count)
                }
                true
            } else if (topTabs && categories.hasFocus() && key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) focusDetail()
                true
            } else if(key in listOf(KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BACK) && (returnFromSubpage != null || detailHost.hasFocus())) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                    returnFromSubpage?.invoke() ?: returnToCategory()
                }
                true
            } else if(embeddedView?.hasFocus() == true) {
                val confirm = key in listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
                val strayRelease = confirm && event.action == KeyEvent.ACTION_UP && embeddedConfirmDown != event.downTime
                if (confirm) embeddedConfirmDown = if (event.action == KeyEvent.ACTION_DOWN) event.downTime else null
                if (strayRelease || embeddedKeys?.invoke(event) == true) true
                else if (key == KeyEvent.KEYCODE_BUTTON_A && focusedView() !is EditText) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) focusedView()?.performClick()
                    true
                } else false
            } else if(embeddedView != null && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) focusDetail()
                true
            } else if(focusedView() !is EditText && key in listOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && !event.isCanceled) {
                    if(focusedGrid!=null) {
                        val position=if(scraperSettingsPanel?.ownsGrid(focusedGrid)==true)scraperSettingsPanel!!.tilePosition() else focusedGrid.selectedItemPosition.coerceAtLeast(0)
                        if(position<focusedGrid.count)focusedGrid.performItemClick(focusedGrid.selectedView,position,focusedGrid.getItemIdAtPosition(position))
                    } else {
                        val focus=focusedView()
                        val category=categoryButtons.indexOfFirst { it===focus }
                        if(category>=0) {
                            if (topTabs) focusDetail() else showCategory(category,true)
                        }
                        else focus?.performClick()
                    }
                }
                true
            } else if(focusedGrid!=null && key in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    val position=if(scraperSettingsPanel?.ownsGrid(focusedGrid)==true)scraperSettingsPanel!!.tilePosition() else focusedGrid.selectedItemPosition.coerceAtLeast(0)
                    if(key==KeyEvent.KEYCODE_DPAD_UP && position<3) {
                        val previous=optionRows.takeWhile { it!==focusedGrid }.lastOrNull { it.isEnabled && it.visibility==View.VISIBLE }
                        if(previous!=null)reveal(detailScroll,previous) else returnToCategory()
                    } else if(key==KeyEvent.KEYCODE_DPAD_LEFT && position%3==0) {
                        if (!topTabs) returnToCategory()
                    }
                    else {
                        val next=when(key) {
                            KeyEvent.KEYCODE_DPAD_UP -> if(position>=3)position-3 else position
                            KeyEvent.KEYCODE_DPAD_DOWN -> (position+3).coerceAtMost(focusedGrid.count-1)
                            KeyEvent.KEYCODE_DPAD_LEFT -> position-1
                            else -> if(position%3<2 && position+1<focusedGrid.count)position+1 else position
                        }
                        if(scraperSettingsPanel?.ownsGrid(focusedGrid)==true)scraperSettingsPanel!!.selectTile(next)
                        else focusedGrid.onKeyDown(key,event)
                    }
                }
                true
            } else if (focusedView() is EditText && key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
            else if (key !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) false
            else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val navigableRows=optionRows.filter { it.isEnabled && it.visibility==View.VISIBLE }
                    val inOptions = detailScroll.hasFocus()
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> returnToCategory()
                        KeyEvent.KEYCODE_DPAD_RIGHT -> navigableRows.firstOrNull()?.let { reveal(detailScroll, it) }
                        else -> {
                            val step = if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                            if (inOptions && navigableRows.isNotEmpty()) {
                                val current = navigableRows.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
                                if (topTabs && step < 0 && current == 0) returnToCategory()
                                else reveal(detailScroll, navigableRows[(current + step).coerceIn(navigableRows.indices)])
                            } else if (!topTabs) {
                                val next = (pageCategory + step).coerceIn(categoryButtons.indices)
                                focusCategory(next)
                            }
                        }
                    }
                }
                true
            }
        }
        val initial=categoryButtons[pageCategory]
        if(popup!=null) {
            popup.clearTabs("All settings")
            popup.page("All settings",root,back=back,handle={ _,event -> handleSettings(event) })
            initial.post { if(popup.dialog.isShowing)initial.requestFocus() }
        } else {
            settingsKeys=handleSettings;settingsKeyHandlers[pageTab]=handleSettings
            present(root);tabFocus[pageTab]=initial
            if(!warmingTabs)initial.post { if(activeTab==pageTab && startMenu?.isShowing!=true)initial.requestFocus() }
        }
    }
    private val swapKeyHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var swapKeyDown:KeyEvent?=null
    private var swapKeyTriggered=false
    private val swapKeyHold=Runnable {
        if(swapKeyDown!=null && !swapKeyTriggered && ready && !showingCompanion && startMenu?.isShowing!=true) {
            swapKeyTriggered=true
            DualScreenCoordinator.swapScreens(this)
        }
    }
    private fun cancelSwapKey() {
        swapKeyHandler.removeCallbacks(swapKeyHold);swapKeyDown=null;swapKeyTriggered=false
    }
    private fun activeSplitTileMenu():SplitTileMenu? {
        if(!ready || activeTab!=0 || startMenu?.isShowing==true)return null
        return tabViews[if(library)6 else 0]?.findViewWithTag<SplitTileMenu>(SplitTileMenu.TAG)?.takeIf { it.isShown }
    }
    private fun activeSplitGrid():SplitLibraryGrid? {
        if(activeTab!=0)return null
        val tag=if(library)"orbit-game-list" else "orbit-systems-grid"
        return tabViews[if(library)6 else 0]?.findViewWithTag<View>(tag) as? SplitLibraryGrid
    }
    private fun refreshGameTiles() {
        (gameList?.adapter as? BaseAdapter)?.notifyDataSetChanged()
        if(library)activeSplitGrid()?.refreshTiles()
    }
    internal fun handleStartShortcut(event:KeyEvent):Boolean {
        if(!ready || showingCompanion || event.keyCode!=KeyEvent.KEYCODE_BUTTON_START)return false
        if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && !event.isCanceled) {
            pendingSettingsStart=null
            val tileMenu=activeSplitTileMenu()
            if(tileMenu?.isOpen==true)tileMenu.launch()
            else if(settingsShortcutHost()===this)openSettings(this)
            else pendingSettingsStart=event.downTime
        } else if(event.action==KeyEvent.ACTION_UP && pendingSettingsStart==event.downTime) {
            pendingSettingsStart=null
            if(!event.isCanceled)openSettings()
        }
        return true
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(showingCompanion)return super.dispatchKeyEvent(event)
        remotePopups.removeAll { !it.isShowing }
        remotePopups.lastOrNull()?.let { popup ->
            val key=event.keyCode
            if(KeyEvent.isGamepadButton(key) || key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
                key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_BACK) {
                // B normally reaches a dialog as Back; passing the raw button on needs the same meaning.
                val handled=popup.dispatchKeyEvent(event)
                if(!handled && key==KeyEvent.KEYCODE_BUTTON_B && event.action==KeyEvent.ACTION_UP && !event.isCanceled)popup.dismiss()
                return true
            }
        }
        remoteSettingsDialog?.takeIf { it.isShowing }?.let { dialog ->
            val key=event.keyCode
            if(KeyEvent.isGamepadButton(key) || key in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
                key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_BACK) {
                dialog.dispatchKeyEvent(event)
                return true
            }
        }
        if(artworkPanel?.manualVideoActive==true || DualScreenCoordinator.companionVideoActive) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_X -> Unit
                KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_MENU,
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER -> return true
                else -> if(KeyEvent.isGamepadButton(event.keyCode))return true
            }
        }
        if(activeSplitGrid()?.takeIf { it.isMoving && it.isShown }?.handleController(event)==true)return true
        if(handleStartShortcut(event))return true
        activeSplitTileMenu()?.let { menu ->
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_Y) {
                if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)menu.onY()
                return true
            }
            if(menu.isOpen && menu.handle(event))return true
        }
        if(ready && event.keyCode==KeyEvent.KEYCODE_BUTTON_X && startMenu?.isShowing!=true) {
            if(event.action==KeyEvent.ACTION_DOWN) {
                if(event.repeatCount==0) {
                    cancelSwapKey();swapKeyDown=KeyEvent(event)
                    swapKeyHandler.postDelayed(swapKeyHold,android.view.ViewConfiguration.getLongPressTimeout().toLong())
                } else if(event.isLongPress)swapKeyHold.run()
            } else if(event.action==KeyEvent.ACTION_UP) {
                val down=swapKeyDown;val held=swapKeyTriggered
                cancelSwapKey()
                if(!held && !event.isCanceled && down!=null) {
                    dispatchFrontendKeyEvent(down)
                    dispatchFrontendKeyEvent(event)
                }
            }
            return true
        }
        return dispatchFrontendKeyEvent(event)
    }
    private fun dispatchFrontendKeyEvent(event:KeyEvent):Boolean {
        if(ready && activeTab==0 && library && event.keyCode==KeyEvent.KEYCODE_BUTTON_X &&
            startMenu?.isShowing!=true && activeSplitTileMenu()?.isOpen!=true) {
            if(activeSplitTileMenu()?.shortcutSelected==true)return true
            if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)games.getOrNull(gameCursor)?.let { game ->
                val local=artworkPanel
                if(local!=null)local.toggleVideoPreview(system,game)
                else if(!DualScreenCoordinator.toggleCompanionVideo(system,game)) {
                    Toast.makeText(this,"The artwork screen is unavailable.",Toast.LENGTH_SHORT).show()
                }
            }
            return true
        }
        if(ready && activeTab==0 && !library && startMenu?.isShowing!=true) {
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_R2) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && !event.isCanceled)navigateTab(1)
                return true
            }
            val step=NavigationTabs.direction(event.keyCode)
            if(step!=0) {
                // Reuse each theme's normal system navigation, including key repeats.
                val key=if(step<0)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                return dispatchFrontendKeyEvent(KeyEvent(event.downTime,event.eventTime,event.action,key,
                    event.repeatCount,event.metaState,event.deviceId,event.scanCode,event.flags,event.source))
            }
        }
        if(ready && startMenu?.isShowing!=true &&
            activeSplitGrid()?.takeIf { it.isShown }?.handleController(event)==true)return true
        if (ready && activeTab == 0 && library && event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event)
            return true
        }
        if (ready && activeTab in listOf(2,4) && settingsKeyHandlers[activeTab]?.invoke(event) == true) return true
        if (ready && activeTab==3 && themeGallery?.handleNavigation(event)==true)return true
        if(ready && activeTab==0 && !library && startMenu?.isShowing!=true && nativeSystems?.handle(event)==true)return true
        if(ready && activeTab==0 && !library && startMenu?.isShowing!=true && importedSystems?.handle(event)==true)return true
        if (ready && activeTab == 0 && !library && EsDeTheme.enabled(this) && startMenu?.isShowing != true && colorfulSystems?.handleController(event) == true) return true
        if (ready && activeTab == 1 && event.action == KeyEvent.ACTION_DOWN && appsPanel?.onKeyDown(event.keyCode, event) == true) return true
        return super.dispatchKeyEvent(event)
    }
    private fun showSystemMenu(spec: SystemSpec, host:Activity=this) {
        if (startMenu?.isShowing == true) return
        val menu = EmulatorSettings.MenuWindow(host,fullScreen=OrbitThemeArtwork.screen(host)=="top")
        startMenu = menu.dialog
        if(host!==this) { remoteSettingsDialog=menu.dialog;remoteSettingsHost=host }
        val currentGame=if(library && system==spec)games.getOrNull(gameCursor) else null
        val scraper=currentGame?.let { game -> GameScraperPopup(menu,spec,game) {
            artworkPanel?.show(spec,game,true);importedScene?.showGame(game,true);gameLogos.clear()
            refreshGameTiles()
            DualScreenCoordinator.refreshMedia(true)
        } }
        menu.dialog.setOnDismissListener {
            scraper?.destroy()
            settingsWindowClosed(menu.dialog)
        }
        val sections=if(currentGame==null)listOf("Emulator","General","Customisation")
            else listOf("General","Emulator","Customisation","Media")
        var section=0
        lateinit var showScraping:()->Unit
        fun home() {
            menu.selectTab(section)
            when(sections[section]) {
                "Emulator" -> if(currentGame!=null) EmulatorSettings.showForGame(menu,spec,currentGame) { menu.dialog.dismiss() }
                    else EmulatorSettings.showForSystemIn(menu,spec,{}, { menu.dialog.dismiss() })
                "General" -> {
                    val actions=mutableListOf<Pair<String,()->Unit>>("Scraping" to { showScraping() })
                    if(currentGame==null)actions.addAll(listOf(
                        "Add ROM folder" to {
                            pickingSystem = spec
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 100, popupLaunchOptions())
                        },
                        "Manage ROM folders" to {
                            val folders = repository.folders(spec)
                            if (folders.isEmpty()) menu.page("ROM folders", text("No folders assigned to ${spec.title}.", 16f), back = { home() })
                            else menu.actions("Remove ROM folder", folders.map { uri ->
                                uri.lastPathSegment.orEmpty() to {
                                    runCatching { repository.removeFolder(spec, uri) }.onSuccess { home() }.onFailure { showDataError(it) }
                                }
                            }, { home() })
                        },
                        "Rescan ${spec.title}" to {
                            menu.page("Scanning ${spec.title}", text("Scanning ROM folders…", 16f), back = { home() })
                            worker.execute {
                                val result = runCatching { repository.scanReport(spec) }
                                val available = runCatching { repository.populatedSystems() }.getOrDefault(populated)
                                runOnUiThread {
                                    if (!isDestroyed) {
                                        result.onSuccess { report ->
                                            pages = pages + (spec.id to page(report)); populated = available
                                            if (system == spec) { games = pages[spec.id]!!.games; error = null }
                                            render()
                                            if (menu.dialog.isShowing) home()
                                        }.onFailure { failure ->
                                            if (menu.dialog.isShowing) menu.page("Scan failed", text(failure.message ?: "Folder unavailable", 16f), back = { home() })
                                        }
                                    }
                                }
                            }
                        }
                    ))
                    menu.actions("General",actions)
                }
                "Customisation" -> if(currentGame==null)SystemArtworkSettingsPanel(this,menu,spec).show()
                    else GameCustomArtworkPanel(this,menu,spec,currentGame).show()
                "Media" -> GameMediaSettingsPanel(this,menu).show()
            }
        }
        showScraping={
            section=sections.indexOf("General");menu.selectTab(section)
            val preferences=getSharedPreferences("scraper_credentials",0)
            val categories=ScraperMediaTypes.categories
            fun enabled()=preferences.getStringSet("media.categories",categories.map { it.folder }.toSet()).orEmpty()
            val labels=listOf<()->String>({ if(currentGame!=null)"Scrape ${currentGame.title}" else "Choose a game to scrape" })+
                categories.map { category -> { "${category.title} · ${if(category.folder in enabled())"On" else "Off"}" } }
            menu.liveActions("Scraping",labels,{ index ->
                if(index==0) {
                    if(scraper!=null) { scraper.parentPage=showScraping;scraper.providers() }
                    else { menu.dialog.dismiss();openSystem(systems.indexOf(spec)) }
                } else {
                    val next=enabled().toMutableSet()
                    val type=categories[index-1].folder
                    if(!next.add(type))next.remove(type)
                    preferences.edit().putStringSet("media.categories",next).apply()
                }
            },{ home() })
        }
        menu.tabs(if(currentGame!=null) "Game Settings" else "System Settings",currentGame?.let { "${it.title} · ${spec.title}" } ?: spec.title,sections,section) { section=it;home() }
        menu.onShoulder={ direction -> section=(section+direction+sections.size)%sections.size;home() }
        home()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(showingCompanion)return true
        if (!ready) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) skipStartup.set(true)
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A) currentFocus?.performClick()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
            if (activeTab == 2) {
                if (settingsDetailView?.hasFocus() == true) settingsCategoryView?.requestFocus() else if(event.repeatCount==0)returnToPreviousPage()
            } else if(activeTab==3) { if(event.repeatCount==0 && themeGallery?.back()!=true)returnToPreviousPage() }
            else if(activeTab==4) { if(scrapingDetails?.hasFocus()==true)scrapingSidebar?.requestFocus() else if(event.repeatCount==0)returnToPreviousPage() }
            else if(event.repeatCount==0 && (library || activeTab!=0))returnToPreviousPage()
            return true
        }
        if (gameList?.hasFocus() == true && (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP || gameCursor > 0) {
                moveGameCursor(gameCursor + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            val list = gameList
            if (list?.hasFocus() == true && list.count > 0) {
                val position = gameCursor
                list.performItemClick(list.selectedView, position, list.getItemIdAtPosition(position))
            } else currentFocus?.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if(showingCompanion)return true
        if(keyCode in listOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B) && !event.isCanceled && ready) {
            if(OrbitThemeArtwork.screen(this)=="top")DualScreenCoordinator.returnBottomToFrontend()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            if (ready && activeTab == 0 && library && !event.isCanceled && startMenu?.isShowing != true) {
                games.getOrNull(gameCursor)?.let { game ->
                    val menu = EmulatorSettings.MenuWindow(this, compact = true)
                    val popup = GameScraperPopup(menu, system, game) {
                        artworkPanel?.show(system, game, refresh = true)
                        importedScene?.showGame(game,true)
                        gameLogos.clear()
                        refreshGameTiles()
                        DualScreenCoordinator.refreshMedia(true)
                    }
                    startMenu = menu.dialog
                    menu.dialog.setOnDismissListener {
                        popup.destroy(); startMenu = null; gameList?.requestFocus()
                        DualScreenCoordinator.refreshMedia()
                    }
                    popup.show()
                }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (ready && activeTab == 0 && populated.isNotEmpty() && !event.isCanceled &&
                activeSplitTileMenu()?.shortcutSelected!=true) {
                val target = if (library) system else systems[focusedSystemIndex.coerceIn(systems.indices)]
                showSystemMenu(target,settingsShortcutHost())
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START || keyCode == KeyEvent.KEYCODE_MENU) {
            if (ready && !event.isCanceled) openSettings()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

class CompanionActivity : MainActivity()
