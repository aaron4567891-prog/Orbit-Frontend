package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class ScreenMediaSettingsTest {
    @Test fun newUsersGetFanArtWhileExistingBackgroundChoicesSurvive() {
        assertEquals("fanart",ScreenMediaSettings.backgroundDefault("top",null))
        assertEquals("screenshots",ScreenMediaSettings.backgroundDefault("top","screenshot"))
        assertEquals("theme",ScreenMediaSettings.backgroundDefault("top","theme"))
        assertEquals("fanart",ScreenMediaSettings.backgroundDefault("top","invalid"))
    }
    @Test fun topBackgroundDoesNotChangeCompanionDefaults() {
        assertEquals("theme",ScreenMediaSettings.backgroundDefault("bottom","screenshot"))
        assertEquals("theme",ScreenMediaSettings.backgroundDefault("bottom","off"))
    }
    @Test fun displayKeysKeepVisibilityAndBackgroundsIndependent() {
        for(type in listOf("background","covers","marquees","videos","fanart","screenshots","physicalmedia","manuals")) {
            assertNotEquals(ScreenMediaSettings.key("top",type),ScreenMediaSettings.key("bottom",type))
        }
        assertNotEquals(ScreenMediaSettings.key("top","covers"),ScreenMediaSettings.key("top","background"))
    }
    @Test fun legacyVideoSwitchAndCompanionChoiceArePreservedIndependently() {
        assertFalse(ScreenMediaSettings.defaultVisible("top","videos",false,2))
        assertTrue(ScreenMediaSettings.defaultVisible("bottom","videos",false,2))
        assertTrue(ScreenMediaSettings.defaultVisible("top","covers",false,0))
        assertTrue(ScreenMediaSettings.defaultVisible("bottom","fanart",true,0))
        assertFalse(ScreenMediaSettings.defaultVisible("bottom","videos",true,0))
        assertTrue(ScreenMediaSettings.defaultVisible("bottom","screenshots",true,1))
    }
    @Test fun offIsAValidChoiceAndInvalidScreensCannotCreateSettings() {
        assertEquals("off",ScreenMediaSettings.normalizeBackground("off"))
        assertNull(ScreenMediaSettings.normalizeBackground("unknown"))
        assertThrows(IllegalArgumentException::class.java) { ScreenMediaSettings.key("secondary","covers") }
        assertThrows(IllegalArgumentException::class.java) { ScreenMediaSettings.key("top","unknown") }
    }
}
