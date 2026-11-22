package com.dronelink.app.video

import android.graphics.Bitmap
import android.view.Surface
import android.view.TextureView
import com.dronelink.app.MainActivity
import com.dronelink.app.debug.DebugLog
import java.io.ByteArrayOutputStream
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * Minimal player surface used by [RtspClient]. Real implementation wraps
 * IjkMediaPlayer (the same libijkplayer stack the vendor's official app
 * ships); tests substitute a fake.
 */
internal interface IjkBridge {
    fun setOptions()
    fun setDataSource(url: String)
    fun attachSurface(texture: TextureView)
    fun prepareAsync()
    fun start()
    /** Current decoded frame as JPEG bytes, or null if none yet. */
    fun captureJpeg(): ByteArray?
    var onPrepared: (() -> Unit)?
    var onError: ((String) -> Unit)?
    fun release()
}

/** IjkMediaPlayer-backed bridge (vendor-proven decoder stack). */
internal class IjkBridgeImpl : IjkBridge {

    private val player = IjkMediaPlayer()
    private val jpegQuality = 85

    init {
        player.setOnPreparedListener { onPrepared?.invoke() }
        player.setOnErrorListener { _, what, extra ->
            onError?.invoke("ijk error what=$what extra=$extra")
            true
        }
    }

    override fun setOptions() {
        with(player) {
            // Mirror the vendor RC FPV app's ijk options (from its decompiled
            // SocketClient/Application) so we negotiate the same stream.
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "readtimeout", 5000000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "preferred-image-type", 0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "image-quality-min", 2L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "image-quality-max", 20L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "preferred-video-type", 2L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "video-need-transcoding", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mjpeg-pix-fmt", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "video-quality-min", 2L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "video-quality-max", 20L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "x264-option-preset", 0L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "x264-option-tune", 5L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "x264-option-profile", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "x264-params", "crf=23")
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "auto-drop-record-frame", 3L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "err_detect", "explode")
            // Vendor ijk default is TCP interleaved (they never set
            // rtsp_transport). With the process pinned to the drone net, the
            // control+interleaved video ride one TCP socket through the
            // secondary network. UDP was a guess from the E88 reference and
            // delivered data but never completed prepare.
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            // Probe windows: this camera's stream is MJPEG-over-RTP with
            // nonstandard timing; the defaults can leave prepareAsync hanging.
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 5000000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 33554432L)
        }
    }

    override fun setDataSource(url: String) {
        player.setDataSource(url)
    }

    override fun attachSurface(texture: TextureView) {
        val st = texture.surfaceTexture ?: return
        player.setSurface(Surface(st))
    }

    override fun prepareAsync() {
        player.prepareAsync()
    }

    override fun start() {
        player.start()
    }

    override fun captureJpeg(): ByteArray? {
        val bmp = MainActivity.videoTexture?.bitmap ?: return null
        if (bmp.isRecycled) return null
        val out = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)) return null
        return out.toByteArray()
    }

    override var onPrepared: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override fun release() {
        runCatching { player.stop() }
        runCatching { player.release() }
    }
}

/** Live video feed: latest complete JPEG frame + a version counter to wait on. */
interface VideoFeed {
    val frameVersion: Long
    val latestFrame: ByteArray?
    /** Block until the frame version changes or [timeoutMs] elapses. */
    fun awaitFrame(seenVersion: Long, timeoutMs: Long): Long
}

/**
 * RTSP video feed for the drone's MJPEG webcam (192.168.1.1:7070/webcam).
 *
 * Decoding is delegated entirely to ijkplayer (vendor-proven: the official
 * RC FPV app renders this camera with the same libijkplayer/libijkffmpeg
 * stack). Frames are captured from a hidden 1x1 TextureView in MainActivity
 * and re-encoded as JPEG for the HTTP/MJPEG proxy — no custom RTSP/RTP
 * handling or reassembly in this app.
 */
