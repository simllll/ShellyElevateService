package io.github.simllll.panelelevate.dashboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import io.github.simllll.panelelevate.dashboard.lovelace.LocalViewActive
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * A muted video that starts by itself and loops (`MediaPlayer` on a `TextureView`, so it clips
 * to rounded cards). Letterboxed on black like a browser's video document (`object-fit: contain`).
 *
 * The player exists only while this composable is in the composition and the activity is
 * started: leaving the screen, closing the pop-up or stopping the activity releases the decoder.
 * Playback errors (network, HA restart) are retried every 30 s.
 */
@Composable
fun LoopingVideo(url: String, headers: Map<String, String>, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(modifier.background(Color.Black)) {
        if (started && LocalViewActive.current) {
            key(url, headers) {
                AndroidView(
                    factory = { context -> VideoTextureView(context, url, headers) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.release() },
                )
            }
        }
    }
}

@SuppressLint("ViewConstructor")
private class VideoTextureView(
    context: Context,
    private val url: String,
    private val headers: Map<String, String>,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var released = false
    private val retry = Runnable { if (!released && surface != null) startPlayer() }

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
        startPlayer()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fit()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        stopPlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun startPlayer() {
        stopPlayer()
        val target = surface ?: return
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setSurface(target)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                fit()
            }
            mp.setOnPreparedListener { if (player === it) it.start() }
            mp.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "playback error $what/$extra for $url")
                if (player === failed) scheduleRetry()
                true
            }
            mp.setDataSource(context, Uri.parse(url), headers)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "cannot play $url: ${e.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        stopPlayer()
        removeCallbacks(retry)
        if (!released) postDelayed(retry, RETRY_MS)
    }

    private fun stopPlayer() {
        val mp = player ?: return
        player = null
        try {
            mp.reset()
        } catch (_: IllegalStateException) {
        }
        mp.release()
    }

    /** Scales the stretched texture back to the video's aspect ratio, centered. */
    private fun fit() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(w / videoWidth, h / videoHeight)
        val matrix = Matrix()
        matrix.setScale(videoWidth * scale / w, videoHeight * scale / h, w / 2f, h / 2f)
        setTransform(matrix)
    }

    fun release() {
        released = true
        removeCallbacks(retry)
        stopPlayer()
        surface?.release()
        surface = null
    }

    private companion object {
        const val TAG = "LoopingVideo"
        const val RETRY_MS = 30_000L
    }
}
