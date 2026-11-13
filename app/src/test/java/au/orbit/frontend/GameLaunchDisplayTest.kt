package au.orbit.frontend

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLaunchDisplayTest {
    @Test fun gameOverrideWinsOverSystem() {
        assertEquals("bottom",GameLaunchDisplay.resolve("bottom","top"))
        assertEquals("top",GameLaunchDisplay.resolve("top","bottom"))
    }
    @Test fun clearingGameOverrideRestoresSystemChoice() {
        assertEquals("bottom",GameLaunchDisplay.resolve(null,"bottom"))
        assertEquals("top",GameLaunchDisplay.resolve(null,null))
    }
}
