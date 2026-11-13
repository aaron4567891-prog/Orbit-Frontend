package au.orbit.frontend

/** Menu pages publish semantic state; Android's temporary focus is never a menu selection. */
class MenuBrowseState {
    enum class Icon { SETTINGS, ARTWORK, EMULATOR, STORAGE, DISPLAY, SEARCH }
    private var scope:String?=null
    private var page="Orbit menu"
    private var option:String?=null
    val title get()=option ?: page
    val key get()="menu/${scope.orEmpty()}/$page/$title"
    val icon:Icon get()=when {
        scope?.contains("settings",true)==true -> Icon.SETTINGS
        page.contains("scrap",true) || page.contains("search",true) -> Icon.SEARCH
        page.contains("theme",true) || page.contains("art",true) -> Icon.ARTWORK
        page.contains("screen",true) || page.contains("display",true) -> Icon.DISPLAY
        page.contains("emulator",true) -> Icon.EMULATOR
        page.contains("folder",true) || page.contains("data",true) -> Icon.STORAGE
        else -> Icon.SETTINGS
    }
    fun scope(title:String?) { scope=title }
    fun page(title:String) { page=title;option=null }
    fun select(label:String) { option=label.takeIf { it.isNotBlank() } }
}
