package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class MenuBrowseStateTest {
    @Test fun settingsKeepOneIconAcrossPagesAndSelectedOptions() {
        val state=MenuBrowseState()
        state.scope("System Settings")
        for(page in listOf("Customisation · PlayStation","Top screen · PlayStation","Bottom screen · PlayStation","PlayStation emulator")) {
            state.page(page)
            assertEquals(page,state.title)
            assertEquals(MenuBrowseState.Icon.SETTINGS,state.icon)
            for(option in listOf("Hero image","System image","Use default system image","Show system image · Off")) {
                state.select(option)
                assertEquals(option,state.title)
                assertEquals(MenuBrowseState.Icon.SETTINGS,state.icon)
            }
        }
    }
    @Test fun pageReplacementUsesTheDestinationPageWithoutPublishingTemporaryFocus() {
        val state=MenuBrowseState()
        state.scope("Game Settings")
        state.page("Choose emulator");state.select("DuckStation")
        state.page("Launch display")
        assertEquals("Launch display",state.title)
        assertEquals(MenuBrowseState.Icon.SETTINGS,state.icon)
        assertFalse(state.key.contains("Back"))
        state.select("Bottom screen")
        assertEquals("Bottom screen",state.title)
    }
    @Test fun standaloneMenuIconsUseTheirPublishedPageAndDoNotChangeWithRowText() {
        val state=MenuBrowseState()
        state.page("Choose emulator")
        assertEquals(MenuBrowseState.Icon.EMULATOR,state.icon)
        state.select("Back")
        assertEquals(MenuBrowseState.Icon.EMULATOR,state.icon)
        state.page("Scraping")
        assertEquals(MenuBrowseState.Icon.SEARCH,state.icon)
        state.select("Start scraping")
        assertEquals(MenuBrowseState.Icon.SEARCH,state.icon)
    }
}
