package au.orbit.frontend

import android.media.AudioAttributes
import java.util.concurrent.Executors

/**
 * Plays the user's own sound when the Y menu opens on a system: a file at sounds/<system id>.<ogg, mp3,
 * wav, m4a or flac> in their data folder. Systems without a file are silent. Turned on or off in
 * System Settings → Customisation → Systems panel.
 */
internal object SystemSounds {
    private val worker = Executors.newSingleThreadExecutor()

    private const val ENABLED_KEY = "systemsPanel.ySound"
    fun enabled() = UserDataStore.cachedInt(ENABLED_KEY, 1) != 0
    fun toggle(context: android.content.Context) = UserDataStore.put(context, "settings", ENABLED_KEY, if (enabled()) 0 else 1)

    fun play(context: android.content.Context, systemId: String) {
        if (!enabled()) return
        val app = context.applicationContext
        worker.execute {
            val uri = runCatching { UserDataStore.systemSoundUri(app, systemId) }.getOrNull() ?: return@execute
            runCatching {
                android.media.MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(app, uri)
                    setOnCompletionListener { it.release() }
                    setOnErrorListener { player, _, _ -> player.release(); true }
                    prepare()
                    start()
                }
            }
        }
    }
}
