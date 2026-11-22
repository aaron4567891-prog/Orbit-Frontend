package au.orbit.frontend

import org.junit.Assert.*
import org.junit.Test

class SettledSelectionTest {
    private class Preview {
        var now = 0L
        var moving = false
        val updates = mutableListOf<String>()
        private val tasks = mutableMapOf<Runnable, Long>()
        val selection = SettledSelection<String>(
            post = { task, delay -> tasks[task] = now + delay },
            remove = { tasks.remove(it); Unit },
            moving = { moving },
            changed = { updates.add(it) }
        )
        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                val next = tasks.minByOrNull { it.value }?.takeIf { it.value <= end } ?: break
                now = next.value
                val task = next.key
                tasks.remove(task)
                task.run()
            }
            now = end
        }
    }

    @Test fun firstSelectionAppearsImmediatelyAndRapidScrollPublishesOnlyTheLastSystem() {
        val preview = Preview()
        preview.selection.select("nes", true)
        preview.selection.select("snes", true)
        preview.advance(100)
        preview.selection.select("n64", true)
        preview.advance(179)
        assertEquals(listOf("nes"), preview.updates)
        preview.advance(1)
        assertEquals(listOf("nes", "n64"), preview.updates)
        assertEquals("n64", preview.selection.current)
    }

    @Test fun heldInputAndAnimationMustFinishBeforeThePreviewChanges() {
        val preview = Preview()
        preview.selection.select("nes", true)
        preview.moving = true
        preview.selection.select("snes", true)
        preview.advance(1000)
        assertEquals(listOf("nes"), preview.updates)
        preview.moving = false
        preview.advance(16)
        assertEquals(listOf("nes", "snes"), preview.updates)
    }

    @Test fun ReturningToTheDisplayedSystemCancelsIntermediateArtwork() {
        val preview = Preview()
        preview.selection.select("nes", true)
        preview.selection.select("snes", true)
        preview.selection.select("nes", true)
        preview.advance(500)
        assertEquals(listOf("nes"), preview.updates)
    }

    @Test fun disablingWaitPublishesImmediatelyAndCancelsTheOldPendingSelection() {
        val preview = Preview()
        preview.selection.select("nes", true)
        preview.selection.select("snes", true)
        preview.selection.select("n64", false)
        preview.advance(500)
        assertEquals(listOf("nes", "n64"), preview.updates)
    }

    @Test fun clearingStopsPendingWorkWhenTheScreenIsReplaced() {
        val preview = Preview()
        preview.selection.select("nes", true)
        preview.selection.select("snes", true)
        preview.selection.clear()
        preview.advance(500)
        assertNull(preview.selection.current)
        assertEquals(listOf("nes"), preview.updates)
    }
}
