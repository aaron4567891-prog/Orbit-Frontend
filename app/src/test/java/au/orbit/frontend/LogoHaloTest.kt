package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class LogoHaloTest {
    @Test fun onlyGreyOrBlackLogosAreRecoloured() {
        assertEquals(true, LogoHalo.isDarkMonochrome(0.02f, 0.2f))   // black wordmark
        assertEquals(true, LogoHalo.isDarkMonochrome(0.02f, 0.65f))  // light grey wordmark (PSP)
        assertEquals(false, LogoHalo.isDarkMonochrome(0.02f, 0.9f))  // white wordmark
        assertEquals(false, LogoHalo.isDarkMonochrome(0.6f, 0.2f))   // dark red or blue logo
        assertEquals(false, LogoHalo.isDarkMonochrome(0.1f, 0.37f, 0.29f))  // black-and-white (Sega CD)
    }

    @Test fun aLogoWithNoVisiblePixelsHasNoSaturation() {
        assertEquals(0f, LogoHalo.saturation(IntArray(16)), 0f)
    }
}
