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
import androidx.core.view.OneShotPreDrawListener
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

open class MainActivity : Activity() {
    var showingCompanion=false
        private set
    private var companionPanel:CompanionPanel?=null
    private val systemsMusic by lazy { SystemsMusic(this) }
    private var musicVisible = false
    private var musicDuringStartup = false

    private fun updateSystemsMusic() {
        systemsMusic.setBrowsing(
            musicVisible && !showingCompanion && (musicDuringStartup || ready && activeTab == 0 && !library && !scanning) &&
                startMenu?.isShowing != true && UserDataStore.cachedInt("systemsMusic", 1) != 0,
            visible = musicVisible && !showingCompanion
        )
    }
    private var gameMediaRoot:View?=null
    private var gameMediaSurface:GameMediaSurface?=null
    fun companionSelection(): Pair<SystemSpec,Game>? =
        if(ready && !showingCompanion && activeTab==0 && library && !scanning &&
            !EmulatorSettings.menuVisible(this) && activeSplitTileMenu()?.shortcutSelected!=true)
            games.getOrNull(gameCursor)?.let { system to it } else null
    internal fun adjacentGames(game: Game): List<Game> {
        if (games.getOrNull(gameCursor) != game) return emptyList()
        val range = if (activeGameWheel()?.isShown == true) 2 else 1
        return (1..range).flatMap { offset ->
            listOfNotNull(games.getOrNull(gameCursor + offset), games.getOrNull(gameCursor - offset))
        }
    }
    fun refreshCompanionMedia(refresh:Boolean=false) { companionPanel?.refreshMedia(refresh) }
    internal fun preloadCompanionGame(system:SystemSpec,game:Game) { companionPanel?.preloadGame(system,game) }
    internal fun showCompanionGame(system:SystemSpec,game:Game) { companionPanel?.showGame(system,game) }
    internal fun previewCompanionBackground(system: SystemSpec, game: Game) {
        companionPanel?.previewWheelBackground(system, game)
    }
    internal fun setCompanionWheelScrolling(scrolling: Boolean) {
        companionPanel?.setWheelScrolling(scrolling)
    }
    internal fun toggleCompanionVideo(system:SystemSpec,game:Game)=companionPanel?.toggleVideoPreview(system,game)==true
    internal fun finishCompanionVideo() { companionPanel?.finishVideoPreview() }
    internal val companionVideoActive get()=companionPanel?.manualVideoActive==true
    fun companionBrowse():CompanionBrowse? {
        if(!ready || showingCompanion || scanning)return null
        EmulatorSettings.companionBrowse(this)?.let { return it }
        activeSplitTileMenu()?.shortcutBrowse()?.let { return it }
        if((activeTab==0 && !library) || activeTab==3) {
            if (activeTab == 0 && focusedBrowseSystem == retroAchievementsSystem) {
                return CompanionBrowse("retroachievements", retroAchievementsSystem.title,
                    iconResource = R.drawable.ic_companion_star)
            }
            val spec=systems.getOrNull(focusedSystemIndex)?.takeIf { it in populated } ?: populated.firstOrNull() ?: return null
            val theme=OrbitThemes.current()
            val identity="${theme.id}/${theme.hashCode()}"
            return CompanionBrowse("system/${spec.id}/$identity",spec.title,"${pages[spec.id]?.games?.size ?: 0} games",system=spec)
        }
        if(activeTab==1) {
            val app=appsPanel?.companionSelection()
            return CompanionBrowse("app/${app?.info?.activityInfo?.packageName}/${app?.info?.activityInfo?.name}",app?.label ?: "Android apps",icon=app?.icon,iconResource=R.drawable.ic_companion_apps)
        }
        if(activeTab==2) {
            val names=settingsCategories
            val icons = listOf(R.drawable.ic_companion_settings, R.drawable.ic_companion_edit,
                R.drawable.ic_companion_search, R.drawable.ic_companion_star)
            return CompanionBrowse("settings/$settingsCategory",names[settingsCategory.coerceIn(names.indices)],"Settings",iconResource=icons[settingsCategory.coerceIn(icons.indices)])
        }
        if(activeTab==4)return CompanionBrowse("scraping","Scraping","Game media and metadata",iconResource=R.drawable.ic_companion_search)
        if(activeTab==3)return CompanionBrowse("themes","Themes","Choose a look for your collection",iconResource=R.drawable.ic_companion_artwork)
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
        discardPreparedArtwork()
        appsPanel?.destroy();appsPanel=null
        gameLogos.cancelPreload();discardPreparedSplitGameView()
        gameMediaRoot=null;gameMediaSurface=null
        libraryDock=null;libraryDockContent=null;dockPadding.clear()
        screenHost=null;gameScrapeProgressBar=null;tabViews.clear();tabFocus.clear();settingsKeyHandlers.clear();gameList=null;settingsKeys=null
    }
    fun showCompanionScreen() {
        cancelSwapKey();generation++;discardViews();showingCompanion=true
        updateSystemsMusic()
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
    private val settingsCategories = listOf("General", "Customisation", "Scraping", "RetroAchievements")
    private var appsPanel: AppsPanel? = null
    private var scraperSettingsPanel:ScraperSettingsPanel?=null
    private var screenHost: FrameLayout? = null
    private var gameScrapeProgressBar: GameScrapeProgressBar? = null
    private var libraryDock:BottomScreenControls?=null
    private var libraryDockContent:View?=null
    private val dockPadding=java.util.WeakHashMap<View,Int>()
    internal fun refreshBottomControls() {
        if (showingCompanion) companionPanel?.refreshBottomControls() else refreshLibraryDock()
    }
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
        updateBottomSettingsGestureArea()
    }

