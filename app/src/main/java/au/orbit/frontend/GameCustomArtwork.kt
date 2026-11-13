package au.orbit.frontend

import android.content.Context

internal object GameCustomArtwork {
    const val HERO = "customHero"
    val roles = listOf(HERO, "covers", "physicalmedia", "marquees")
    private const val SECTION = "gameArtwork"
    private const val CLEARED = "removed"

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

    fun save(context: Context, group: String, screen: String, role: String, file: String?) {
        // Clearing the hero also suppresses any previous background for this screen.
        val value = file ?: CLEARED.takeIf { role == HERO && previousBackground(group, screen) != null }
        UserDataStore.put(context, SECTION, key(group, screen, role), value)
    }
}