internal class RtspClient(
    private val host: String = DroneVideo.DRONE_IP,
    private val rtspPort: Int = 7070,
    private val path: String = "/webcam",
    private val tag: String = "rtsp",
    private val bridge: IjkBridge = IjkBridgeImpl(),
    /** Drone network the RTSP sockets must ride (app-scoped secondary join). */
    private val connectivityManager: android.net.ConnectivityManager? = null,
    private val droneNetwork: android.net.Network? = null,
) : VideoFeed {

    private val url get() = "rtsp://$host:$rtspPort$path"

    @Volatile
    var running = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var sessionId: String? = url
        private set

    /** JPEG frames successfully captured + re-encoded. */
    @Volatile
    var framesDecoded: Long = 0
        private set

    /** Total JPEG bytes published. */
    @Volatile
    var framesBytes: Long = 0
        private set

    @Volatile
    private var _frameVersion = 0L

    @Volatile
    private var _latestFrame: ByteArray? = null

    /** Previous process-default network to restore on stop (null = cellular). */
    @Volatile
    private var _prevProcessNetwork: android.net.Network? = null

    private val lock = Object()
    private var grabber: Thread? = null

    override val frameVersion: Long get() = _frameVersion
    override val latestFrame: ByteArray? get() = _latestFrame

    override fun awaitFrame(seenVersion: Long, timeoutMs: Long): Long {
        if (_frameVersion != seenVersion) return _frameVersion
        synchronized(lock) {
            if (_frameVersion != seenVersion) return _frameVersion
            try {
                lock.wait(timeoutMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return _frameVersion
    }

    /** Start the ijk player; frames begin flowing once prepared. */
    fun start() {
        if (running) return
        error = null
        try {
            // ijk's sockets are created inside the native ffmpeg layer, so
            // they follow the process default network. The drone is a
            // secondary app-scoped network; pin the process to it for the
            // whole play session so RTSP/RTTP rides the right route.
            // (The heartbeat already binds per-socket; ijk cannot.)
            val prevNet = connectivityManager?.let { cm ->
                try {
                    cm.boundNetworkForProcess
                } catch (_: Throwable) {
                    null
                }
            }
            if (connectivityManager != null && droneNetwork != null) {
                connectivityManager.bindProcessToNetwork(droneNetwork)
            }
            bridge.setOptions()
            bridge.setDataSource(url)
            // Attach the render surface synchronously BEFORE prepare — the
            // vendor's IjkVideoView attaches its surface at start(); without
            // it some forks never leave the prepared wait on live streams.
            MainActivity.videoTexture?.let { runCatching { bridge.attachSurface(it) } }
            bridge.onPrepared = {
                synchronized(lock) {
                    running = true
                    lock.notifyAll()
                }
                DebugLog.log(tag, "prepared — starting playback")
                bridge.start()
            }
            bridge.onError = { msg ->
                error = "ijk: $msg"
                DebugLog.log(tag, error!!)
                stop()
            }
            bridge.prepareAsync()
            grabber = Thread { grabLoop() }.apply { isDaemon = true; start() }
            // keep the previous binding to restore on stop
            _prevProcessNetwork = prevNet
        } catch (e: Exception) {
            error = "ijk start failed: ${e.message}"
            DebugLog.log(tag, error!!)
            releaseQuiet()
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
        // restore the pre-play process-default network (cellular)
        if (connectivityManager != null) {
            runCatching { connectivityManager?.bindProcessToNetwork(_prevProcessNetwork) }
            _prevProcessNetwork = null
        }
        releaseQuiet()
    }

    private fun grabLoop() {
        while (true) {
            // The hidden TextureView may not exist yet (Activity cold start);
            // retry attaching until it sticks.
            val texture = MainActivity.videoTexture
            if (texture != null) {
                runCatching { bridge.attachSurface(texture) }
            }
            val jpeg = try {
                bridge.captureJpeg()
            } catch (e: Exception) {
                null
            }
            if (jpeg != null && jpeg.isNotEmpty()) {
                synchronized(lock) {
                    _latestFrame = jpeg
                    _frameVersion++
                    framesDecoded++
                    framesBytes += jpeg.size
                    lock.notifyAll()
                }
                DebugLog.log(tag, "frame #$framesDecoded ${jpeg.size}B")
            }
            val stillRunning = synchronized(lock) { running }
            if (!stillRunning) break
            try {
                Thread.sleep(125)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun releaseQuiet() {
        runCatching { bridge.onPrepared = null }
        runCatching { bridge.onError = null }
        runCatching { bridge.release() }
        synchronized(lock) { lock.notifyAll() }
    }

    object DroneVideo {
        const val DRONE_IP = "192.168.1.1"
        const val RTSP_PORT = 7070
        const val PATH = "/webcam"
        const val HTTP_PORT = 8080
    }
}
