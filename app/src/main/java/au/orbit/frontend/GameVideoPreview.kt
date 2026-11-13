package au.orbit.frontend

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View

/** Local previews. The static artwork remains underneath until a video frame is ready. */
class GameVideoPreview(context: Context, private val soundEnabled: Boolean = true,private val cropToFill:Boolean=false) : TextureView(context), TextureView.SurfaceTextureListener {
    var frameReadyChanged:((Boolean)->Unit)?=null
    var playbackFailed:(()->Unit)?=null
    var playbackInterrupted:(()->Unit)?=null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var source: Uri? = null
    private var pending: Runnable? = null
    private var ready = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var playSound = soundEnabled
    private var audioFocused = false
    private val audio=context.getSystemService(AudioManager::class.java)
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

    fun show(uri: Uri?, delay: Long, withSound: Boolean = soundEnabled) {
        stop()
        source=uri
        playSound=withSound
        if(uri==null)return
        pending=Runnable { ready=true; startVideo() }.also { postDelayed(it,delay) }
    }

    fun stop() {
        pending?.let(::removeCallbacks); pending=null; source=null;ready=false;alpha=0f
        frameReadyChanged?.invoke(false)
        player?.release();player=null
        surface?.release();surface=null
        if(audioFocused) {
            audioFocused=false
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun failed() { stop();playbackFailed?.invoke() }

    private fun startVideo() {
        val uri=source ?: return
        val texture=surfaceTexture ?: return
        if(!ready || player!=null || !isShown)return
        val next=MediaPlayer()
        player=next
        runCatching {
            surface=Surface(texture)
            next.setSurface(surface)
            next.setAudioAttributes(audioAttributes)
            audioFocused=playSound && audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val volume=if(audioFocused)1f else 0f
            next.setVolume(volume,volume)
            next.setOnVideoSizeChangedListener { _,w,h -> videoWidth=w;videoHeight=h;fit() }
            next.setOnPreparedListener { if(player===it) { it.isLooping=true;it.start() } }
            next.setOnInfoListener { media,what,_ ->
                if(player===media && what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    alpha=1f;frameReadyChanged?.invoke(true)
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
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { stop();return true }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility!=View.VISIBLE)stop()
    }
    override fun onDetachedFromWindow() { stop();super.onDetachedFromWindow() }
}
