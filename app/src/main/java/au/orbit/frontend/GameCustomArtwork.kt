package au.orbit.frontend

import android.content.Context
import org.json.JSONArray

internal object GameCustomArtwork {
    const val HERO = "customHero"
    val roles = listOf(HERO, "covers", "3dboxes", "physicalmedia", "marquees", "fanart", "screenshots", "videos")
    private val sharedRoles = setOf("covers", "3dboxes", "physicalmedia", "videos")
    private const val SECTION = "gameArtwork"
    private const val CLEARED = "removed"
    private const val REMOVED_SECTION = "removedGameArtwork"
    private val mediaLocks = Array(64) { Any() }

    fun <T> editMedia(group: String, action: () -> T): T =
        synchronized(mediaLocks[group.hashCode() and (mediaLocks.size - 1)], action)

    fun group(system: SystemSpec, game: Game) = "${system.id}/${ScraperClient.gameKey(game.uri.toString())}"

    private fun key(group: String, screen: String, role: String) = "$group/$screen/$role"

    private fun previousBackground(group: String, screen: String) =
        UserDataStore.cachedString(SECTION, key(group, screen, "customBackground"))

    fun file(group: String, screen: String, role: String) =
        (UserDataStore.cachedString(SECTION, key(group, screen, role))
            ?: if (role == HERO) previousBackground(group, screen) else null)
            ?.takeIf(OrbitThemes::validArtworkPath)

    fun paths(group: String, screen: String) = roles.mapNotNull { role ->
        file(group, screen, role)?.let { role to it }
    }.toMap()

    private fun removalKey(group: String, screen: String) = "$group/$screen"

    fun removed(group: String, screen: String): Set<String> =
        storedRemovals(group, screen).orEmpty() - sharedRoles + sharedRemovals(group)

    private fun sharedRemovals(group: String): Set<String> {
        storedRemovals(group, "shared")?.let { return it }
        return legacySharedRemovals(
            listOf(storedRemovals(group, "top").orEmpty(), storedRemovals(group, "bottom").orEmpty())
        )
    }

    internal fun legacySharedRemovals(legacy: List<Set<String>>): Set<String> =
        buildSet {
            addAll(sharedRoles.filter { role -> legacy.any { role in it } })
            // Older Remove all predates the video and 3D box art rows.
            val previousRoles = roles - setOf("videos", "3dboxes")
            if (legacy.any { it.containsAll(previousRoles) }) addAll(listOf("videos", "3dboxes"))
        }

    private fun storedRemovals(group: String, screen: String): Set<String>? {
        val saved = UserDataStore.cachedString(REMOVED_SECTION, removalKey(group, screen)) ?: return null
        return runCatching {
            val array = JSONArray(saved)
            (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
        }.getOrDefault(emptySet())
    }

    fun setRemoved(context: Context, group: String, screen: String, roles: Collection<String>, remove: Boolean) {
        val shared = roles.filter { it in sharedRoles }
        val local = roles.filterNot { it in sharedRoles }
        fun changed(previous: Set<String>, targets: List<String>) =
            if (remove) previous + targets else previous - targets.toSet()

        if (shared.isNotEmpty()) {
            val hidden = changed(sharedRemovals(group), shared)
            UserDataStore.put(context, REMOVED_SECTION, removalKey(group, "shared"), JSONArray(hidden.sorted()))
        }
        if (local.isNotEmpty()) {
            val hidden = changed(storedRemovals(group, screen).orEmpty() - sharedRoles, local)
            UserDataStore.put(context, REMOVED_SECTION, removalKey(group, screen),
                hidden.takeIf { it.isNotEmpty() }?.let { JSONArray(it.sorted()) })
        }
    }

    internal fun restoredRoles(mediaFolders: Set<String>, background: String): Set<String> =
        if (background in mediaFolders) mediaFolders + HERO else mediaFolders

    internal fun restoreScrapedMedia(context: Context, group: String, mediaFolders: Set<String>) {
        if (mediaFolders.isEmpty()) return
        val screens = listOf("top", "bottom")
        if (screens.none { screen -> removed(group, screen).isNotEmpty() }) return
        editMedia(group) {
            for (screen in screens) {
                val restored = restoredRoles(mediaFolders, ScreenMediaSettings.background(screen))
                val hidden = removed(group, screen).intersect(restored)
                if (hidden.isNotEmpty()) setRemoved(context, group, screen, hidden, remove = false)
            }
        }
    }

    fun delete(context: Context, group: String, screen: String, role: String? = null) = editMedia(group) {
        val screens = listOf("top", "bottom")
        val targets = if (role == null) roles else listOf(role)
        val mediaRoles = targets.toMutableSet()
        if (role == HERO && file(group, screen, HERO) == null) {
            mediaRoles.add(ScreenMediaSettings.background(screen))
        }
        val mediaTypes = ScraperMediaTypes.categories.filter { it.folder in mediaRoles }.flatMap { it.types }.toSet()
        if (role == null || mediaTypes.isNotEmpty()) {
            UserDataStore.deleteGameMedia(context, group, mediaTypes.takeUnless { role == null })
        }
        for (targetScreen in screens) {
            for (target in targets) {
                deleteAssignment(context, group, targetScreen, target)
            }
            if (removed(group, targetScreen).any { it in mediaRoles }) {
                setRemoved(context, group, targetScreen, mediaRoles, remove = false)
            }
        }
    }

    private fun deleteAssignment(context: Context, group: String, screen: String, role: String) {
        val assignment = key(group, screen, role)
        if (role == HERO) {
            val previous = previousBackground(group, screen)
            previous?.takeIf(OrbitThemes::validArtworkPath)?.let { UserDataStore.deleteGameArtworkFile(context, it) }
            if (previous != null) UserDataStore.put(context, SECTION, key(group, screen, "customBackground"), null)
        }
        val current = UserDataStore.cachedString(SECTION, assignment) ?: return
        if (OrbitThemes.validArtworkPath(current)) UserDataStore.deleteGameArtworkFile(context, current)
        UserDataStore.put(context, SECTION, assignment, null)
    }

    fun save(context: Context, group: String, screen: String, role: String, file: String?) {
        // Clearing the hero also suppresses any previous background for this screen.
        val value = file ?: CLEARED.takeIf { role == HERO && previousBackground(group, screen) != null }
        UserDataStore.put(context, SECTION, key(group, screen, role), value)
        if (role in removed(group, screen)) setRemoved(context, group, screen, listOf(role), remove = false)
    }
}
