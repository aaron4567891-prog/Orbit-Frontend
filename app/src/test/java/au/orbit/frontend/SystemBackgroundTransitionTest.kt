package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class SystemBackgroundTransitionTest {
    private val left = SystemBackgroundTransition.Direction.LEFT

    @Test fun crossfadeKeepsTheIncomingImageOpaqueUnderTheOutgoingImage() {
        val style = SystemBackgroundTransition.Style.CROSSFADE
        val start = SystemBackgroundTransition.frame(style, 0f, left)
        val middle = SystemBackgroundTransition.frame(style, 0.5f, left)
        val end = SystemBackgroundTransition.frame(style, 1f, left)
        assertEquals(1f, start.oldAlpha, 0f)
        assertEquals(0.5f, middle.oldAlpha, 0.0001f)
        assertEquals(1f, middle.newAlpha, 0f)
        assertEquals(0f, end.oldAlpha, 0f)
        assertEquals(1f, end.newAlpha, 0f)
    }

    @Test fun removedStyleFallsBackToCrossfade() {
        assertEquals(SystemBackgroundTransition.Style.CROSSFADE, SystemBackgroundTransition.Style.fromId(5))
    }

    @Test fun fadeThroughBlackHasAnEmptyMidpoint() {
        val frame = SystemBackgroundTransition.frame(SystemBackgroundTransition.Style.FADE, 0.5f, left)
        assertEquals(0f, frame.oldAlpha, 0f)
        assertEquals(0f, frame.newAlpha, 0f)
    }

    @Test fun slideKeepsImageEdgesTogetherInEveryDirection() {
        for (direction in SystemBackgroundTransition.Direction.entries) {
            for (progress in listOf(0f, 0.25f, 0.5f, 1f)) {
                val frame = SystemBackgroundTransition.frame(SystemBackgroundTransition.Style.SLIDE, progress, direction)
                assertEquals(direction.x, frame.oldX - frame.newX, 0.0001f)
                assertEquals(direction.y, frame.oldY - frame.newY, 0.0001f)
            }
        }
    }

    @Test fun progressIsClampedAndZoomSettlesAtFullSize() {
        for (style in SystemBackgroundTransition.Style.entries) {
            assertEquals(SystemBackgroundTransition.frame(style, 0f, left), SystemBackgroundTransition.frame(style, -1f, left))
            assertEquals(SystemBackgroundTransition.frame(style, 1f, left), SystemBackgroundTransition.frame(style, 2f, left))
        }
        assertEquals(1f, SystemBackgroundTransition.frame(SystemBackgroundTransition.Style.ZOOM, 1f, left).newScale, 0f)
        assertEquals(0L, SystemBackgroundTransition.duration(SystemBackgroundTransition.Style.INSTANT,
            SystemBackgroundTransition.Speed.VERY_SLOW))
        assertEquals(SystemBackgroundTransition.Style.CROSSFADE, SystemBackgroundTransition.Style.fromId(999))
    }
}
