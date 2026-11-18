package au.orbit.frontend

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionAndAchievementsTest {
    @Test fun collectionsPreserveGameIdentityAcrossSystemsAndTitleChanges() {
        val first = CustomCollections.Entry("nes", "content://games/shared", "Old title")
        val second = first.copy(systemId = "snes")
        val original = listOf(CustomCollections.Collection("id", "A & B", listOf(first)),
            CustomCollections.Collection("other", "Empty", emptyList()))
        val restored = CustomCollections.decode(JSONArray(CustomCollections.encode(original).toString()))

        assertEquals(original, restored)
        assertTrue(restored.first().contains(first.copy(title = "New title")))
        assertFalse(restored.first().contains(second))
    }

}
