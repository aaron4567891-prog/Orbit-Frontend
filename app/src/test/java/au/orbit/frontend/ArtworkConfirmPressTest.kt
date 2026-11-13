package au.orbit.frontend

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkConfirmPressTest {
    @Test fun openingPressCannotConfirmInTheNewWindow() {
        val chooser = ArtworkConfirmPress()
        assertFalse(chooser.released(ACTION_DOWN, 10L, repeatCount = 1))
        assertFalse(chooser.released(ACTION_UP, 10L))
        assertFalse(chooser.released(ACTION_DOWN, 20L))
        assertTrue(chooser.released(ACTION_UP, 20L))
        assertFalse(chooser.released(ACTION_UP, 20L))
    }

    @Test fun canceledPressDoesNotConfirm() {
        val chooser = ArtworkConfirmPress()
        chooser.released(ACTION_DOWN, 10L)
        assertFalse(chooser.released(ACTION_UP, 10L, canceled = true))
        assertFalse(chooser.released(ACTION_UP, 10L))
    }
}
