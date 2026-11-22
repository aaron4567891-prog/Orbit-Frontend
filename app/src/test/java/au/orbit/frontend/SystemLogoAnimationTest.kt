package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class SystemLogoAnimationTest {
    @Test fun neighbouringLogosHandOverSmoothlyWhileScrollingEitherDirection() {
        for (progress in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val outgoing = SystemLogoAnimation.scrollSelection(-progress)
            val incoming = SystemLogoAnimation.scrollSelection(1f - progress)
            assertEquals(1f, outgoing + incoming, 0.0001f)
            assertEquals(outgoing, SystemLogoAnimation.scrollSelection(progress), 0f)
        }
    }
}
