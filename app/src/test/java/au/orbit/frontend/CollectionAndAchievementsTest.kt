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

    @Test fun completionProgressPreservesBothUnlockCountsAndPageTotal() {
        val progress = RetroAchievements.parseProgress(JSONObject(
            """{"Total":42,"Results":[{"GameID":10,"Title":"Example","ConsoleName":"NES",
                "NumAwarded":7,"NumAwardedHardcore":3,"MaxPossible":12}]}"""
        ))

        assertEquals(42, progress.total)
        assertEquals(RetroAchievements.GameProgress(10, "Example", "NES", 7, 3, 12), progress.games.single())
    }

    @Test fun recentUnlocksParseHardcoreModeAndEmptyResults() {
        val unlocks = RetroAchievements.parseUnlocks(JSONArray(
            """[{"AchievementID":24,"Title":"First win","GameTitle":"Example","Points":5,"HardcoreMode":1}]"""
        ))

        assertEquals(RetroAchievements.Unlock(24, "First win", "Example", 5, true), unlocks.single())
        assertTrue(RetroAchievements.parseUnlocks(JSONArray()).isEmpty())
    }

    @Test fun badgeMatchingIgnoresRegionAndArticleButRequiresTheSameTitle() {
        val catalog = JSONArray(
            """[{"ID":14,"Title":"The Legend of Zelda"},
                {"ID":28,"Title":"The Legend of Zelda: A Link to the Past"}]"""
        )
        assertEquals(listOf(RetroAchievements.GameSet(14, "The Legend of Zelda")),
            RetroAchievements.matchingSets(catalog, "Legend of Zelda, The (USA) (Rev 1)"))
        assertTrue(RetroAchievements.matchingSets(catalog, "Legend of Zelda II").isEmpty())
    }

    @Test fun badgeGridPreservesSetOrderAndUnlockStatus() {
        val badges = RetroAchievements.parseBadges(JSONObject(
            """{"Achievements":{
                "3":{"ID":3,"Title":"Third","BadgeName":"12303","DisplayOrder":3,"DateEarnedHardcore":"2026-01-01"},
                "1":{"ID":1,"Title":"First","BadgeName":"12301","DisplayOrder":1,"DateEarned":null},
                "2":{"ID":2,"Title":"Second","BadgeName":"12302","DisplayOrder":2,"DateEarned":"2026-01-01"}
            }}"""
        ))
        assertEquals(listOf(1, 2, 3), badges.map { it.id })
        assertEquals(listOf("Locked", "Unlocked", "Hardcore"), badges.map { it.status })
    }

    @Test fun legacyGameArtworkRemovalIncludesNewMediaOnlyAfterRemoveAll() {
        assertEquals(setOf("covers", "physicalmedia"),
            GameCustomArtwork.legacySharedRemovals(listOf(setOf("covers"), setOf("physicalmedia"))))
        val originalRoles = setOf(GameCustomArtwork.HERO, "covers", "physicalmedia", "marquees", "fanart", "screenshots")
        assertEquals(setOf("covers", "physicalmedia", "videos", "3dboxes"),
            GameCustomArtwork.legacySharedRemovals(listOf(originalRoles)))
    }

    @Test fun damagedCollectionEntriesAreSkippedInsteadOfCrashing() {
        val data = org.json.JSONArray("""[
            {"id":"a","name":"Favourites","games":[{"system":"psx","uri":"content://1","title":"Crash"},{"system":"psx"}]},
            {"id":"b"},
            "not a collection"
        ]""")
        val collections = CustomCollections.decode(data)
        assertEquals(1, collections.size)
        assertEquals(listOf("Crash"), collections.single().games.map { it.title })
    }
}
