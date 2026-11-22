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
    private val retryFocus = Runnable { updatePlayback() }
    private val releaseFocusIfHidden = Runnable {
        if (!visible && power.isInteractive) releaseFocus()
    }
    private val refreshVolume = Runnable {
        if (canPlay()) player?.let(::applyMediaVolume)
    }
    private var focusAttempts = 0
    private var recoveries = 0
    /** After the audio service restarts, the player and Thor's volume route are gone: build them again. */
    private val recover = Runnable {
        if (canPlay()) {
            Log.i("OrbitSystemsMusic", "Restoring the Systems track after a playback error")
            updatePlayback()
            refreshVolumeAfterReturn()
        }
    }
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
                        if (canPlay() && prepared) player?.let {
                            applyMediaVolume(it)
                            it.start()
                        }
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
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(releaseFocusIfHidden)
                    updatePlayback()
                }
                Intent.ACTION_SCREEN_ON -> {
                    updatePlayback()
                    refreshVolumeAfterReturn()
                }
                VOLUME_CHANGED -> {
                    if (intent.getIntExtra(VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                        player?.let(::applyMediaVolume)
                    }
                }
            }
        }
    }

    fun setBrowsing(active: Boolean, visible: Boolean) {
        val returning = active && !browsing
        this.visible = visible
        handler.removeCallbacks(releaseFocusIfHidden)
        // onStop can arrive just before SCREEN_OFF. Let the power transition
        // settle so closing the lid doesn't abandon the retained audio session.
        if (!visible) handler.postDelayed(releaseFocusIfHidden, 300L)
        if (browsing != active) focusAttempts = 0
        browsing = active
        if (!listening && visible) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(VOLUME_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            listening = true
        }
        updatePlayback()
        if (returning) refreshVolumeAfterReturn()
    }

    private fun refreshVolumeAfterReturn() {
        handler.removeCallbacks(refreshVolume)
        // AYN can restore display-specific routing after the first resume event.
        handler.postDelayed(refreshVolume, 250L)
        handler.postDelayed(refreshVolume, 750L)
    }

    private fun canPlay() = browsing && power.isInteractive

    private fun updatePlayback() {
        if (!canPlay()) {
            handler.removeCallbacks(retryFocus)
            handler.removeCallbacks(refreshVolume)
            focusAttempts = 0
            // Retain Thor's prepared track across app switches as well as Games
            // and sleep, so returning resumes instead of loading the track again.
            if (!paused && prepared) player?.pause()
            paused = true
            if (!isThor && power.isInteractive) stop()
            return
        }
        if (hasFocus && player != null && !paused) {
            player?.let(::applyMediaVolume)
            return
        }
        paused = false
        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                // The Home activity can become visible before Android considers
                // it eligible for audio focus. Don't wait for the user to navigate.
                handler.removeCallbacks(retryFocus)
                if (visible && focusAttempts < 10) {
                    focusAttempts++
                    handler.postDelayed(retryFocus, 300L)
                }
                return
            }
            focusPaused = false
        }
        handler.removeCallbacks(retryFocus)
        focusAttempts = 0
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
                    recoveries = 0
                    next.isLooping = true
                    if (canPlay() && hasFocus && !focusPaused) {
                        applyMediaVolume(next)
                        next.start()
                    }
                }
            }
            next.setOnErrorListener { _, what, extra ->
                Log.w("OrbitSystemsMusic", "Could not play the Systems track ($what, $extra)")
                if (player === next) {
                    stop()
                    // A media server restart ends playback without the app knowing; rebuild the track
                    // a few times rather than staying silent until Orbit is relaunched.
                    if (recoveries < 3) {
                        recoveries++
                        handler.removeCallbacks(recover)
                        handler.postDelayed(recover, 1000L * recoveries)
                    }
                }
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
        // Boost Thor's quiet media steps without duplicating Android's volume
        // attenuation. Elsewhere keep a constant track gain.
        val gain = SystemsMusicVolume.gain(audio.getStreamVolume(stream),
            audio.getStreamMaxVolume(stream), softwareVolume = isThor,
            muted = audio.isStreamMute(stream))
        target.setVolume(gain, gain)
    }

    fun destroy() {
        setBrowsing(false, false)
        handler.removeCallbacks(releaseFocusIfHidden)
        if (listening) {
            context.unregisterReceiver(receiver)
            listening = false
        }
        stop()
    }

    private fun stop() {
        handler.removeCallbacks(retryFocus)
        handler.removeCallbacks(recover)
        handler.removeCallbacks(refreshVolume)
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