    private val bottomSettingsGestureLayout = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        setBottomSettingsGestureArea(view)
    }

    private fun updateBottomSettingsGestureArea() {
        val decor = window.decorView
        decor.removeOnLayoutChangeListener(bottomSettingsGestureLayout)
        if (OrbitThemeArtwork.screen(this) != "bottom") {
            decor.systemGestureExclusionRects = emptyList()
            return
        }
        decor.addOnLayoutChangeListener(bottomSettingsGestureLayout)
        setBottomSettingsGestureArea(decor)
    }

    private fun setBottomSettingsGestureArea(decor: View) {
        val edgeWidth = dp(32)
        if (decor.width <= edgeWidth || decor.height == 0) return
        // Android limits exclusions along each side. Reserve only a central strip for Orbit.
        val height = minOf(decor.height, dp(192))
        val top = (decor.height - height) / 2
        val bottom = top + height
        decor.systemGestureExclusionRects = listOf(android.graphics.Rect(0, top, edgeWidth, bottom))
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
        val focused = focusedBrowseSystem
        if (focused == retroAchievementsSystem) return fallback
        val spec=focused?.takeIf { it in populated } ?: populated.firstOrNull() ?: return fallback
        val screen=OrbitThemeArtwork.artworkScreen(this)
        if(theme.heroImage(screen,spec.id)==null)return fallback
        return OrbitThemeArtwork.systemHeroBackground(this,theme,spec,screen,fallback).also { hero ->
            OrbitThemeArtwork.requestHero(this,theme,spec,screen,hero::invalidateSelf)
        }
    }
    private fun focusSystem(spec:SystemSpec) {
        focusedSystemIndex=systems.indexOf(spec)
        val wheel=nativeSystems?.takeIf { it.isShown && systemsAppearance()==SystemsAppearance.VERTICAL_SCROLL }
        if(spec==retroAchievementsSystem) wheel?.removeCallbacks(warmWheelSelection)
        else if(wheel==null)preloadListArtwork(spec)
        else {
            // Keep the preview in sync, but wait until the wheel settles to warm its game list.
            wheel.removeCallbacks(warmWheelSelection)
            wheel.postDelayed(warmWheelSelection,160L)
        }
        if(activeTab==0 && !library)tabViews[0]?.let { it.background=pageBackground(it) }
        DualScreenCoordinator.refreshMedia()
    }
    private val warmWheelSelection=Runnable {
        if(nativeSystems?.isShown==true)systems.getOrNull(focusedSystemIndex)?.let(::preloadListArtwork)
    }
    private fun present(contentView: View) {
        finishCrossfade()
        val view=if(contentView===gameMediaRoot)gameMediaSurface ?: contentView else contentView
        if (view is GameMediaSurface) view.prepareGameView()
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
                val progress = GameScrapeProgressBar(this@MainActivity)
                gameScrapeProgressBar = progress
                addView(progress, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                    rightMargin = dp(10)
                })
            }
            super.setContentView(shell)
        }
        val previousLibraryPage = tabViews[if (library) 0 else 6]
        val systemSwitch = pendingGameListSwitch.takeIf { !warmingTabs && activeTab == 0 && library }
        if (!warmingTabs) pendingGameListSwitch = null
        val switchOutgoing = systemSwitch?.let { tabViews[6] }?.takeIf { it !== view && it.isShown && it.parent === host }
        val switchBackground = host.background
        val switchingLibraryViews = !warmingTabs && activeTab == 0 &&
            previousLibraryPage != null && previousLibraryPage !== view && previousLibraryPage.isShown
        if(!warmingTabs) {
            if(library || activeTab!=0) {
                gameLogos.cancelPreload()
                discardPreparedSplitGameView()
            }
            refreshThemeBackdrop()
            val topSettings=activeTab==2 && OrbitThemeArtwork.screen(this)=="top"
            val themed=ready && (activeTab==0 || topSettings || OrbitThemeArtwork.image(this,OrbitThemes.current(),"background")!=null)
            val splitPage=activeTab==0 && view.findViewWithTag<View>(SplitTileMenu.TAG)!=null
            val switchingSplitPages = splitPage && switchingLibraryViews &&
                previousLibraryPage?.findViewWithTag<View>(SplitTileMenu.TAG) != null
            if(!switchingSplitPages)applyWindowChrome(themed)
            // Keep the old background through the animation.
            val animationView = libraryTransitionView(library)
            if (switchingLibraryViews && pageTransition(animationView) != instantTransition) {
                crossfadeOutgoing=previousLibraryPage
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
            gameScrapeProgressBar?.let { progress ->
                val margin = padded.paddingTop + dp(6)
                val params = progress.layoutParams as FrameLayout.LayoutParams
                if (params.topMargin != margin) {
                    params.topMargin = margin
                    progress.layoutParams = params
                }
                progress.inGameView = ready && activeTab == 0 && library
            }
            (host.layoutParams as FrameLayout.LayoutParams).apply {
                if(topMargin!=0) {
                    topMargin=0
                    host.layoutParams=this
                }
            }
        }
        val slot = if (!ready) -1 else if (activeTab == 0 && library) 6 else activeTab
        val previous = tabViews.put(slot, view)
        if (previous != null && previous !== view && previous !== switchOutgoing) host.removeView(previous)
        if (view.parent == null) host.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (!warmingTabs) {
            updateSystemsMusic()
            DualScreenCoordinator.refreshMedia()
        }
        if (warmingTabs) view.visibility = View.INVISIBLE
        else for(i in 0 until host.childCount) {
            val child=host.getChildAt(i)
            child.visibility=if(child===view)View.VISIBLE else View.INVISIBLE
        }
        if (switchingLibraryViews) {
            val gridTag = if (library) "orbit-game-list" else "orbit-systems-grid"
            (view.findViewWithTag<View>(gridTag) as? SplitLibraryGrid)?.cascadeIn()
        }
        val outgoing=crossfadeOutgoing
        crossfadeOutgoing=null
        if (switchOutgoing != null) {
            startCrossfade(host, switchOutgoing, switchBackground, view, opening = true, systemSwitch = systemSwitch)
        } else if(!warmingTabs && outgoing!=null && outgoing.parent===host) {
            startCrossfade(host,outgoing,crossfadeBackground,view,opening=crossfadeOpening)
        }
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
    private enum class SystemSwitchAnimation(val label: String, val transition: Int) {
        INSTANT("Instant", 3),
        FADE("Fade", Transition.FADE),
        SLIDE("Slide", Transition.SLIDE_X),
        ZOOM("Zoom", Transition.ZOOM)
    }
    private data class GameListSwitch(val animation: SystemSwitchAnimation, val speed: Int, val slideDirection: Int)
    private var pendingGameListSwitch: GameListSwitch? = null
    private val systemSwitchAnimationKey = "gameListSystemSwitchAnimation"
    private val systemSwitchSpeedKey = "gameListSystemSwitchSpeed"
    private val systemSwitchDirectionKey = "gameListSystemSwitchDirection"
    private val slideDirectionLabels = listOf("From right", "From left", "From bottom", "From top")
    private val systemSwitchDirectionLabels = listOf("Follow L/R") + slideDirectionLabels
    private fun systemSwitchAnimation() = SystemSwitchAnimation.entries.getOrElse(
        TransitionSettings.int(systemSwitchAnimationKey, 0, TransitionSettings.View.GAMES)
    ) { SystemSwitchAnimation.INSTANT }
    private fun systemSwitchSpeed() = TransitionSettings.int(systemSwitchSpeedKey, 1, TransitionSettings.View.GAMES)
        .coerceIn(transitionSpeedLabels.indices)
    private fun systemSwitchDirection() = TransitionSettings.int(systemSwitchDirectionKey, 0, TransitionSettings.View.GAMES)
        .coerceIn(systemSwitchDirectionLabels.indices)
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
    private fun pageTransition(view: TransitionSettings.View) =
        TransitionSettings.int(pageTransitionKey, 0, view).coerceIn(pageTransitionLabels.indices)
    private fun isSlideTransition(style: Int) = style == Transition.SLIDE_X || style == Transition.SLIDE_Y

    private fun libraryTransitionView(opening: Boolean): TransitionSettings.View {
        val games = TransitionSettings.View.GAMES
        val systems = TransitionSettings.View.SYSTEMS
        // A slide covers both pages. Use one direction and speed so Back retraces the same route.
        return when {
            isSlideTransition(pageTransition(games)) -> games
            isSlideTransition(pageTransition(systems)) -> systems
            opening -> games
            else -> systems
        }
    }
    private fun nextPageTransition(current:Int):Int {
        val position=pageTransitionOrder.indexOf(if(current==Transition.SLIDE_Y)Transition.SLIDE_X else current)
        return pageTransitionOrder[(position+1)%pageTransitionOrder.size]
    }
    /** The Direction choices for a style, or null when the style has no direction. */
    private fun transitionDirectionLabels(style:Int):List<String>? = when(style) {
        Transition.SLIDE_X,Transition.SLIDE_Y -> slideDirectionLabels
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
    private fun transitionDirection(style: Int, view: TransitionSettings.View): Int {
        val labels=transitionDirectionLabels(style) ?: return 0
        val default=if(style==Transition.SLIDE_Y)2 else 0
        return TransitionSettings.int(transitionDirectionKey(style), default, view).coerceIn(labels.indices)
    }
    private val transitionSpeedKey="transitionSpeed"
    private val transitionSpeedLabels=listOf("Slow","Normal","Fast","Very fast")
    private val transitionSpeedFactors=listOf(1.4f,1f,0.75f,0.55f)
    private fun transitionSpeed(view: TransitionSettings.View) =
        TransitionSettings.int(transitionSpeedKey, 1, view).coerceIn(transitionSpeedLabels.indices)
    private val flipDirectionKey="flipDirection"
    private val flipDirectionLabels=listOf("Sideways","Forwards","Backwards")

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
        incoming:View,opening:Boolean, systemSwitch: GameListSwitch? = null) {
        val animationView = libraryTransitionView(opening)
        val style = systemSwitch?.animation?.transition ?: pageTransition(animationView)
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
        val slideDirection = if (style == Transition.SLIDE_X || style == Transition.SLIDE_Y) {
            systemSwitch?.slideDirection ?: transitionDirection(style, animationView)
        } else 0
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
        val tile=if(systemSwitch==null && style==Transition.ZOOM && transitionDirection(style, animationView)==0)selectedTileRect(systemsPage,host) else null
        // Scale Direction: 0 grows in from slightly smaller, 1 shrinks in from slightly larger.
        val scaleFrom=if(transitionDirection(Transition.SCALE, animationView)==0)0.92f else 1.08f
        val zoomFrom=tile?.let { maxOf(it.width()/host.width,it.height()/host.height).coerceIn(0.05f,1f) }
            ?: if (systemSwitch != null) 0.88f else 0.3f
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
                if (systemSwitch != null) host.removeView(outgoing)
                else outgoing.visibility=if(outgoing===activeHostPage())View.VISIBLE else View.INVISIBLE
            }
        }
        crossfadeEnd=end
        // Speed scales every duration. Fades keep a floor, because fading faster than about 100 ms
        // brought the Thor's flash back; motion (slide, zoom, scale, flip) can go all the way.
        val speed = transitionSpeedFactors[systemSwitch?.speed ?: transitionSpeed(animationView)]
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
        val flip = transitionDirection(Transition.FLIP, animationView)
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
        this, ::selectedScrapingSystems, { spec -> pages[spec.id]?.games.orEmpty() }
    ) {
        GameArtworkPanel.clearMediaCache();EsDeScene.clearMediaCache();gameLogos.clear();preloadKey=null
        discardPreparedArtwork()
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
    private val retroAchievementsWorker = Executors.newSingleThreadExecutor()
    private val libraryRefreshWorker=Executors.newSingleThreadExecutor()
    private lateinit var repository: LibraryRepository
    private lateinit var root: LinearLayout
    private var ready = false
    private var populated = emptyList<SystemSpec>()
    private val browsingSystems get() = populated
    private val focusedBrowseSystem get() = systems.getOrNull(focusedSystemIndex)
    private data class LibraryPage(val games: List<Game>, val errors: List<String>)
    private var pages = emptyMap<String, LibraryPage>()
    private var gameList: AbsListView? = null
    private var artworkPanel: GameArtworkPanel? = null
    private var preparedArtworkPanel: GameArtworkPanel? = null
    private var colorfulSystems: ColorfulSystemsView? = null
    private var importedSystems:ImportedSystemsView?=null
    private var importedScene:EsDeScene?=null
    private var gamePreloader:EsDeScene?=null
    private var preloadKey:String?=null
    internal fun firstGameForArtwork(spec: SystemSpec): Game? =
        if (ready && !library && !scanning && OrbitThemes.current().libraryScreen == "top") pages[spec.id]?.games?.firstOrNull() else null

    private fun preloadListArtwork(spec: SystemSpec) {
        if (!ready || showingCompanion || activeTab != 0 || library || scanning) return
        val entries = pages[spec.id]?.games.orEmpty()
        val first = entries.firstOrNull() ?: return
        val panel = preparedArtworkPanel ?: GameArtworkPanel(this, OrbitThemes.current().systemLayout == "artwork")
            .also { preparedArtworkPanel = it }
        panel.preload(spec, first)
        if (gameAppearance() != GameAppearance.THEME_DEFAULT) {
            val media = gameWheelMedia()
            gameLogos.preload(spec, media.artworkType, delayMillis = 75,
                spinDiscs = media == GameWheelMedia.PHYSICAL_MEDIA && spinDiscsEnabled()) { entries.take(8) }
        } else if (UserDataStore.cachedInt("nativeListLogos", 0) == 1) {
            gameLogos.preload(spec, "marquee", delayMillis = 75) { entries.take(8) }
        }
    }

    private fun discardPreparedArtwork() {
        preparedArtworkPanel?.destroy()
        preparedArtworkPanel = null
    }

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
        if (library) activeGameWheel()?.let { it.select(position, animate = false); return }
        if(library)activeSplitGrid()?.let { it.selectLibrary(position);return }
        val list = gameList ?: return
        if (list.count == 0) return
        val previous = gameCursor
        gameCursor = position.coerceIn(0, list.count - 1)
        if (gameCursor == previous) return
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
        if (row == null || row.top != targetTop) list.setSelectionFromTop(gameCursor, targetTop - top)
        list.getChildAt(previous - first)?.let { list.adapter.getView(previous, it, list) }
        row?.let { list.adapter.getView(gameCursor, it, list) }
        artworkPanel?.show(system, games.getOrNull(gameCursor))
        games.getOrNull(gameCursor)?.let { artworkPanel?.warmAdjacentBackgrounds(system, adjacentGames(it)) }
        importedScene?.showGame(games.getOrNull(gameCursor))
        DualScreenCoordinator.refreshMedia()
    }
    private fun page(report: RomScanResult) = LibraryPage(report.games.map { Game(it.title, android.net.Uri.parse(it.uri), it.fileName,it.directory) }, report.errors)
    private fun openBrowseSystem(spec: SystemSpec) {
        if (spec == retroAchievementsSystem) {
            openRetroAchievementsPage()
        } else openSystem(systems.indexOf(spec))
    }

    private fun openRetroAchievementsPage() {
        settingsCategory = settingsCategories.indexOf(retroAchievementsSystem.title)
        tabViews.remove(2)?.let { screenHost?.removeView(it) }
        tabFocus.remove(2)
        navigateTab(2)
    }

    private fun openSystem(index: Int) {
        rememberPage(PageDestination(0,true))
        activeTab = 0; selected = index; library = true
        gameCursor = 0
        val cached = pages[system.id]
        if (cached == null) { scan(); return }
        generation++; scanning = false; games = cached.games
        error = cached.errors.takeIf { it.isNotEmpty() }?.let { "${games.size} games · ${it.size} folder errors. Check ROM library settings." }
        render()
    }

    private fun switchGameListSystem(step: Int) {
        if (populated.size < 2) return
        val current = populated.indexOf(system)
        val next = if (current < 0) {
            if (step < 0) populated.last() else populated.first()
        } else populated[Math.floorMod(current + step, populated.size)]
        val index = systems.indexOf(next)
        if (index < 0 || index == selected) return
        activeGameWheel()?.endMotion()
        focusedSystemIndex = index
        val animation = systemSwitchAnimation()
        val direction = systemSwitchDirection()
        val slideDirection = if (direction == 0) (if (step < 0) 1 else 0) else direction - 1
        pendingGameListSwitch = if (animation == SystemSwitchAnimation.INSTANT) null
            else GameListSwitch(animation, systemSwitchSpeed(), slideDirection)
        openSystem(index)
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
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
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
        val restoreGameList = state?.getBoolean("library") == true
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
                UserDataStore.initialize(this)
                if (!restoreGameList) runOnUiThread {
                    if (!isDestroyed && !isFinishing && !ready && !showingCompanion &&
                        intent.getIntExtra("navigationTab", 0) == 0) {
                        musicDuringStartup = true
                        updateSystemsMusic()
                    }
                }
                SystemCatalog.initialize(this)
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
                musicDuringStartup = false
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
                }.onFailure {
                    updateSystemsMusic()
                    showDataError(it)
                }
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
        if (!ready && intent.getIntExtra("navigationTab", 0) != 0) {
            musicDuringStartup = false
            updateSystemsMusic()
        }
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
    override fun onStart() {
        super.onStart()
        musicVisible = true
        updateSystemsMusic()
        companionPanel?.start()
        DualScreenCoordinator.attach(this)
    }
    override fun onResume() {
        super.onResume()
        FrontendNavigationBar.apply(this)
        if(showingCompanion) {
            DualScreenCoordinator.companionResumed(this)
            return
        }
        updateSystemsMusic()
        refreshLibraryDock()
        if(ready)FrontendRefreshRate.apply(this)
        if(ready && activeTab==1)appsPanel?.resume()
        if(ready && library && activeTab==0) {
            artworkPanel?.show(system,games.getOrNull(gameCursor),true)
            importedScene?.showGame(games.getOrNull(gameCursor),true)
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FrontendNavigationBar.apply(this)
            if (!showingCompanion) updateSystemsMusic()
        }
    }
    override fun onPause() {
        pendingSplitSystemOpen=null;cancelSwapKey();artworkPanel?.pause();importedScene?.pause()
        if(!showingCompanion)DualScreenCoordinator.finishCompanionVideo()
        super.onPause()
    }
    override fun onStop() {
        musicVisible = false
        updateSystemsMusic()
        companionPanel?.stop()
        DualScreenCoordinator.detach(this)
        super.onStop()
    }
    override fun onDestroy() { remoteSettingsDialog?.dismiss();systemsMusic.destroy();mediaTransferPanel?.destroy();scraperSettingsPanel?.destroy();themeBackdrop?.destroy();gamePreloader?.destroy();importedSystems?.destroy();importedScene?.destroy();gameLogos.destroy();companionPanel?.destroy();if(showingCompanion)DualScreenCoordinator.panelDestroyed(this);colorfulSystems?.destroy(); artworkPanel?.destroy(); discardPreparedArtwork(); appsPanel?.destroy(); skipStartup.set(true); generation++; libraryRefreshWorker.shutdownNow();worker.shutdownNow();retroAchievementsWorker.shutdownNow(); super.onDestroy() }
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
        useSplitGameGrid = splitDisplays
        val visibleSystems = browsingSystems
        val focusedPosition = visibleSystems.indexOf(focusedBrowseSystem).coerceAtLeast(0)
        val gameView = gameAppearance()
        if (library && !scanning && games.isNotEmpty() && gameView != GameAppearance.THEME_DEFAULT) {
            renderGameWheel(nativeTheme, gameView, splitDisplays)
            return
        }
        val appearance = systemsAppearance()
        val useTileGrid = if (library) splitDisplays else when (appearance) {
            SystemsAppearance.THEME_DEFAULT -> splitDisplays
            SystemsAppearance.HORIZONTAL_VIEW, SystemsAppearance.VERTICAL_SCROLL -> false
            SystemsAppearance.VERTICAL_GRID, SystemsAppearance.HORIZONTAL_SCROLL -> true
        }
        if(useTileGrid && !scanning && populated.isNotEmpty() && (!library || games.isNotEmpty())) {
            renderSplitLibrary(nativeTheme)
            return
        }
        val showSystemHero=screen!="bottom"
        val verticalScroll = appearance == SystemsAppearance.VERTICAL_SCROLL
        val useArtworkCarousel = verticalScroll || appearance == SystemsAppearance.HORIZONTAL_VIEW || nativeTheme.systemLayout == "artwork" ||
            OrbitThemeArtwork.image(this, nativeTheme, "hero", target = screen) != null
        if(useArtworkCarousel && !library && populated.isNotEmpty() && !scanning) {
            val scene=OrbitSystemsView(this,visibleSystems,focusedPosition,
                { pages[it.id]?.games?.size ?: 0 },::focusSystem,::openBrowseSystem,
                showHero=showSystemHero, verticalScroll=verticalScroll)
            root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(nativeTheme.background);addView(scene,LinearLayout.LayoutParams(-1,-1)) }
            nativeSystems=scene;present(root);tabFocus[0]=scene
            scene.post { if(activeTab==0 && !library)scene.requestFocus() }
            return
        }
        val externalTheme=ImportedThemes.active(this)
        if(externalTheme!=null && !library && populated.isNotEmpty() && !scanning) {
            val scene=ImportedSystemsView(this,externalTheme,visibleSystems,focusedPosition,
                { pages[it.id]?.games?.size ?: 0 },{ focusedSystemIndex=systems.indexOf(it);preloadFirstGame(it);refreshThemeBackdrop();DualScreenCoordinator.refreshMedia() },::openBrowseSystem)
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
            val view = ColorfulSystemsView(this,visibleSystems,focusedPosition,
                { pages[it.id]?.games?.size ?: 0 }, { focusedSystemIndex=systems.indexOf(it);DualScreenCoordinator.refreshMedia() }, ::openBrowseSystem)
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
            val focused = visibleSystems[focusedPosition]
            visibleSystems.forEach { spec ->
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
                    val titleLabel = text(spec.title, 15f)
                    if (spec == retroAchievementsSystem) {
                        titleLabel.gravity = Gravity.CENTER
                        titleLabel.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.btn_star_big_on, 0, 0)
                    }
                    val logo = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        visibility = View.GONE
                    }
                    addView(titleLabel)
                    addView(logo, LinearLayout.LayoutParams(-1, dp(30)))
                    fun bindLogo() {
                        val bitmap = OrbitThemeArtwork.systemLogo(nativeTheme, spec)
                        logo.setImageBitmap(bitmap)
                        logo.visibility = if (bitmap == null) View.GONE else View.VISIBLE
                        titleLabel.visibility = if (bitmap == null) View.VISIBLE else View.GONE
                        contentDescription = spec.title
                    }
                    bindLogo()
                    OrbitThemeArtwork.requestSystemLogo(this@MainActivity, nativeTheme, spec, ::bindLogo)
                    setOnFocusChangeListener { v, focused ->
                        if (focused) { tabFocus[0] = v; rail.reveal(v);focusSystem(spec) }
                        v.background = surface(nativeTheme.surface, if (focused) blue else Color.rgb(38, 49, 65))
                    }
                    setOnClickListener { carouselPosition = rail.scrollX; openBrowseSystem(spec) }
                }
                cards.addView(card, LinearLayout.LayoutParams(dp(190), dp(164)).apply { setMargins(0, dp(10), dp(12), dp(12)) })
                if (spec == focused) tabFocus[0] = card
            }
            rail.addView(cards); body.addView(rail)
            rail.restore(carouselPosition) {
                if (startMenu?.isShowing != true) cards.getChildAt(focusedPosition)?.requestFocus()
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
                scene.onReady={ if(!isDestroyed && activeTab==0 && library && root === preparedRoot)present(preparedRoot) }
                importedScene=scene
                root.addView(scene,LinearLayout.LayoutParams(-1,0,1f))
                scene.initialise(system,entries.size,entries.firstOrNull())
            } else {
            val artwork = (preparedArtworkPanel.takeIf { colorfulModel == null }
                ?: GameArtworkPanel(this, nativeTheme.systemLayout=="artwork", colorfulModel)).also {
                if (it === preparedArtworkPanel) preparedArtworkPanel = null else discardPreparedArtwork()
                artworkPanel = it
            }
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
                val content = object : LinearLayout(this) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val width = MeasureSpec.getSize(widthMeasureSpec)
                        val height = MeasureSpec.getSize(heightMeasureSpec)
                        val stacked = !usesThorLayout && height >= dp(480) && width < height && width < dp(600)
                        orientation = if (stacked) VERTICAL else HORIZONTAL
                        (list.layoutParams as LayoutParams).apply {
                            this.width = if (stacked) -1 else 0
                            this.height = if (stacked) 0 else -1
                            weight = when {
                                stacked -> 0.58f
                                usesThorLayout -> nativeTheme.listFraction
                                else -> nativeTheme.listFraction.coerceAtLeast(
                                    minOf(0.5f, dp(145).toFloat() / width.coerceAtLeast(1)))
                            }
                        }
                        (artwork.view.layoutParams as LayoutParams).apply {
                            this.width = if (stacked) -1 else 0
                            this.height = if (stacked) 0 else -1
                            weight = 1f - (list.layoutParams as LayoutParams).weight
                        }
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }
                content.addView(list, LinearLayout.LayoutParams(0, -1, nativeTheme.listFraction))
                content.addView(artwork.view, LinearLayout.LayoutParams(0, -1, 1f-nativeTheme.listFraction))
                root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            artwork.show(system, entries.firstOrNull())
            entries.firstOrNull()?.let { artwork.warmAdjacentBackgrounds(system, adjacentGames(it)) }
            }
            list.post { if (startMenu?.isShowing != true) { list.requestFocus(); if (entries.isNotEmpty()) list.setSelection(0) } }
        }
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        if (!library) footer.addView(text("D-PAD  Navigate     A  Open     SELECT  System settings     START  Menu", 11f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        if (!library) root.addView(footer)
    }

    private enum class SystemsAppearance(val label: String, val savedName: String? = null) {
        THEME_DEFAULT("Theme default"),
        HORIZONTAL_VIEW("Horizontal view"),
        VERTICAL_SCROLL("Vertical scroll", savedName = "VERTICAL_WHEEL"),
        VERTICAL_GRID("Vertical grid", savedName = "VERTICAL_SCROLL"),
        HORIZONTAL_SCROLL("Horizontal scroll")
    }

    private enum class GameAppearance(val label: String) {
        THEME_DEFAULT("Theme default"),
        VERTICAL_WHEEL("Vertical wheel"),
        HORIZONTAL_WHEEL("Horizontal wheel")
    }

    private enum class GameWheelMedia(val label: String, val artworkType: String) {
        MARQUEES("Marquees", "marquee"),
        BOX_ART("2D box art", "cover"),
        BOX_ART_3D("3D box art", "3dbox"),
        PHYSICAL_MEDIA("Physical media", "physicalmedia");

        val sizeKey get() = "gameWheelSize.$name"
        val defaultSize get() = if (this == MARQUEES) GameWheelSize.MEDIUM else GameWheelSize.LARGE
    }

    private enum class GameWheelSize(val label: String, val scale: Float) {
        SMALL("Small", 0.75f),
        MEDIUM("Medium", 1f),
        LARGE("Large", 1.35f),
        EXTRA_LARGE("Extra large", 1.60f)
    }

    private fun gameWheelSize(media: GameWheelMedia): GameWheelSize {
        val stored = UserDataStore.cachedString("settings", media.sizeKey)
        return GameWheelSize.entries.firstOrNull { it.name == stored } ?: media.defaultSize
    }

    private val discSpinKey = "splitGameDiscSpin"
    private fun spinDiscsEnabled() = UserDataStore.cachedInt(discSpinKey, 0) == 1

    private fun gameWheelMedia(): GameWheelMedia {
        val stored = UserDataStore.cachedString("settings", "gameWheelMedia")
        return GameWheelMedia.entries.firstOrNull { it.name == stored } ?: GameWheelMedia.MARQUEES
    }

    private fun gameAppearance(): GameAppearance {
        val stored = UserDataStore.cachedString("settings", "gameAppearance")
        return GameAppearance.entries.firstOrNull { it.name == stored } ?: GameAppearance.THEME_DEFAULT
    }

    private fun activeGameWheel() = tabViews[6]?.findViewWithTag<GameWheelView>("orbit-game-wheel")

    private fun renderGameWheel(theme: OrbitThemes.Theme, appearance: GameAppearance, splitDisplays: Boolean) {
        val spec = system
        val entries = games
        gameCursor = gameCursor.coerceIn(entries.indices)
        val panel = if (splitDisplays) null else {
            (preparedArtworkPanel ?: GameArtworkPanel(this, theme.systemLayout == "artwork")).also {
                preparedArtworkPanel = null
                artworkPanel = it
            }
        }
        val vertical = appearance == GameAppearance.VERTICAL_WHEEL
        val media = gameWheelMedia()
        val artworkScale = gameWheelSize(media).scale
        lateinit var wheel: GameWheelView
        val showSelectedMedia = {
            if (activeTab == 0 && library && wheel.isShown && activeGameWheel() === wheel) {
                val game = entries[gameCursor]
                panel?.show(spec, game)
                panel?.warmAdjacentBackgrounds(spec, adjacentGames(game))
                DualScreenCoordinator.refreshMedia()
            }
        }
        val showWheelBackground = Runnable {
            if (activeTab == 0 && library && wheel.isShown && activeGameWheel() === wheel) {
                val game = entries[gameCursor]
                panel?.previewWheelBackground(spec, game) {
                    activeTab == 0 && library && wheel.isShown && activeGameWheel() === wheel && entries.getOrNull(gameCursor) == game
                }
                DualScreenCoordinator.previewWheelBackground(this, spec, game)
            }
        }
        wheel = GameWheelView(this, entries, vertical, artworkScale, gameCursor, theme,
            bindArtwork = { cell, label, game, scheduleArtwork ->
                gameLogos.bind(cell, label, spec, game, true, mediaType = media.artworkType,
                    spinDiscs = media == GameWheelMedia.PHYSICAL_MEDIA && spinDiscsEnabled(),
                    blendFromTitle = true, scheduleArtwork = scheduleArtwork)
            },
            recycleArtwork = gameLogos::recycle,
            prioritizeArtwork = gameLogos::prioritize,
            selected = { index ->
                gameCursor = index
                wheel.removeCallbacks(showWheelBackground)
                wheel.postOnAnimation(showWheelBackground)
            },
            motionChanged = { scrolling ->
                gameMediaSurface?.takeIf { activeGameWheel() === wheel }?.setWheelScrolling(scrolling)
                panel?.setWheelScrolling(scrolling)
                DualScreenCoordinator.setWheelScrolling(this, scrolling)
            },
            settled = {
                wheel.removeCallbacks(showWheelBackground)
                showSelectedMedia()
            },
            open = { index -> chooseEmulator(entries[index]) }
        ).apply { tag = "orbit-game-wheel" }
        root = LinearLayout(this).apply {
            orientation = if (!vertical || resources.configuration.screenWidthDp < 500) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
            if (panel == null) addView(wheel, LinearLayout.LayoutParams(-1, -1))
            else if (orientation == LinearLayout.HORIZONTAL) {
                addView(wheel, LinearLayout.LayoutParams(0, -1, theme.listFraction))
                addView(panel.view, LinearLayout.LayoutParams(0, -1, 1f - theme.listFraction))
            } else {
                val wheelShare = if (vertical) 0.35f else (0.35f * artworkScale).coerceAtMost(0.85f)
                addView(panel.view, LinearLayout.LayoutParams(-1, 0, 1f - wheelShare))
                addView(wheel, LinearLayout.LayoutParams(-1, 0, wheelShare))
            }
        }
        if (panel != null) {
            gameMediaRoot = root
            gameMediaSurface = GameMediaSurface(this, root).also {
                panel.backgroundMediaChanged = it::update
                panel.backgroundMediaPaused = it::pause
            }
        }
        present(root)
        tabFocus[6] = wheel
        if (startMenu?.isShowing != true) wheel.requestFocus()
        showSelectedMedia()
    }

    private val systemsAppearanceKey = "systemsAppearance"
    private var useSplitGameGrid = false

    private fun systemsAppearance(): SystemsAppearance {
        val saved = UserDataStore.cachedString("settings", systemsAppearanceKey)
        return SystemsAppearance.entries.firstOrNull { (it.savedName ?: it.name) == saved } ?: SystemsAppearance.THEME_DEFAULT
    }

    private fun splitTileArrangement(prefix:String,key:String,fallback:SplitLibraryGrid.Arrangement):SplitLibraryGrid.Arrangement {
        val layouts=SplitLibraryGrid.Arrangement.entries
        return layouts[UserDataStore.cachedInt(prefix+key,fallback.ordinal).coerceIn(layouts.indices)]
    }

    private val physicalMediaKey="splitGameTilePhysicalMedia"
    private fun physicalMediaTiles()=UserDataStore.cachedInt(physicalMediaKey,0)==1

    private fun preloadSplitGames(grid:SplitLibraryGrid, initial: Boolean = false) {
        val spec=grid.selectedLibraryIndex?.let(populated::getOrNull)
        if (!useSplitGameGrid) {
            spec?.let(::preloadListArtwork)
            return
        }
        if (gameAppearance() != GameAppearance.THEME_DEFAULT) {
            spec?.let(::preloadListArtwork)
            return
        }
        val entries=spec?.let { pages[it.id]?.games }
        if(grid.scrollState!=RecyclerView.SCROLL_STATE_IDLE || grid.shortcutSelected || spec==null || entries.isNullOrEmpty()) {
            gameLogos.cancelPreload()
            return
        }
        val physicalMedia = physicalMediaTiles()
        gameLogos.preload(spec, if (physicalMedia) "physicalmedia" else "cover",
            fallback = if (physicalMedia) "cover" else null,
            delayMillis = if (initial) 0L else 150L, onReady = {
            if(grid.isShown && activeTab==0 && !library && grid.scrollState==RecyclerView.SCROLL_STATE_IDLE &&
                matchingSplitGamePage(preparedSplitGameView,spec,entries,OrbitThemes.current())!=null)
                stagePreparedSplitGameView()
        }, spinDiscs = physicalMedia && spinDiscsEnabled()) {
            val tile=grid.cursorTile
            if(!grid.isShown || activeTab!=0 || library || grid.scrollState!=RecyclerView.SCROLL_STATE_IDLE ||
                (!initial && (tile==null || tile.right<=0 || tile.left>=grid.width)))emptyList()
            else {
                val prefix="splitGameTile"
                val legacy=splitTileArrangement(prefix,"Size",SplitLibraryGrid.Arrangement.TWO_ROWS)
                val layout=splitTileArrangement(prefix,"Layout",legacy)
                // Leave room for the system title above the game grid.
                val width = grid.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                val height = grid.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                val columnWidth=layout.columnWidth(width,(height-dp(48)).coerceAtLeast(1))
                val visibleSlots=(((width+columnWidth-1)/columnWidth)*layout.rows).coerceAtMost(32)
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
        if(grid.isShown && startMenu?.isShowing!=true && !grid.requestFocus())
            grid.post { if(grid.isShown && startMenu?.isShowing!=true)grid.requestFocus() }
    }

    private fun renderSplitLibrary(theme:OrbitThemes.Theme) {
        val inGames=library
        val openingGames=inGames && tabViews[0]?.isShown==true
        val previousSlot = if (inGames) null else activeSplitGrid()?.cursorPosition
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
        val libraryKeys=if(inGames)entries.map { it.uri.toString() } else browsingSystems.map { it.id }
        val slots=SplitTileSlots(this,if(inGames)"games/${spec.id}" else "systems",libraryKeys)
        val initialSlot = if (inGames) 0 else previousSlot
            ?: slots.librarySlot(browsingSystems.indexOf(focusedBrowseSystem)) ?: 0
        val (view,grid)=createSplitGrid(theme,inGames,spec,entries,slots)
        if(inGames)gameCursor=0
        root=view
        showSplitGrid(grid,initialSlot,openingGames=openingGames)
        if (!inGames) preloadSplitGames(grid, initial = true)
    }

    private fun createSplitGrid(
        theme:OrbitThemes.Theme,inGames:Boolean,spec:SystemSpec,entries:List<Game>,slots:SplitTileSlots
    ):Pair<LinearLayout,SplitLibraryGrid> {
        val systemsHere=if(inGames) emptyList() else browsingSystems
        val verticalScroll = !inGames && systemsAppearance() == SystemsAppearance.VERTICAL_GRID
        val prefix = when {
            inGames -> "splitGameTile"
            verticalScroll -> "verticalSystemTile"
            else -> "splitSystemTile"
        }
        val scaleKey=prefix+"Scale"
        fun spinDiscs() = spinDiscsEnabled()
        val defaultLayout = if (verticalScroll) SplitLibraryGrid.Arrangement.FOUR_ROWS else SplitLibraryGrid.Arrangement.TWO_ROWS
        val legacyLayout=splitTileArrangement(prefix,"Size",defaultLayout)
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
        val animationView = if (inGames) TransitionSettings.View.GAMES else TransitionSettings.View.SYSTEMS
        grid=SplitLibraryGrid(this,theme,slots,animationView,
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
                    systemsHere[index] == retroAchievementsSystem -> openRetroAchievementsPage()
                    else -> openSplitSystem(grid,systems.indexOf(systemsHere[index]))
                }
            }, hold = { tileMenu.show() }, changeLayout = { changeLayout(it, true) }, verticalScroll = verticalScroll)
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
                if (targetSystem() != retroAchievementsSystem) runCatching { GameLaunchDisplay.save(this,targetSystem(),targetGame(),screen) }.onFailure(::showDataError)
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
            },openSettings={
                val target = targetSystem()
                if (target == retroAchievementsSystem) openRetroAchievementsPage()
                else showSystemMenu(target,settingsShortcutHost())
            }, canSetLaunchScreen = { targetSystem() != retroAchievementsSystem })
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
        if (spec == retroAchievementsSystem) {
            image.setImageDrawable(null)
            image.visibility = View.INVISIBLE
            label.setCompoundDrawablesWithIntrinsicBounds(0, android.R.drawable.btn_star_big_on, 0, 0)
            label.visibility = View.VISIBLE
            return
        }
        label.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        val screen=OrbitThemeArtwork.artworkScreen(this)
        val visible=theme.showSystemArtwork(spec.id,screen)
        fun bind() {
            if(cell.tag!=spec.id)return
            val bitmap=OrbitThemeArtwork.systemLogo(theme,spec)
                ?: if(visible)OrbitThemeArtwork.systemImage(this,theme,spec,screen) ?: OrbitConsoleImages.cached(spec) else null
            image.setImageBitmap(bitmap)
            image.visibility=if(bitmap==null)View.INVISIBLE else View.VISIBLE
            label.visibility=if(bitmap==null)View.VISIBLE else View.INVISIBLE
        }
        bind()
        OrbitThemeArtwork.requestSystemLogo(this,theme,spec,::bind)
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
    private fun chooseEmulator(game: Game, screenOverride:String?=null, spec:SystemSpec=system) {
        val emulatorRepository=EmulatorProfileRepository(this)
        val savedProfile=emulatorRepository.selectedForGame(spec,game)
        val profile = savedProfile ?: InstalledEmulators.available(this,emulatorRepository.forSystem(spec))
            .filter { candidate -> candidate.installed(this) &&
                (candidate.filenameRegex?.let { Regex(it,RegexOption.IGNORE_CASE).matches(game.fileName) }
                    ?: (game.fileName.substringAfterLast('.').lowercase() in candidate.extensions)) }
            .sortedBy { it.component.packageName.startsWith("com.retroarch") }
            .firstOrNull()
        if (profile != null) {
            runCatching {
                EmulatorLaunchManager(this).launch(game, GameLaunchDisplay.display(this,spec,game,screenOverride), profile)
                if(savedProfile==null)emulatorRepository.select(spec,profile)
            }
                .onFailure { AlertDialog.Builder(this).setTitle("Unable to launch ${profile.name}").setMessage(it.message).setPositiveButton("OK", null).show() }
            return
        }
        if (emulatorRepository.forSystem(spec).isNotEmpty()) {
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
            runCatching { EmulatorLaunchManager(this).launch(game, GameLaunchDisplay.display(this,spec,game,screenOverride), ComponentName(info.packageName, info.name)) }
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
        val spec=if(library)system else focusedBrowseSystem ?: return null
        if (spec == retroAchievementsSystem) return null
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
        showSettingsWindow(host, bottomOnly = false)
    }
    internal fun showBottomScreenSettings(host: MainActivity) {
        if (!ready || showingCompanion || startMenu?.isShowing == true || OrbitThemeArtwork.screen(host) != "bottom") return
        showSettingsWindow(host, bottomOnly = true)
    }
    private fun showSettingsWindow(host: Activity, bottomOnly: Boolean) {
        startMenu?.dismiss()
        val menu=EmulatorSettings.MenuWindow(host,fullScreen=true)
        startMenu=menu.dialog
        if (host !== this) {
            remoteSettingsDialog=menu.dialog
            remoteSettingsHost=host
        }
        menu.dialog.setOnDismissListener { settingsWindowClosed(menu.dialog) }
        renderSettingsPage(menu,menu.dialog::dismiss,bottomOnly)
        updateSystemsMusic()
    }
    private fun settingsWindowClosed(dialog:AlertDialog) {
        if(startMenu===dialog)startMenu=null
        if(remoteSettingsDialog===dialog) {
            remoteSettingsDialog=null
            remoteSettingsHost=null
            DualScreenCoordinator.restoreHostFocus()
        }
        updateSystemsMusic()
        if (activeTab == 0) {
            val focus = if (library) activeGameWheel() ?: gameList ?: activeSplitGrid() else tabFocus[0]
            focus?.requestFocus()
        }
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
        pendingGameListSwitch = null
        activeGameWheel()?.endMotion()
        activeSplitTileMenu()?.close()
        activeSplitGrid()?.cancelMove()
        val tab=destination.tab
        artworkPanel?.pause();importedScene?.pause()
        currentFocus?.let { tabFocus[if(activeTab==0 && library)6 else activeTab] = it }
        if (tab != 0) discardPreparedArtwork()
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
            if (slot == 0) activeSplitGrid()?.let { grid ->
                val index = browsingSystems.indexOf(focusedBrowseSystem)
                if (index >= 0 && index != grid.selectedLibraryIndex) grid.selectLibrary(index)
                else preloadSplitGames(grid)
            }
            if(tab==3)themeGallery?.initialFocus?.requestFocus()
            else if (tabFocus[slot]?.requestFocus() != true) cached.requestFocus()
            if(slot==6) {
                artworkPanel?.show(system,games.getOrNull(gameCursor))
                importedScene?.showGame(games.getOrNull(gameCursor))
            }
        } else {
            val cursor=gameCursor
            render()
            if(slot==6)(activeGameWheel() ?: gameList ?: activeSplitGrid())?.post { moveGameCursor(cursor) }
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
        discardPreparedArtwork()
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
    internal fun refreshGameArtwork() = refreshTransferredMedia()

    private fun onGameScraped() {
        if (!isDestroyed && !isFinishing && !showingCompanion) refreshTransferredMedia()
        else {
            GameArtworkPanel.clearMediaCache()
            EsDeScene.clearMediaCache()
            DualScreenCoordinator.refreshMedia(true)
        }
    }
    private fun refreshTransferredMedia() {
        discardPreparedArtwork()
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
    private fun renderSettingsPage(popup:EmulatorSettings.MenuWindow?=null,back:()->Unit={},bottomOnly:Boolean=false) {
        val scrapingPage=popup==null && activeTab==4
        val topTabs = !scrapingPage
        val originalWideOptions = (popup?.activity ?: this).resources.configuration.screenWidthDp >= 680
        val pageTab=activeTab
        var pageCategory = when {
            bottomOnly -> 0
            scrapingPage -> scrapingCategory
            else -> settingsCategory
        }
        val mediaScreen = if (bottomOnly) "bottom" else "top"
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
        val scraperCategories = listOf("Account", "Scraping", "Scrape Mode", "Systems", "Region", "Languages", "Media types")
        val names = when {
            bottomOnly -> listOf("General", "Media", "Customisation")
            scrapingPage -> scraperCategories
            else -> settingsCategories
        }
        val categoryButtons = mutableListOf<Button>()
        val optionRows = mutableListOf<View>()
        fun firstOption() = optionRows.firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }
        fun scrollDetailToTop() {
            detailScroll.scrollTo(0, 0)
            // ScrollView captures its current offset when starting a fling.
            detailScroll.fling(0)
        }
        fun revealOption(row: View) {
            if (row === firstOption()) scrollDetailToTop()
            else row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), true)
        }
        var updatingCategory=false
        data class Choice(val title: String, val description: String = "", val value: String = "",
            val section: String? = null, val choose: () -> Unit)
        data class Subpage(val title: String, val back: () -> Unit, val choices: List<Choice>? = null,
            val description: String = "", val screenMediaTypes: Boolean = false)
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
        fun showCategory(index: Int, enter: Boolean = false, restoreOption: Int? = null,
            romFoldersOption: Int? = null, subpage: Subpage? = null) {
            updatingCategory=true
            val sameCategory = pageCategory == index && optionRows.isNotEmpty() &&
                romFoldersOption == null && subpage == null && returnFromSubpage == null
            returnFromSubpage = subpage?.back
            clearEmbeddedPage()
            if (!scrapingPage) romLibraryScan?.onUpdate = null
            val focusedOption = optionRows.indexOfFirst { it.hasFocus() }
            val previousScroll = if (sameCategory) detailScroll.scrollY else 0
            // Keep focus from falling back to the category buttons while replacing option rows.
            detail.isFocusableInTouchMode = true
            if (focusedOption >= 0) detail.requestFocus()
            val previousCategory = pageCategory
            pageCategory = index
            if (scrapingPage) scrapingCategory=index
            else if (!bottomOnly) settingsCategory=index
            if(!warmingTabs && activeTab==2)DualScreenCoordinator.refreshMedia()
            if(popup==null) { if(!scrapingPage)settingsCategoryView = categoryButtons.getOrNull(index) else scrapingSidebar=categoryButtons.getOrNull(index) }
            if (previousCategory != index || optionRows.isEmpty()) {
                categoryButtons.forEachIndexed { i, row ->
                    row.background = surface(Color.TRANSPARENT, if (i == index) blue else Color.rgb(38,49,65))
                }
            }
            detail.removeAllViews()
            scrollDetailToTop()
            detailScroll.visibility=View.VISIBLE
            optionRows.clear()
            val heading = when {
                subpage != null -> subpage.title
                romFoldersOption != null -> "ROM folders & scanner"
                else -> names[index]
            }
            if (!topTabs || subpage != null || romFoldersOption != null) {
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
            fun option(title: String, description: String, value: String = "", compact: Boolean = false,
                action: () -> Unit): LinearLayout {
                val row = object : LinearLayout(this) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        if (topTabs && childCount == 2) {
                            val wide = if (usesThorLayout) originalWideOptions
                                else MeasureSpec.getSize(widthMeasureSpec) >= dp(620)
                            orientation = if (wide) HORIZONTAL else VERTICAL
                            for (childIndex in 0 until childCount) {
                                (getChildAt(childIndex).layoutParams as LayoutParams).apply {
                                    width = if (wide) 0 else -1
                                    weight = if (wide) 1f else 0f
                                    marginEnd = if (wide && childIndex == 0) dp(12) else 0
                                }
                            }
                        }
                        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    }
                }.apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                    defaultFocusHighlightEnabled = false
                    if (topTabs) {
                        minimumHeight = dp(if (compact) 44 else 48)
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                    } else setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = surface(Color.TRANSPARENT, Color.rgb(38,49,65))
                    val titleView = text(if (value.isBlank()) title else "$title  ·  $value", 16f)
                    val descriptionView = description.takeIf { it.isNotBlank() }?.let { text(it, 12f, muted) }
                    if (topTabs) {
                        titleView.setPadding(0, 0, 0, 0)
                        descriptionView?.setPadding(0, 0, 0, 0)
                    }
                    addView(titleView, LinearLayout.LayoutParams(-1, -2))
                    descriptionView?.let { addView(it, LinearLayout.LayoutParams(-1, -2)) }
                    setOnFocusChangeListener { v, focus -> v.background = surface(Color.TRANSPARENT, if (focus) blue else Color.rgb(38,49,65)) }
                    setOnClickListener { action() }
                }
                detail.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (compact) 2 else if (topTabs) 4 else 10) })
                optionRows.add(row)
                if (first == null) first = row
                return row
            }
            fun screenMediaOptions(options: List<GameMediaSettingsPanel.Option>, compact: Boolean = false) {
                options.forEach { mediaOption ->
                    lateinit var row: LinearLayout
                    fun label() = "${mediaOption.title}  ·  ${mediaOption.value()}"
                    fun updateDescription() {
                        if (compact) row.contentDescription = "${label()}. ${mediaOption.description}"
                    }
                    val description = if (compact) "" else mediaOption.description
                    row = option(mediaOption.title, description, mediaOption.value(), compact = compact) {
                        mediaOption.choose {
                            (row.getChildAt(0) as TextView).text = label()
                            updateDescription()
                        }
                    }
                    updateDescription()
                }
            }
            fun showChoices(title: String, parent: () -> Unit, choices: List<Choice>, description: String = "",
                focused: Int? = null) {
                showCategory(index, enter = true, restoreOption = focused, subpage = Subpage(title, parent, choices, description))
            }
            fun returnToParent(row: Int, parentPage: Subpage? = null): () -> Unit =
                { showCategory(index, enter = true, restoreOption = row, subpage = parentPage) }
            fun isCurrent(parent: () -> Unit): Boolean =
                activeTab == pageTab && root.isShown && returnFromSubpage === parent
            fun showSystemLogoOptions(spec: SystemSpec, parent: () -> Unit, focused: Int = 0) {
                val theme = OrbitThemes.current()
                val configured = spec.id in theme.systemLogos
                val choices = buildList {
                    add(Choice("Search artwork", "Find a logo for ${spec.title}.") {
                        val backToLogo: () -> Unit = { showSystemLogoOptions(spec, parent) }
                        ArtworkSearchPicker(this@MainActivity, "marquees", spec.title, emptySet()) { bytes ->
                            ThemeMediaSettings.applyDownloaded(this@MainActivity, theme, "systemLogo", emptySet(), spec, bytes) {
                                if (isCurrent(backToLogo) || isCurrent(parent)) backToLogo()
                            }
                        }.show(
                            embed = { content, focus, keys, back -> embedPage(content, back, focus, keys, "System logo · Search artwork") },
                            closePage = backToLogo
                        )
                    })
                    add(Choice("Choose from device", "Select a PNG, JPEG or WebP logo.") {
                        pickThemeImage { uri ->
                            if (uri != null) ThemeMediaSettings.setSystemLogo(this@MainActivity, theme, spec, uri) {
                                if (isCurrent(parent)) showSystemLogoOptions(spec, parent, 1)
                            }
                        }
                    })
                    if (configured) add(Choice("Remove system logo", "Go back to the default system artwork or name.") {
                        ThemeMediaSettings.setSystemLogo(this@MainActivity, theme, spec, null) {
                            if (isCurrent(parent)) showSystemLogoOptions(spec, parent, 1)
                        }
                    })
                }
                showChoices("System logo · ${spec.title}", parent, choices, if (configured) "Custom logo" else "Default", focused)
            }
            fun showArtworkImages(parent: () -> Unit, focused: Int? = null) {
                val theme = OrbitThemes.current()
                val choices = mutableListOf<Choice>()
                val screens = setOf(OrbitThemeArtwork.artworkScreen(this, mediaScreen, theme))
                for ((role, title) in listOf("background" to "Background image", "hero" to "Hero image",
                    "systemsBackground" to "Systems page background", "gamesBackground" to "Game page background")) {
                    val pageImage = role == "systemsBackground" || role == "gamesBackground"
                    if (pageImage && theme.libraryScreen != mediaScreen) continue
                    val row = choices.size
                    fun update(focus: Int): () -> Unit = {
                        if (isCurrent(parent)) showArtworkImages(parent, focus)
                    }
                    choices += Choice("Search artwork", "Find and download an image sized for this screen.", section = title) {
                        val backToImages = { showArtworkImages(parent, row) }
                        ArtworkSearchPicker(this, if (pageImage) "background" else role, "", screens) { bytes ->
                            ThemeMediaSettings.applyDownloaded(this, theme, role, screens, null, bytes, update(row))
                        }.show(
                            embed = { content, focus, keys, back -> embedPage(content, back, focus, keys, "$title · Search artwork") },
                            closePage = backToImages
                        )
                    }
                    choices += Choice("Choose from device", "Select a PNG, JPEG or WebP image for this screen.") {
                        pickThemeImage { uri ->
                            if (uri != null) ThemeMediaSettings.setImage(this, theme, role, screens, uri, update(row + 1))
                        }
                    }
                    if (theme.artwork.any { it.role == role && it.screen in screens }) {
                        choices += Choice("Remove image", "Clear this image from this screen.") {
                            ThemeMediaSettings.setImage(this, theme, role, screens, null, update(row + 1))
                        }
                    }
                }
                val logosRow = choices.size
                choices += Choice("System logo", "Choose a logo for a system in your library.", section = "System artwork") {
                    val backToImages: () -> Unit = { showArtworkImages(parent, logosRow) }
                    fun showSystems(focus: Int? = null) {
                        val current = OrbitThemes.current()
                        val rows = populated.mapIndexed { row, spec ->
                            Choice(spec.title, value = if (spec.id in current.systemLogos) "Custom" else "") {
                                showSystemLogoOptions(spec, { showSystems(row) })
                            }
                        }
                        showChoices("System logos", backToImages, rows, if (rows.isEmpty()) "No systems in your library yet." else "", focus)
                    }
                    showSystems()
                }
                showChoices("Artwork images", parent, choices, focused = focused)
            }
            fun showAnimationSettings(view: TransitionSettings.View, parent: () -> Unit, focused: Int? = null) {
                val choices = animationOptions(view).mapIndexed { row, option ->
                    Choice(option.title, option.description, option.value()) {
                        option.change()
                        if (isCurrent(parent)) showAnimationSettings(view, parent, row)
                    }
                }
                val title = if (view == TransitionSettings.View.SYSTEMS) "Systems animations" else "Game list animations"
                showChoices(title, parent, choices, focused = focused)
            }
            val section = subpage?.title ?: names[index]
            fun redrawSection() {
                val focused = optionRows.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 && subpage != null }
                showCategory(index, enter = true, subpage = subpage, restoreOption = focused)
            }
            fun artworkLinks() {
                val imagesRow = optionRows.size
                option("Artwork images", "Set backgrounds, hero images and system logos.") {
                    showArtworkImages(returnToParent(imagesRow))
                }
                val theme = OrbitThemes.current()
                val screen = OrbitThemeArtwork.artworkScreen(this, mediaScreen, theme)
                val visible = screen in theme.systemArtworkScreens
                option("Show system artwork", "Show the foreground console image for this screen.", if (visible) "On" else "Off") {
                    ThemeMediaSettings.setSystemArtworkVisible(this, theme, screen, !visible, ::redrawSection)
                }
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

            fun showCollections() {
                fun showCollectionsHome() = showCategory(index, enter = true, restoreOption = 0, subpage = subpage)
                fun showList(id: String, focused: Int? = null) {
                    val collection = CustomCollections.list(this).firstOrNull { it.id == id } ?: return
                    val titles = systems.associateBy { it.id }
                    val gameIndex = collection.games.map { it.systemId }.distinct().associateWith { systemId ->
                        pages[systemId]?.games.orEmpty().associateBy { it.uri.toString() }
                    }
                    val choices = buildList {
                        add(Choice("Rename collection") {
                            editFields("Rename collection", listOf(InputField("Collection name", collection.name))) { values ->
                                CustomCollections.rename(this@MainActivity, id, values.single())
                                showList(id, 0)
                            }
                        })
                        add(Choice("Delete collection") {
                            showChoices("Delete collection?", { showList(id, 1) }, listOf(
                                Choice("Keep collection") { showList(id, 1) },
                                Choice("Delete ${collection.name}") {
                                    runCatching { CustomCollections.delete(this@MainActivity, id) }
                                        .onSuccess { showCollectionsHome() }.onFailure(::showDataError)
                                }
                            ), "Your ROMs and artwork stay in the library.")
                        })
                        collection.games.forEachIndexed { position, entry ->
                            val spec = titles[entry.systemId]
                            val game = gameIndex[entry.systemId]?.get(entry.uri)
                            add(Choice(game?.title ?: entry.title, spec?.title ?: entry.systemId,
                                if (game == null) "Unavailable" else "", if (position == 0) "Games" else null) {
                                val actions = buildList {
                                    if (spec != null && game != null) add(Choice("Launch game") {
                                        chooseEmulator(game, spec = spec)
                                    })
                                    add(Choice("Remove from collection") {
                                        runCatching { CustomCollections.setMember(this@MainActivity, id, entry, false) }
                                            .onSuccess { showList(id, position + 1) }.onFailure(::showDataError)
                                    })
                                }
                                showChoices(game?.title ?: entry.title, { showList(id, position + 2) }, actions,
                                    if (game == null) "Scan the ROM folder to make this game available again." else spec?.title.orEmpty())
                            })
                        }
                    }
                    showChoices(collection.name, { showCollectionsHome() }, choices,
                        if (collection.games.isEmpty()) "Add games from Game Settings → Customisation → Collections." else "", focused)
                }

                option("New collection", "Create a named list of games from any system.") {
                    editFields("New collection", listOf(InputField("Collection name"))) { values ->
                        CustomCollections.create(this, values.single())
                        showCollectionsHome()
                    }
                }
                val collections = CustomCollections.list(this)
                if (collections.isEmpty()) detail.addView(text("Add games from Game Settings → Customisation → Collections.", 14f, muted))
                collections.forEach { collection ->
                    option(collection.name, "", "${collection.games.size} games") { showList(collection.id) }
                }
            }

            fun showRetroAchievements() {
                val account = RetroAchievements.account(this)
                val connectionRow = optionRows.size
                option("Account", "Enter your RetroAchievements username and Web API key.", account?.username.orEmpty()) {
                    editFields("RetroAchievements account", listOf(
                        InputField("Username", account?.username.orEmpty()),
                        InputField("Web API key", account?.key.orEmpty(), secret = true)
                    )) { values ->
                        val (username, key) = values
                        require(username.isNotBlank() && key.isNotBlank()) { "Enter your username and Web API key." }
                        RetroAchievements.save(this, RetroAchievements.Account(username, key))
                        showCategory(index, enter = true, restoreOption = connectionRow)
                    }
                }
                option("Get a Web API key", "Open the RetroAchievements control panel.") {
                    openRetroAchievements(RetroAchievements.ACCOUNT_URL)
                }
                if (account == null) return

                fun <T> load(title: String, back: () -> Unit, retry: () -> Unit, fetch: () -> T, display: (T) -> Unit) {
                    showChoices(title, back, emptyList(), "Loading…")
                    retroAchievementsWorker.execute {
                        val result = runCatching(fetch)
                        runOnUiThread {
                            if (!isDestroyed && isCurrent(back)) result.fold(display) {
                                val message = (it as? RetroAchievements.RequestError)?.message
                                    ?: "Couldn’t load RetroAchievements. Check your connection and try again."
                                showChoices(title, back, listOf(Choice("Retry", choose = retry)), message)
                            }
                        }
                    }
                }
                fun profile(back: () -> Unit) {
                    load("Profile", back, { profile(back) }, { RetroAchievements.profile(account) }) { profile ->
                        showChoices(profile.name, back, listOf(
                            Choice("Refresh") { profile(back) }
                        ), "${profile.points} hardcore points · ${profile.softcorePoints} softcore points")
                    }
                }
                fun recent(back: () -> Unit) {
                    load("Recent unlocks", back, { recent(back) }, { RetroAchievements.recent(account) }) { unlocks ->
                        val rows = unlocks.map { unlock ->
                            Choice(unlock.title, "${unlock.game} · ${unlock.points} points",
                                if (unlock.hardcore) "Hardcore" else "Softcore") {
                                openRetroAchievements("https://retroachievements.org/achievement/${unlock.id}")
                            }
                        }
                        showChoices("Recent unlocks", back, rows + Choice("Refresh") { recent(back) },
                            if (rows.isEmpty()) "No achievements unlocked in the last 24 hours." else "Unlocks in the last 24 hours.")
                    }
                }
                fun progress(back: () -> Unit, offset: Int = 0) {
                    load("Game progress", back, { progress(back, offset) },
                        { RetroAchievements.progress(account, offset) }) { page ->
                        val rows = buildList {
                            page.games.forEach { game ->
                                add(Choice(game.title, "${game.system} · ${game.earned}/${game.total} softcore · ${game.hardcore}/${game.total} hardcore") {
                                    openRetroAchievements("https://retroachievements.org/game/${game.id}")
                                })
                            }
                            if (offset > 0) add(Choice("Previous games") {
                                progress(back, offset - RetroAchievements.PAGE_SIZE)
                            })
                            if (page.games.isNotEmpty() && offset + page.games.size < page.total) {
                                add(Choice("More games") { progress(back, offset + RetroAchievements.PAGE_SIZE) })
                            }
                            add(Choice("Refresh") { progress(back, offset) })
                        }
                        val summary = if (page.games.isEmpty()) "No game progress to display."
                            else "${offset + 1}–${offset + page.games.size} of ${page.total} games"
                        showChoices("Game progress", back, rows, summary)
                    }
                }
                fun row(title: String, description: String, open: (() -> Unit) -> Unit) {
                    val position = optionRows.size
                    option(title, description) { open(returnToParent(position)) }
                }
                row("Profile", "View your points.", ::profile)
                row("Recent unlocks", "View achievements earned in the last 24 hours.", ::recent)
                row("Game progress", "Browse achievement progress by game.") { progress(it) }
                option("Disconnect", "Remove the saved RetroAchievements account from Orbit.") {
                    RetroAchievements.save(this, null)
                    showCategory(index, enter = true, restoreOption = connectionRow)
                }
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
                    option(choice.title, choice.description, choice.value, action = choice.choose)
                }
            } else if (subpage?.screenMediaTypes == true) {
                screenMediaOptions(GameMediaSettingsPanel.options(this, mediaScreen, ::refreshGameMediaOptions).drop(1))
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
            } else when (section) {
                "Collections" -> showCollections()
                "RetroAchievements" -> showRetroAchievements()
                "General" -> if (bottomOnly) {
                    val dockEnabled = AppDock.enabled(this)
                    option("App dock", "Show the app dock on the bottom screen.", if (dockEnabled) "On" else "Off") {
                        runCatching { UserDataStore.put(this, "settings", "appDockEnabled", if (dockEnabled) 0 else 1) }
                            .onSuccess { DualScreenCoordinator.refreshBottomControls(); redrawSection() }
                            .onFailure(::showDataError)
                    }
                    val dockSlots = AppDock.slots(this)
                    option("App dock slots", "Press A to choose 1–5 slots.", dockSlots.toString()) {
                        runCatching { UserDataStore.put(this, "settings", "appDockSlots", dockSlots % 5 + 1) }
                            .onSuccess { DualScreenCoordinator.refreshBottomControls(); redrawSection() }
                            .onFailure(::showDataError)
                    }
                } else {
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
                    val collectionsRow = optionRows.size
                    option("Collections", "Browse and manage your custom game collections.") {
                        showCategory(index, enter = true, subpage = Subpage("Collections", returnToParent(collectionsRow)))
                    }
                    val displayRow = optionRows.size
                    option("Display", "See screen sizes, set the refresh rate and choose where settings open.") {
                        showCategory(index, enter = true, subpage = Subpage("Display", returnToParent(displayRow)))
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
                        }.onSuccess { redrawSection() }.onFailure { showDataError(it) }
                    }
                    val shortcutScreen = UserDataStore.cachedString("settings", "settingsShortcutScreen")
                    val shortcutLabel = shortcutScreen?.let(GameLaunchDisplay::label) ?: "Follow tile"
                    option("Settings shortcut display", "Choose where Start and Select open settings.", shortcutLabel) {
                        val next = when (shortcutScreen) {
                            null -> "top"
                            "top" -> "bottom"
                            else -> null
                        }
                        runCatching { UserDataStore.put(this, "settings", "settingsShortcutScreen", next) }
                            .onSuccess { redrawSection() }.onFailure(::showDataError)
                    }
                    val sizesRow = optionRows.size
                    option("Display sizes", "See the detected resolution of each screen.") {
                        showCategory(index, enter = true,
                            subpage = Subpage("Display sizes", returnToParent(sizesRow, subpage)))
                    }
                }
                "Display sizes" -> {
                    val displays = DualScreenManager(this).displays().sortedBy { it.displayId }
                    val bottomId = displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.displayId
                    for (display in displays) {
                        val title = when {
                            displays.size == 1 -> "Screen"
                            display.displayId == Display.DEFAULT_DISPLAY -> "Top screen"
                            display.displayId == bottomId -> "Bottom screen"
                            else -> display.name
                        }
                        val mode = display.mode
                        detail.addView(text("$title  ·  ${mode.physicalWidth} × ${mode.physicalHeight} px", 16f))
                    }
                    if (displays.isEmpty()) detail.addView(text("No displays detected.", 16f))
                }
                "Customisation" -> if (bottomOnly) {
                    artworkLinks()
                } else {
                    val theme = OrbitThemes.current()
                    val themesRow = optionRows.size
                    option("Themes", "Open the theme gallery to preview, apply or create a theme.", theme.name) {
                        val parent = returnToParent(themesRow)
                        val gallery = ThemeGalleryPanel(this, columns = 2, exit = parent) { themeApplied(keepSettings = true) }
                        embedPage(gallery.view, { if (!gallery.back()) parent() }, gallery.initialFocus,
                            gallery::handleNavigation, showBack = false)
                    }
                    val musicOn = UserDataStore.int(this, "systemsMusic", 1) != 0
                    option("Background music", "Play music while browsing Systems.", if (musicOn) "On" else "Off") {
                        runCatching { UserDataStore.put(this, "settings", "systemsMusic", if (musicOn) 0 else 1) }
                            .onSuccess { updateSystemsMusic(); showCategory(index, true) }.onFailure { showDataError(it) }
                    }
                    artworkLinks()
                    for ((view, label) in listOf(TransitionSettings.View.SYSTEMS to "Systems animations",
                        TransitionSettings.View.GAMES to "Game list animations")) {
                        val animationRow = optionRows.size
                        option(label, "Choose the transition and effects for this view.", pageTransitionLabels[pageTransition(view)]) {
                            showAnimationSettings(view, returnToParent(animationRow))
                        }
                    }
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
                    val panel = scraperPanel()
                    val mainScrapingTab = !scrapingPage && subpage == null
                    val startRow = optionRows.size
                    panel.attach(detail) {
                        if (mainScrapingTab) {
                            showCategory(index, enter = true, subpage = Subpage("Systems", returnToParent(startRow)))
                        } else if (subpage == null) openScrapingPage(3)
                        else showCategory(index, enter = true, subpage = Subpage("Systems", subpage.back))
                    }
                    optionRows.addAll(panel.navigationRows)
                    first = panel.navigationRows.firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }
                    if (mainScrapingTab) {
                        detail.addView(text("Scraper settings", 21f).apply { typeface = Typeface.DEFAULT_BOLD })
                        listOf(
                            "Account" to "Choose a scraper and sign in.",
                            "Scrape Mode" to "Choose what to replace and how many games to scrape at once.",
                            "Systems" to "Choose which systems to scrape.",
                            "Region" to "Choose the preferred artwork region.",
                            "Languages" to "Choose the preferred language.",
                            "Media types" to "Choose which artwork and media to download."
                        ).forEach { (label, description) ->
                            val row = optionRows.size
                            option(label, description) {
                                showCategory(index, enter = true, subpage = Subpage(label, returnToParent(row)))
                            }
                        }
                        val mediaRow = optionRows.size
                        option("Media", "Manage top-screen media, artwork packs and media transfers.") {
                            showCategory(index, enter = true, subpage = Subpage("Media", returnToParent(mediaRow)))
                        }
                    }
                }
                "Media" -> if (bottomOnly) {
                    screenMediaOptions(GameMediaSettingsPanel.options(this, mediaScreen, ::refreshGameMediaOptions), compact = true)
                } else {
                    screenMediaOptions(GameMediaSettingsPanel.options(this, mediaScreen, ::refreshGameMediaOptions).take(1))
                    val mediaTypesRow = optionRows.size
                    option("Media types", "Choose which media to show on the top screen.") {
                        showCategory(index, enter = true,
                            subpage = Subpage("Media types", returnToParent(mediaTypesRow, subpage), screenMediaTypes = true))
                    }
                    detail.addView(text("Media library", 21f).apply { typeface = Typeface.DEFAULT_BOLD })
                    val packsRow = optionRows.size
                    option("NeoAssets artwork packs","Browse downloadable system artwork packs.") {
                        val parent = returnToParent(packsRow, subpage)
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
                        mediaTransferBack = returnToParent(transferRow, subpage)
                        val panel = mediaTransferPanel ?: MediaTransferPanel(
                            this, { populated }, { mediaTransferBack?.invoke() }, ::refreshTransferredMedia
                        ) { picker -> startActivityForResult(picker, 151, popupLaunchOptions()) }.also { mediaTransferPanel = it }
                        embedPage(panel.view, panel::back, panel.initialFocus)
                    }
                }
                "Media types" -> {
                    val preferences = getSharedPreferences("scraper_credentials", 0)
                    val categories = ScraperMediaTypes.categories
                    val defaults = categories.map { it.folder }.toSet()
                    fun enabledMedia() = preferences.getStringSet("media.categories", defaults).orEmpty()
                    categories.forEach { category ->
                        fun value() = if (category.folder in enabledMedia()) "On" else "Off"
                        lateinit var row: LinearLayout
                        row = option(category.title, "Include when scraping, where available.", value()) {
                            val next = enabledMedia().toMutableSet()
                            if (!next.add(category.folder)) next.remove(category.folder)
                            preferences.edit().putStringSet("media.categories", next).apply()
                            (row.getChildAt(0) as TextView).text = "${category.title}  ·  ${value()}"
                        }
                    }
                }
                "Scrape Mode" -> {
                    val preferences=getSharedPreferences("scraper_credentials",0)
                    val mode=ScrapePolicy.mode(this)
                    val requested=ScrapePolicy.requestedConcurrency(this)
                    val concurrencyRow = optionRows.size
                    option("Games at once","Use ScreenScraper’s account allowance or up to six games with other providers.",if(requested==0)"Account maximum" else requested.toString()) {
                        val parent = { showCategory(index, enter = true, restoreOption = concurrencyRow, subpage = subpage) }
                        val choices = (0..ScrapePolicy.MAX_MANUAL_GAMES).map { count ->
                            Choice(if (count == 0) "Account maximum" else count.toString(),
                                value = if (requested == count) "Selected" else "") {
                                preferences.edit().putInt("scrape.gamesAtOnce", count).apply()
                                parent()
                            }
                        }
                        showChoices("Games at once", parent, choices, focused = requested.coerceIn(0, ScrapePolicy.MAX_MANUAL_GAMES))
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
                        val grid=ResponsiveGridView(this, 140).apply {
                            horizontalSpacing=dp(12);verticalSpacing=dp(12)
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
                                            scaleType=if(usesThorLayout)ImageView.ScaleType.FIT_XY else ImageView.ScaleType.FIT_CENTER
                                            contentDescription="${spec.title} artwork"
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
                val destination = target ?: firstOption()
                if (sameCategory && restoreOption == null) detailScroll.scrollTo(0, previousScroll)
                destination?.requestFocus()
                if (destination != null && (restoreOption != null || destination === firstOption())) {
                    OneShotPreDrawListener.add(detailScroll) {
                        if (detailScroll.isShown && destination.hasFocus() && destination in optionRows) {
                            revealOption(destination)
                        }
                    }
                }
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
        fun focusOption(row: View) {
            row.requestFocus()
            revealOption(row)
        }
        fun focusCategory(index: Int) {
            val row = categoryButtons[index]
            row.requestFocus()
            sidebar?.fling(0)
            row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), true)
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
            } else firstOption()?.let(::focusOption)
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
            } else if(key in listOf(KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BACK) && (returnFromSubpage != null || (!bottomOnly && detailHost.hasFocus()))) {
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
                    val columns = focusedGrid.numColumns.coerceAtLeast(1)
                    if(key==KeyEvent.KEYCODE_DPAD_UP && position<columns) {
                        val previous=optionRows.takeWhile { it!==focusedGrid }.lastOrNull { it.isEnabled && it.visibility==View.VISIBLE }
                        if (previous != null) focusOption(previous)
                        else {
                            revealOption(focusedGrid)
                            returnToCategory()
                        }
                    } else if(key==KeyEvent.KEYCODE_DPAD_DOWN && position / columns == (focusedGrid.count - 1) / columns) {
                        val next = optionRows.dropWhile { it !== focusedGrid }.drop(1)
                            .firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }
                        if (next != null) focusOption(next)
                    } else if(key==KeyEvent.KEYCODE_DPAD_LEFT && position%columns==0) {
                        if (!topTabs) returnToCategory()
                    }
                    else {
                        val next=when(key) {
                            KeyEvent.KEYCODE_DPAD_UP -> position-columns
                            KeyEvent.KEYCODE_DPAD_DOWN -> (position+columns).coerceAtMost(focusedGrid.count-1)
                            KeyEvent.KEYCODE_DPAD_LEFT -> position-1
                            else -> if(position%columns<columns-1 && position+1<focusedGrid.count)position+1 else position
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
                        KeyEvent.KEYCODE_DPAD_RIGHT -> navigableRows.firstOrNull()?.let(::focusOption)
                        else -> {
                            val step = if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                            if (inOptions && navigableRows.isNotEmpty()) {
                                val current = navigableRows.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)
                                if (topTabs && step < 0 && current == 0) {
                                    revealOption(navigableRows.first())
                                    returnToCategory()
                                } else focusOption(navigableRows[(current + step).coerceIn(navigableRows.indices)])
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
        val initial = categoryButtons[pageCategory]
        if(popup!=null) {
            val title = if (bottomOnly) "Dual Screen Settings" else "All settings"
            popup.clearTabs(title)
            popup.page(title,root,back=back,handle={ _,event -> handleSettings(event) })
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
        activeGameWheel()?.adapter?.notifyDataSetChanged()
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
    private var trackingBottomSettingsSwipe = false
    private var bottomEdgeX = 0f
    private var bottomEdgeY = 0f
    private var bottomEdgeSwipeCaptured = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            bottomEdgeSwipeCaptured = false
            trackingBottomSettingsSwipe = false
            if (event.x < dp(32) && OrbitThemeArtwork.screen(this) == "bottom" && startMenu?.isShowing != true &&
                (ready || showingCompanion) && !scanning && !companionVideoActive &&
                artworkPanel?.manualVideoActive != true &&
                activeSplitTileMenu()?.isOpen != true && activeSplitGrid()?.isMoving != true
            ) {
                trackingBottomSettingsSwipe = true
                bottomEdgeX = event.x
                bottomEdgeY = event.y
            }
        } else if (bottomEdgeSwipeCaptured) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                action == MotionEvent.ACTION_POINTER_DOWN
            ) {
                if (action == MotionEvent.ACTION_UP && trackingBottomSettingsSwipe) {
                    DualScreenCoordinator.openBottomSettings(this)
                }
                trackingBottomSettingsSwipe = false
                if (action != MotionEvent.ACTION_POINTER_DOWN) bottomEdgeSwipeCaptured = false
            }
            return true
        } else if (action == MotionEvent.ACTION_MOVE && trackingBottomSettingsSwipe) {
            val inward = event.x - bottomEdgeX
            val vertical = kotlin.math.abs(event.y - bottomEdgeY)
            val slop = ViewConfiguration.get(this).scaledTouchSlop
            if (inward >= dp(64) && inward >= vertical * 1.5f) {
                bottomEdgeSwipeCaptured = true
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                try {
                    super.dispatchTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
                return true
            }
            if (inward < -slop || (vertical > slop && vertical > inward)) trackingBottomSettingsSwipe = false
        } else if (action != MotionEvent.ACTION_MOVE) {
            trackingBottomSettingsSwipe = false
        }
        return super.dispatchTouchEvent(event)
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
        if (ready && activeTab == 0 && library && startMenu?.isShowing != true && activeSplitTileMenu()?.isOpen != true) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 -> -1
                KeyEvent.KEYCODE_BUTTON_R2 -> 1
                else -> NavigationTabs.direction(event.keyCode)
            }
            if (step != 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled && !scanning) {
                    switchGameListSystem(step)
                }
                return true
            }
        }
        if(ready && activeTab==0 && library && event.keyCode==KeyEvent.KEYCODE_BUTTON_X &&
            startMenu?.isShowing!=true && activeSplitTileMenu()?.isOpen!=true) {
            if(activeSplitTileMenu()?.shortcutSelected==true)return true
            if (OrbitThemes.current().id != OrbitThemes.SPLIT_THEME) return true
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
                val vertical = when (systemsAppearance()) {
                    SystemsAppearance.VERTICAL_GRID, SystemsAppearance.VERTICAL_SCROLL -> true
                    else -> false
                }
                val key = if (vertical) {
                    if (step < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                } else {
                    if (step < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                }
                return dispatchFrontendKeyEvent(KeyEvent(event.downTime,event.eventTime,event.action,key,
                    event.repeatCount,event.metaState,event.deviceId,event.scanCode,event.flags,event.source))
            }
        }
        if(ready && startMenu?.isShowing!=true &&
            activeSplitGrid()?.takeIf { it.isShown }?.handleController(event)==true)return true
        if (ready && activeTab == 0 && library && startMenu?.isShowing != true &&
            activeGameWheel()?.takeIf { it.isShown }?.handle(event) == true) return true
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
    private class AnimationOption(
        val title: String,
        val description: String,
        val value: () -> String,
        val change: () -> Unit
    )

    private fun animationOptions(view: TransitionSettings.View): List<AnimationOption> {
        val systems = view == TransitionSettings.View.SYSTEMS
        fun refreshGrid() {
            val slot = if (systems) 0 else 6
            val tag = if (systems) "orbit-systems-grid" else "orbit-game-list"
            (tabViews[slot]?.findViewWithTag<View>(tag) as? SplitLibraryGrid)?.refreshTiles()
        }

        fun save(key: String, value: Int, refreshSelection: Boolean = false, target: TransitionSettings.View = view) {
            runCatching { TransitionSettings.put(this, key, value, target) }
                .onSuccess { if (refreshSelection) refreshGrid() }
                .onFailure(::showDataError)
        }

        fun toggle(title: String, description: String, key: String, refreshSelection: Boolean = false) =
            AnimationOption(title, description, { if (TransitionSettings.flag(key, view)) "On" else "Off" }) {
                save(key, if (TransitionSettings.flag(key, view)) 0 else 1, refreshSelection)
            }

        fun motionSettingsView(): TransitionSettings.View {
            val effective = libraryTransitionView(opening = !systems)
            return if (isSlideTransition(pageTransition(effective))) effective else view
        }
        return listOf(
            AnimationOption("Transition style", "Slide applies in both directions between Systems and Games.",
                { pageTransitionLabels[pageTransition(view)] }) {
                save(pageTransitionKey, nextPageTransition(pageTransition(view)))
            },
            AnimationOption("Direction", "Change which way the transition moves.",
                {
                    val target = motionSettingsView()
                    val style = pageTransition(target)
                    transitionDirectionLabels(style)?.get(transitionDirection(style, target)) ?: "Not used"
                }) {
                val target = motionSettingsView()
                val style = pageTransition(target)
                transitionDirectionLabels(style)?.let { directions ->
                    save(transitionDirectionKey(style), (transitionDirection(style, target) + 1) % directions.size, target = target)
                }
            },
            AnimationOption("Speed", "Change the transition speed.",
                { transitionSpeedLabels[transitionSpeed(motionSettingsView())] }) {
                val target = motionSettingsView()
                save(transitionSpeedKey, (transitionSpeed(target) + 1) % transitionSpeedLabels.size, target = target)
            },
            AnimationOption("Apply to", "Share animation settings across all themes or set them for each theme.",
                { if (TransitionSettings.perTheme(view)) "Each theme (${OrbitThemes.current().name})" else "All themes" }) {
                runCatching {
                    TransitionSettings.setPerTheme(this, view, !TransitionSettings.perTheme(view))
                }.onSuccess { refreshGrid() }.onFailure(::showDataError)
            },
            toggle("Tile cascade", "Tiles appear one after another when this view opens.", SplitLibraryGrid.TILE_CASCADE_KEY),
            toggle("Gliding selection", "The selection outline slides from tile to tile.", SplitLibraryGrid.GLIDING_SELECTION_KEY,
                refreshSelection = true),
            toggle("Artwork crossfade", "Blend between artwork as you browse this view.", GameMediaSurface.ART_CROSSFADE_KEY)
        ) + if (systems) emptyList() else listOf(artworkAnimationOption(), launchAnimationOption())
    }

    private fun artworkAnimationOption() = AnimationOption(
        "Background animation", "Choose how the game artwork background appears.", { TransitionSettings.ArtworkAnimation.selected().label }
    ) {
        val styles = TransitionSettings.ArtworkAnimation.entries
        val next = styles[(TransitionSettings.ArtworkAnimation.selected().ordinal + 1) % styles.size]
        saveArtworkAnimation(next)
    }

    private fun saveArtworkAnimation(style: TransitionSettings.ArtworkAnimation) {
        runCatching {
            TransitionSettings.put(this, TransitionSettings.ArtworkAnimation.KEY, style.id, TransitionSettings.View.GAMES)
        }.onFailure(::showDataError)
    }

    private fun showGameArtworkAnimations(menu: EmulatorSettings.MenuWindow, back: () -> Unit) {
        val styles = TransitionSettings.ArtworkAnimation.entries
        val labels = styles.map { style ->
            { if (style == TransitionSettings.ArtworkAnimation.selected()) "✓  ${style.label}" else style.label }
        }
        menu.liveActions("Background animation", labels, { saveArtworkAnimation(styles[it]) },
            back, initialSelection = TransitionSettings.ArtworkAnimation.selected().ordinal)
    }

    private fun launchAnimationOption() = AnimationOption(
        "Launch animation", "Choose the transition when a game opens.", { TransitionSettings.LaunchAnimation.selected().label }
    ) {
        val styles = TransitionSettings.LaunchAnimation.entries
        val next = styles[(TransitionSettings.LaunchAnimation.selected().ordinal + 1) % styles.size]
        saveLaunchAnimation(next)
    }

    private fun saveLaunchAnimation(style: TransitionSettings.LaunchAnimation) {
        runCatching {
            TransitionSettings.put(this, TransitionSettings.LaunchAnimation.KEY, style.id, TransitionSettings.View.GAMES)
        }.onFailure(::showDataError)
    }

    private fun showGameAnimations(menu: EmulatorSettings.MenuWindow, back: () -> Unit, focused: Int = 0) {
        val labels = listOf<() -> String>(
            { "Background animation · ${TransitionSettings.ArtworkAnimation.selected().label}" },
            { "Launch animation · ${TransitionSettings.LaunchAnimation.selected().label}" },
            { "System switching · ${systemSwitchAnimation().label}" },
            { "Spin discs · ${if (spinDiscsEnabled()) "On" else "Off"}" }
        )
        menu.liveActions("Animation", labels, { index ->
            val backToAnimations = { showGameAnimations(menu, back, index) }
            if (index == 0) showGameArtworkAnimations(menu, backToAnimations)
            else if (index == 1) {
                val styles = TransitionSettings.LaunchAnimation.entries
                val styleLabels = styles.map { style ->
                    { if (style == TransitionSettings.LaunchAnimation.selected()) "✓  ${style.label}" else style.label }
                }
                menu.liveActions("Launch animation", styleLabels, { saveLaunchAnimation(styles[it]) },
                    backToAnimations, initialSelection = TransitionSettings.LaunchAnimation.selected().ordinal)
            } else if (index == 2) {
                showSystemSwitchAnimations(menu, backToAnimations)
            } else {
                runCatching { UserDataStore.put(this, "settings", discSpinKey, if (spinDiscsEnabled()) 0 else 1) }
                    .onSuccess { refreshGameTiles() }
                    .onFailure(::showDataError)
            }
        }, back, initialSelection = focused)
    }

    private fun showSystemSwitchAnimations(menu: EmulatorSettings.MenuWindow, back: () -> Unit) {
        val labels = listOf<() -> String>(
            { "Animation · ${systemSwitchAnimation().label}" },
            {
                val direction = if (systemSwitchAnimation() == SystemSwitchAnimation.SLIDE)
                    systemSwitchDirectionLabels[systemSwitchDirection()] else "Not used"
                "Direction · $direction"
            },
            { "Speed · ${transitionSpeedLabels[systemSwitchSpeed()]}" }
        )
        menu.liveActions("System switching", labels, { index ->
            runCatching {
                when (index) {
                    0 -> {
                        val next = (systemSwitchAnimation().ordinal + 1) % SystemSwitchAnimation.entries.size
                        TransitionSettings.put(this, systemSwitchAnimationKey, next, TransitionSettings.View.GAMES)
                    }
                    1 -> if (systemSwitchAnimation() == SystemSwitchAnimation.SLIDE) {
                        val next = (systemSwitchDirection() + 1) % systemSwitchDirectionLabels.size
                        TransitionSettings.put(this, systemSwitchDirectionKey, next, TransitionSettings.View.GAMES)
                    }
                    2 -> {
                        val next = (systemSwitchSpeed() + 1) % transitionSpeedLabels.size
                        TransitionSettings.put(this, systemSwitchSpeedKey, next, TransitionSettings.View.GAMES)
                    }
                }
                Unit
            }.onFailure(::showDataError)
        }, back)
    }

    private fun showSystemViews(menu: EmulatorSettings.MenuWindow, back: () -> Unit) {
        val appearances = SystemsAppearance.entries
        val labels = appearances.map { appearance ->
            { if (appearance == systemsAppearance()) "✓  ${appearance.label}" else appearance.label }
        }
        menu.liveActions("System views", labels, { position ->
            val appearance = appearances[position]
            if (appearance != systemsAppearance()) {
                runCatching { UserDataStore.put(this, "settings", systemsAppearanceKey, appearance.savedName ?: appearance.name) }
                    .onSuccess { if (activeTab == 0 && !library) render() }
                    .onFailure(::showDataError)
            }
        }, back, initialSelection = systemsAppearance().ordinal)
    }

    private fun showGameViews(menu: EmulatorSettings.MenuWindow, back: () -> Unit, focused: Int = gameAppearance().ordinal) {
        val appearances = GameAppearance.entries
        val labels = appearances.map { appearance ->
            { if (appearance == gameAppearance()) "✓  ${appearance.label}" else appearance.label }
        } + listOf<() -> String>(
            { "Game media · ${gameWheelMedia().label}" },
            { "Artwork sizes" }
        )
        menu.liveActions("Game Views", labels, { position ->
            val appearance = appearances.getOrNull(position)
            if (position == appearances.size) {
                showGameWheelMedia(menu) { showGameViews(menu, back, position) }
            } else if (appearance == null) {
                showGameWheelSizes(menu, { showGameViews(menu, back, position) })
            } else if (appearance != gameAppearance()) {
                runCatching { UserDataStore.put(this, "settings", "gameAppearance", appearance.name) }
                    .onSuccess {
                        discardPreparedSplitGameView()
                        if (activeTab == 0 && library) {
                            val selectedGame = gameCursor
                            render()
                            moveGameCursor(selectedGame)
                        }
                    }
                    .onFailure(::showDataError)
            }
        }, back, initialSelection = focused)
    }

    private fun showGameWheelSizes(menu: EmulatorSettings.MenuWindow, back: () -> Unit, focused: Int = gameWheelMedia().ordinal) {
        val mediaTypes = GameWheelMedia.entries
        val labels = mediaTypes.map { media -> { "${media.label} · ${gameWheelSize(media).label}" } }
        menu.liveActions("Artwork sizes", labels, { position ->
            val media = mediaTypes[position]
            val sizes = GameWheelSize.entries
            val next = sizes[(gameWheelSize(media).ordinal + 1) % sizes.size]
            runCatching { UserDataStore.put(this, "settings", media.sizeKey, next.name) }
                .onSuccess {
                    if (media == gameWheelMedia() && activeTab == 0 && library && activeGameWheel() != null) render()
                }
                .onFailure(::showDataError)
        }, back, initialSelection = focused)
    }

    private fun showGameWheelMedia(menu: EmulatorSettings.MenuWindow, back: () -> Unit) {
        val mediaOptions = GameWheelMedia.entries
        val labels = mediaOptions.map { media ->
            { if (media == gameWheelMedia()) "✓  ${media.label}" else media.label }
        }
        menu.liveActions("Game media", labels, { position ->
            val media = mediaOptions[position]
            if (media != gameWheelMedia()) {
                runCatching { UserDataStore.put(this, "settings", "gameWheelMedia", media.name) }
                    .onSuccess { if (activeTab == 0 && library && activeGameWheel() != null) render() }
                    .onFailure(::showDataError)
            }
        }, back, initialSelection = gameWheelMedia().ordinal)
    }

    private data class InputField(val label: String, val value: String = "", val secret: Boolean = false)

    private fun editFields(title: String, fields: List<InputField>, save: (List<String>) -> Unit) {
        val host = popupContext()
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            isFocusableInTouchMode = true
        }
        val editors = fields.map { field ->
            body.addView(TextView(host).apply { text = field.label })
            EditText(host).apply {
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or if (field.secret)
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                if (field.secret) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setText(field.value)
                contentDescription = field.label
                body.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val dialog = AlertDialog.Builder(host).setTitle(title).setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.styleArtworkWindow(host)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        trackPopup(dialog)
        body.requestFocus()
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        SelectionStyle.apply(confirm)
        SelectionStyle.apply(cancel)
        FrontendNavigationBar.apply(host, dialog.window)
        confirm.setOnClickListener {
            runCatching { save(editors.map { it.text.toString().trim() }) }
                .onSuccess { dialog.dismiss() }
                .onFailure { editors.first().error = it.message ?: "Could not save. Try again." }
        }
        dialog.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        val focus = dialog.currentFocus
                        if (focus === confirm || focus === cancel) focus?.performClick()
                        else {
                            val editor = focus as? EditText ?: editors.first()
                            editor.requestFocus()
                            host.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dialog.dismiss()
                    true
                }
                else -> false
            }
        }
    }

    private fun openRetroAchievements(url: String) {
        runCatching { popupContext().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { showSettingsMessage("RetroAchievements", "A web browser is needed to open this page.") }
    }

    private fun showGameCollections(menu: EmulatorSettings.MenuWindow, spec: SystemSpec, game: Game, back: () -> Unit,
        focus: Int = 0) {
        val entry = CustomCollections.Entry(spec, game)
        val collections = CustomCollections.list(this)
        val labels = listOf({ "New collection" }) + collections.map { collection ->
            { if (collection.contains(entry)) "✓  ${collection.name}" else collection.name }
        }
        menu.liveActions("Collections", labels, { position ->
            if (position == 0) {
                editFields("New collection", listOf(InputField("Collection name"))) { values ->
                    CustomCollections.create(this, values.single(), entry)
                    showGameCollections(menu, spec, game, back, collections.size + 1)
                }
            } else {
                val collection = collections[position - 1]
                runCatching { CustomCollections.setMember(this, collection.id, entry, !collection.contains(entry)) }
                    .onSuccess { showGameCollections(menu, spec, game, back, position) }.onFailure(::showDataError)
            }
        }, back, initialSelection = focus)
    }


    private fun showGameAchievements(menu: EmulatorSettings.MenuWindow, spec: SystemSpec, game: Game, back: () -> Unit) {
        val title = "RetroAchievements"
        val account = RetroAchievements.account(this)
        if (account == null) {
            menu.page(title, TextView(menu.activity).apply {
                text = "Achievement progress is unavailable."
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, back = back)
            return
        }
        val key = GameCustomArtwork.group(spec, game)
        fun <T> load(retry: () -> Unit, fetch: () -> T, display: (T) -> Unit) {
            val status = TextView(menu.activity).apply {
                text = "Loading…"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusableInTouchMode = true
            }
            menu.page(title, status, back = back)
            status.requestFocus()
            val revision = menu.revision
            retroAchievementsWorker.execute {
                val result = runCatching(fetch)
                runOnUiThread {
                    if (!isDestroyed && menu.dialog.isShowing && menu.revision == revision) {
                        result.fold(display) { error ->
                            val message = if (error is RetroAchievements.AuthenticationError)
                                "Achievement progress is unavailable."
                            else (error as? RetroAchievements.RequestError)?.message
                                ?: "Couldn’t load RetroAchievements. Check your connection and try again."
                            menu.actions(message, listOf("Retry" to retry, "Change game match" to {
                                editAchievementSet(menu, key, null) { showGameAchievements(menu, spec, game, back) }
                            }), back)
                        }
                    }
                }
            }
        }

        fun showSet(set: RetroAchievements.GameSet) {
            val change = {
                editAchievementSet(menu, key, set.id) { showGameAchievements(menu, spec, game, back) }
            }
            load({ showSet(set) }, { RetroAchievements.gameBadges(account, set.id) }) { (name, badges) ->
                if (badges.isEmpty()) {
                    menu.actions("No badges for $name", listOf("Change game match" to change), back)
                } else AchievementBadgeGrid(menu, badges, change) { badge ->
                    val message = listOf(badge.progressSummary(), badge.description)
                        .filter(String::isNotBlank).joinToString("\n\n")
                    showSettingsMessage("${badge.title} · ${badge.points} points", message)
                }.show("$title · $name", back)
            }
        }

        val linked = RetroAchievements.linkedSet(this, key)
        if (linked != null) {
            showSet(RetroAchievements.GameSet(linked, ""))
        } else load({ showGameAchievements(menu, spec, game, back) },
            { RetroAchievements.match(this, account, spec, game) }) { matches ->
            when (matches.size) {
                0 -> menu.actions("No matching achievement set", listOf("Enter RetroAchievements game ID" to {
                    editAchievementSet(menu, key, null) { showGameAchievements(menu, spec, game, back) }
                }), back)
                1 -> showSet(matches.single())
                else -> menu.actions("Choose the matching game", matches.map { set ->
                    "${set.title} · #${set.id}" to {
                        RetroAchievements.linkSet(this, key, set.id)
                        showSet(set)
                    }
                } + ("Enter RetroAchievements game ID" to {
                    editAchievementSet(menu, key, null) { showGameAchievements(menu, spec, game, back) }
                }), back)
            }
        }
    }

    private fun editAchievementSet(menu: EmulatorSettings.MenuWindow, key: String, currentId: Int?, refresh: () -> Unit) {
        editFields("RetroAchievements game match", listOf(
            InputField("Game ID from retroachievements.org/game/… (blank for automatic)", currentId?.toString().orEmpty())
        )) { values ->
            val input = values.single()
            val id = input.toIntOrNull()
            require(input.isBlank() || id != null && id > 0) { "Enter the game’s numeric RetroAchievements ID." }
            RetroAchievements.linkSet(this, key, id)
            if (menu.dialog.isShowing) refresh()
        }
    }

    private fun showSystemMenu(spec: SystemSpec, host:Activity=this) {
        if (startMenu?.isShowing == true) return
        val currentGame = if (library && system == spec) games.getOrNull(gameCursor) else null
        val menu = EmulatorSettings.MenuWindow(host, fullScreen = OrbitThemeArtwork.screen(host) == "top",
            smoothSelection = true)
        startMenu = menu.dialog
        if(host!==this) { remoteSettingsDialog=menu.dialog;remoteSettingsHost=host }
        val customArtwork = currentGame?.let { GameCustomArtworkPanel(this, menu, spec, it) }
        menu.dialog.setOnDismissListener {
            settingsWindowClosed(menu.dialog)
        }
        val sections = if (currentGame == null) listOf("Scraper", "Emulator", "Customisation")
            else listOf("Scraper", "Emulator", "Customisation", "RetroAchievements")
        var section=0
        fun showScraper(selected: Int = 0) {
            val artworkLabel = if (customArtwork == null) "System artwork" else "View or change artwork"
            val labels = buildList<() -> String> {
                add { artworkLabel }
                add { "Media types" }
                add { "Hide Media" }
                if (customArtwork == null) add { "Choose a game to scrape" }
            }
            menu.liveActions("Scraper", labels, { position ->
                val back = { showScraper(position) }
                when (position) {
                    0 -> if (customArtwork != null) customArtwork.showImages(back)
                        else SystemArtworkSettingsPanel(this, menu, spec).show(back)
                    1 -> showScraperMediaTypes(menu, back)
                    2 -> GameMediaSettingsPanel(this, menu).show(back)
                    3 -> {
                        menu.dialog.dismiss()
                        openSystem(systems.indexOf(spec))
                    }
                }
            }, menu.dialog::dismiss, initialSelection = selected)
        }
        fun home() {
            menu.selectTab(section)
            when(sections[section]) {
                "Emulator" -> if(currentGame!=null) EmulatorSettings.showForGame(menu,spec,currentGame) { menu.dialog.dismiss() }
                    else EmulatorSettings.showForSystemIn(menu,spec,{}, { menu.dialog.dismiss() })
                "Scraper" -> showScraper()
                "RetroAchievements" -> currentGame?.let { showGameAchievements(menu, spec, it, menu.dialog::dismiss) }
                "Customisation" -> menu.actions("Customisation", buildList {
                    add("Animation" to {
                        if (currentGame != null) showGameAnimations(menu, ::home)
                        else menu.actions("Animation", emptyList(), ::home)
                    })
                    if (currentGame == null) add("System views" to { showSystemViews(menu, ::home) })
                    else {
                        add("Game Views" to { showGameViews(menu, ::home) })
                        add("Collections" to { showGameCollections(menu, spec, currentGame, ::home) })
                    }
                })
            }
        }
        menu.tabs(if(currentGame!=null) "Game Settings" else "System Settings",currentGame?.let { "${it.title} · ${spec.title}" } ?: spec.title,sections,section,compactTabs=currentGame!=null) { section=it;home() }
        menu.onShoulder={ direction -> section=(section+direction+sections.size)%sections.size;home() }
        home()
    }
    private fun showScraperMediaTypes(menu: EmulatorSettings.MenuWindow, back: () -> Unit) {
        val preferences = getSharedPreferences("scraper_credentials", 0)
        val categories = ScraperMediaTypes.categories
        val defaults = categories.map { it.folder }.toSet()
        fun enabled() = preferences.getStringSet("media.categories", defaults).orEmpty()
        val labels = categories.map { category ->
            { "${category.title} · ${if (category.folder in enabled()) "On" else "Off"}" }
        }
        menu.liveActions("Media types", labels, { position ->
            val type = categories[position].folder
            val next = enabled().toMutableSet()
            if (!next.add(type)) next.remove(type)
            preferences.edit().putStringSet("media.categories", next).apply()
        }, back)
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
                    val popup = GameScraperPopup(menu, system, game, ::onGameScraped)
                    startMenu = menu.dialog
                    menu.dialog.setOnDismissListener {
                        popup.destroy(); startMenu = null; (activeGameWheel() ?: gameList)?.requestFocus()
                        DualScreenCoordinator.refreshMedia()
                    }
                    popup.show()
                }
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (ready && activeTab == 0 && !event.isCanceled &&
                activeSplitTileMenu()?.shortcutSelected!=true) {
                val target = if (library) system else focusedBrowseSystem ?: return true
                if (target == retroAchievementsSystem) openRetroAchievementsPage()
                else if (populated.isNotEmpty()) showSystemMenu(target,settingsShortcutHost())
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
