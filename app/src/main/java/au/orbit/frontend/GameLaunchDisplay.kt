package au.orbit.frontend

import android.content.Context

/** Physical display preferences survive frontend screen swaps and display ID changes. */
internal object GameLaunchDisplay {
    private fun gameKey(system:SystemSpec,game:Game)="${system.id}/${ScraperClient.gameKey(game.uri.toString())}"
    fun system(context:Context,spec:SystemSpec)=UserDataStore.string(context,"systemLaunchScreens",spec.id)
    fun game(context:Context,spec:SystemSpec,game:Game)=UserDataStore.string(context,"gameLaunchScreens",gameKey(spec,game))
    fun save(context:Context,spec:SystemSpec,game:Game?,screen:String?) {
        require(screen==null || screen in setOf("top","bottom"))
        UserDataStore.put(context,if(game==null)"systemLaunchScreens" else "gameLaunchScreens",if(game==null)spec.id else gameKey(spec,game),screen)
    }
    fun resolve(game:String?,system:String?)=game ?: system ?: "top"
    fun display(context:Context,spec:SystemSpec,game:Game,screenOverride:String?=null):Int {
        val screen=screenOverride ?: resolve(game(context,spec,game),system(context,spec))
        return DualScreenManager(context).displayForScreen(screen)
    }
    fun label(screen:String?)=when(screen) { "bottom" -> "Bottom screen";"top" -> "Top screen";else -> "Use system default" }
}
