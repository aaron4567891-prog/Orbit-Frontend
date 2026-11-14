package au.orbit.frontend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

internal class SystemsMusic(private val context: Context) {
    private companion object {
        // AudioService sends this when media volume changes through the buttons or panel.
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val MAX_TRACK_GAIN = 0.614f
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val isThor = Build.MANUFACTURER.equals("AYN", ignoreCase = true) &&
        Build.MODEL.contains("Thor", ignoreCase = true)
    private var prepared = false
    private var focusPaused = false
    private var paused = false
    private var visible = false
    private val handler = Handler(Looper.getMainLooper())
    private val releaseIfHidden = Runnable { if (!visible && power.isInteractive) stop() }
    private var browsing = false
    private var listening = false
    private var hasFocus = false
    private var player: MediaPlayer? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (hasFocus) {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusPaused = false
                        if (canPlay() && prepared) player?.start()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        focusPaused = true
                        if (prepared) player?.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        if (prepared) player?.pause()
                        paused = true
                        releaseFocus()
                    }
                }
            }
        }
        .build()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> updatePlayback()
                VOLUME_CHANGED -> {
                    if (intent.getIntExtra(VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                        player?.let(::applyMediaVolume)
                    }
                }
            }
        }
    }

    fun setBrowsing(active: Boolean, visible: Boolean) {
        this.visible = visible
        handler.removeCallbacks(releaseIfHidden)
        if (!visible && player != null) handler.postDelayed(releaseIfHidden, 750L)
        browsing = active
        if (listening != visible) {
            listening = visible
            if (visible) {
                val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(VOLUME_CHANGED)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }

            } else {
                context.unregisterReceiver(receiver)
            }
        }
        updatePlayback()
    }

    private fun canPlay() = browsing && power.isInteractive

    private fun updatePlayback() {
        if (!canPlay()) {
            // Keep the track allocated across Games and sleep. Thor can assign a new
            // track to the bottom-screen volume when both Orbit windows wake.
            if (!paused && prepared) player?.pause()
            paused = true
            if (!isThor && power.isInteractive) stop()
            return
        }
        if (hasFocus && player != null && !paused) return
        paused = false
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) return
        player?.let {
            if (prepared) {
                applyMediaVolume(it)
                if (!focusPaused) it.start()
            }
            return
        }

        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(R.raw.orbit_systems_music).use { next.setDataSource(it) }
            applyMediaVolume(next)
            next.setOnPreparedListener {
                if (player === next) {
                    prepared = true
                    next.isLooping = true
                    if (canPlay() && hasFocus && !focusPaused) {
                        applyMediaVolume(next)
                        next.start()
                    }
                }
            }
            next.setOnErrorListener { _, what, extra ->
                Log.w("OrbitSystemsMusic", "Could not play the Systems track ($what, $extra)")
                if (player === next) stop()
                true
            }
            next.prepareAsync()
        } catch (failure: Exception) {
            Log.w("OrbitSystemsMusic", "Could not open the Systems track", failure)
            stop()
        }
    }

    private fun applyMediaVolume(target: MediaPlayer) {
        val stream = AudioManager.STREAM_MUSIC
        val level = audio.getStreamVolume(stream)
        val maximum = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val fraction = level.coerceIn(0, maximum).toFloat() / maximum
        val normalGain = MAX_TRACK_GAIN * (0.85f + 0.15f * fraction)
        // Give the quietest steps more gain while keeping the original track intact.
        val gain = when (level) {
            0 -> 0f
            in 1..3 -> 1f
            4 -> 0.9f
            else -> normalGain
        }
        target.setVolume(gain, gain)
    }

    fun destroy() {
        setBrowsing(false, false)
        handler.removeCallbacks(releaseIfHidden)
        stop()
    }

    private fun stop() {
        player?.release()
        player = null
        prepared = false
        paused = false
        releaseFocus()
    }

    private fun releaseFocus() {
        focusPaused = false
        if (hasFocus) {
            hasFocus = false
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
