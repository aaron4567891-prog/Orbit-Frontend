package au.orbit.frontend

/** Media preferences for the artwork slot assigned to each display by the active theme. */
internal object ScreenMediaSettings {
    val categories=ScraperMediaTypes.categories
    val backgrounds=listOf("fanart","screenshots","covers","physicalmedia","marquees","videos","manuals","theme","off")
    fun key(screen:String,option:String):String {
        require(screen in setOf("top","bottom"))
        require(option=="background" || categories.any { it.folder==option })
        return "gameMedia.$screen.$option"
    }
    fun normalizeBackground(value:String?)=when(value) {
        "screenshot" -> "screenshots"
        in backgrounds -> value!!
        else -> null
    }
    fun backgroundDefault(screen:String,legacy:String?)=if(screen=="top")normalizeBackground(legacy) ?: "fanart" else "theme"
    fun defaultVisible(screen:String,type:String,legacyTopVideo:Boolean,legacyCompanion:Int):Boolean =
        if(screen=="top")type=="covers" || type=="videos" && legacyTopVideo
        else type==listOf("fanart","screenshots","videos")[legacyCompanion.coerceIn(0,2)]
    fun background(screen:String)=normalizeBackground(UserDataStore.cachedString("settings",key(screen,"background")))
        ?: backgroundDefault(screen,UserDataStore.cachedString("settings","gameBackgroundMedia"))
    fun visible(screen:String,type:String)=UserDataStore.cachedInt(key(screen,type),
        if(defaultVisible(screen,type,UserDataStore.cachedInt("topScreenVideo",1)==1,UserDataStore.cachedInt("companionMedia",2)))1 else 0)==1
    fun shown(screen:String)=categories.filter { visible(screen,it.folder) }.map { it.folder }
    fun signature(screen:String)="${background(screen)}/${shown(screen).joinToString(",")}"
    fun label(value:String)=when(value) {
        "theme" -> "Theme background"
        "off" -> "Off"
        else -> categories.firstOrNull { it.folder==value }?.title ?: value
    }
}
