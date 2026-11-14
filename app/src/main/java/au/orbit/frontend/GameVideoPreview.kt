package au.orbit.frontend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.Surface
import android.view.TextureView
import android.view.View

/** Local previews. The static artwork remains underneath until a video frame is ready. */
class GameVideoPreview(
    context: Context,
    private val cropToFill: Boolean = false,
    private val audioPriority: Int = 1,
    private val waitForTextureFrame: Boolean = false
) : TextureView(context), TextureView.SurfaceTextureListener {
    private companion object {
        // All previews run on the UI thread, including the companion display.
        val playingPreviews = linkedSetOf<GameVideoPreview>()
        var audiblePreview: GameVideoPreview? = null

        fun selectAudiblePreview() {
            val next = playingPreviews.maxByOrNull { it.audioPriority }
            if (audiblePreview === next && next?.audioFocused == true) return
            audiblePreview?.mutePreview()
            audiblePreview = next
            next?.requestPreviewAudio()
        }
    }
    var frameReadyChanged:((Boolean)->Unit)?=null
    var playbackFailed:(()->Unit)?=null
    var playbackInterrupted:(()->Unit)?=null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var source: Uri? = null
    private var pending: Runnable? = null
    private var ready = false
    private var prepared = false
    private var navigationPaused = false
    private var renderingStarted = false
    private var textureUpdated = false
    internal var hasVisibleFrame = false
        private set
    private var videoWidth = 0
    private var videoHeight = 0
    private var audioFocused = false
    private val audio=context.getSystemService(AudioManager::class.java)
    private val power=context.getSystemService(PowerManager::class.java)
    private var observingPower = false
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> releasePlayback()
                Intent.ACTION_SCREEN_ON -> startVideo()
            }
        }
    }
    private val audioAttributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener { change ->
            if(audioFocused && (change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                stop()
                playbackInterrupted?.invoke()
            }
        }.build()

    init { isOpaque=false; alpha=0f; isFocusable=false; surfaceTextureListener=this }

    fun show(uri: Uri?, delay: Long) {
        stop()
        source=uri
        if(uri==null)return
        observePower()
        pending=Runnable { ready=true; startVideo() }.also { postDelayed(it,delay) }
    }

    fun stop() {
        pending?.let(::removeCallbacks); pending=null; source=null;ready=false
        if (observingPower) {
            context.unregisterReceiver(powerReceiver)
            observingPower = false
        }
        releasePlayback()
    }

    private fun observePower() {
        if (observingPower || !isAttachedToWindow || source == null) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(powerReceiver, filter)
        }
        observingPower = true
    }

    private fun canPlay() = power.isInteractive && windowVisibility == View.VISIBLE && isShown

    private fun releasePlayback() {
        prepared = false
        renderingStarted = false
        textureUpdated = false
        hasVisibleFrame = false
        animate().cancel()
        alpha=0f
        frameReadyChanged?.invoke(false)
        releaseAudio()
        player?.release();player=null
        surface?.release();surface=null
    }

    private fun releaseAudio() {
        val wasPlaying = playingPreviews.remove(this)
        if (audiblePreview === this) {
            mutePreview()
            audiblePreview = null
        }
        if (wasPlaying && audiblePreview == null) selectAudiblePreview()
    }

    /** Hold the last frame while a separate preview prepares, with no audio from this clip. */
    internal fun freezeFrame() {
        source = null
        runCatching { player?.pause() }
        releaseAudio()
    }

    internal fun setNavigationPaused(paused: Boolean) {
        if (navigationPaused == paused) return
        navigationPaused = paused
        if (paused) {
            val current = player
            if (prepared && current?.isPlaying == true) current.pause()
            releaseAudio()
        } else {
            val current = player
            if (current != null && prepared && source != null && canPlay()) playPrepared(current)
            else startVideo()
        }
    }

    private fun playPrepared(media: MediaPlayer) {
        media.start()
        if (media.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }) {
            playingPreviews.add(this)
            selectAudiblePreview()
        }
    }

    private fun requestPreviewAudio() {
        audioFocused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (audioFocused) player?.setVolume(1f, 1f)
    }

    private fun mutePreview() {
        player?.setVolume(0f, 0f)
        if(audioFocused) {
            audioFocused=false
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun failed() { stop();playbackFailed?.invoke() }

    private fun revealFrame() {
        if (!renderingStarted || (waitForTextureFrame && !textureUpdated) || hasVisibleFrame) return
        hasVisibleFrame = true
        alpha = 1f
        frameReadyChanged?.invoke(true)
    }

    private fun startVideo() {
        val uri=source ?: return
        val texture=surfaceTexture ?: return
        if(!ready || navigationPaused || player!=null || !canPlay())return
        val next=MediaPlayer()
        player=next
        runCatching {
            surface=Surface(texture)
            next.setSurface(surface)
            next.setAudioAttributes(audioAttributes)
            next.setVolume(0f, 0f)
            next.setOnVideoSizeChangedListener { _,w,h -> videoWidth=w;videoHeight=h;fit() }
            next.setOnPreparedListener {
                if (player === it) {
                    if (canPlay()) {
                        it.isLooping = true
                        prepared = true
                        if (!navigationPaused) playPrepared(it)
                    } else releasePlayback()
                }
            }
            next.setOnInfoListener { media,what,_ ->
                if(player===media && what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    renderingStarted = true
                    revealFrame()
                }
                false
            }
            next.setOnErrorListener { media,_,_ -> if(player===media)failed();true }
            next.setDataSource(context,uri)
            next.prepareAsync()
        }.onFailure { failed() }
    }

    private fun fit() {
        if(width==0 || height==0 || videoWidth==0 || videoHeight==0)return
        val scale=if(cropToFill)maxOf(width.toFloat()/videoWidth,height.toFloat()/videoHeight)
            else minOf(width.toFloat()/videoWidth,height.toFloat()/videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth*scale/width,videoHeight*scale/height,width/2f,height/2f)
        })
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,w:Int,h:Int) { fit();startVideo() }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,w:Int,h:Int) { fit() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (waitForTextureFrame && player != null && !textureUpdated) {
            textureUpdated = true
            revealFrame()
        }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { releasePlayback();return true }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) startVideo() else releasePlayback()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); observePower() }
    override fun onDetachedFromWindow() { stop();super.onDetachedFromWindow() }
}
