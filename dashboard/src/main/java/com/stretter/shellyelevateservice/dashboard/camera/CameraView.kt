package com.stretter.shellyelevateservice.dashboard.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.image.RemoteImage
import com.stretter.shellyelevateservice.dashboard.lovelace.LocalViewActive
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `fit_mode` of `hui-image`: `cover` crops, `contain` letterboxes, `fill` stretches. */
enum class FitMode {
    COVER, CONTAIN, FILL;

    companion object {
        fun parse(value: String?): FitMode? = when (value) {
            "cover" -> COVER
            "contain" -> CONTAIN
            "fill" -> FILL
            else -> null
        }
    }
}

/** Refresh interval of camera stills (`UPDATE_INTERVAL` of `hui-image`). */
const val CAMERA_STILL_REFRESH_MS = 10_000L

/** `aspect_ratio` like `parseAspectRatio` of the frontend: `16:9`, `16x9`, `1.78` or `56%` (h/w). */
fun parseAspectRatio(value: String?): Float? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.endsWith("%")) return text.dropLast(1).toFloatOrNull()?.takeIf { it > 0 }?.let { 100f / it }
    val parts = text.split(':', 'x', 'X', '/')
    if (parts.size == 2) {
        val w = parts[0].trim().toFloatOrNull() ?: return null
        val h = parts[1].trim().toFloatOrNull() ?: return null
        return if (w > 0 && h > 0) w / h else null
    }
    return text.toFloatOrNull()?.takeIf { it > 0 }
}

/**
 * A camera picture: the live WebRTC stream when [live] (still image until the first frame,
 * refreshing stills when WebRTC fails), otherwise stills refreshed every 10 s.
 *
 * The stream runs only while this is composed, its view active and the activity started; it
 * is shared with other views of the same camera ([LiveStreamHub]). Without [aspectRatio] the
 * box takes the video's aspect ratio (16:9 until known), like the frontend's `<video>`.
 */
@Composable
fun CameraPicture(
    ctx: CardContext,
    entityId: String,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    stillUrl: String? = null,
    aspectRatio: Float? = null,
    fitMode: FitMode? = null,
    fillHeight: Boolean = false,
) {
    val entity = ctx.entity(entityId)
    val still = stillUrl ?: entity?.entityPicture
    val active = rememberActive()
    var refresh by remember { mutableIntStateOf(0) }

    val stream = if (live && active) rememberLiveStream(ctx, entityId) else null
    val state = stream?.state?.collectAsState()?.value
    val videoSize = stream?.videoSize?.collectAsState()?.value
    val engineReady = stream?.engineReady?.collectAsState()?.value == true
    val playing = state == LiveState.Playing
    val refreshing = active && (!live || state is LiveState.Failed)
    if (refreshing) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(CAMERA_STILL_REFRESH_MS)
                refresh++
            }
        }
    }

    val ratio = aspectRatio ?: videoSize?.let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) } ?: (16f / 9f)
    val box = when {
        fillHeight -> modifier.fillMaxSize()
        else -> modifier.fillMaxWidth().aspectRatio(ratio)
    }
    val scale = when (fitMode) {
        FitMode.CONTAIN -> ContentScale.Fit
        FitMode.FILL -> ContentScale.FillBounds
        else -> ContentScale.Crop
    }
    Box(box.background(Color.Black)) {
        if (!playing) {
            RemoteImage(ctx.images, still, Modifier.fillMaxSize(), contentScale = scale, refreshKey = refresh.takeIf { it > 0 })
        }
        if (stream != null && engineReady && state !is LiveState.Failed) {
            VideoSurface(stream, fitMode ?: if (aspectRatio != null || fillHeight) FitMode.COVER else FitMode.CONTAIN)
            if (playing) MuteButton(stream)
        }
    }
}

@Composable
private fun BoxScope.VideoSurface(stream: LiveStream, fit: FitMode) {
    val videoSize by stream.videoSize.collectAsState()
    val contentRatio = videoSize?.let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) }
    val slot = if (fit == FitMode.CONTAIN && contentRatio != null) {
        Modifier.align(Alignment.Center).aspectRatio(contentRatio)
    } else Modifier.fillMaxSize()
    AndroidView(
        factory = { context -> VideoTextureView(context).also { stream.addSink(it) } },
        modifier = slot,
        update = { it.cover = fit == FitMode.COVER },
        onRelease = { view ->
            stream.removeSink(view)
            view.release()
        },
    )
}

@Composable
private fun BoxScope.MuteButton(stream: LiveStream) {
    val hasAudio by stream.hasAudio.collectAsState()
    if (!hasAudio) return
    val muted by stream.muted.collectAsState()
    Box(
        Modifier
            .align(Alignment.TopEnd) // the bottom belongs to picture-entity's name/state footer
            .padding(8.dp)
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { stream.muted.value = !muted },
        contentAlignment = Alignment.Center,
    ) {
        MdiIcon(if (muted) "mdi:volume-off" else "mdi:volume-high", Color.White, size = 20.dp)
    }
}

@Composable
private fun rememberLiveStream(ctx: CardContext, entityId: String): LiveStream? {
    val context = LocalContext.current
    val hub = remember(ctx.hass) { LiveStreamHub.get(context, ctx.hass) }
    var stream by remember { mutableStateOf<LiveStream?>(null) }
    DisposableEffect(hub, entityId) {
        val acquired = hub.acquire(entityId)
        stream = acquired
        onDispose {
            stream = null
            hub.release(acquired)
        }
    }
    return stream
}

/** In the active view and the activity started (stopping it, e.g. screen off, ends streams). */
@Composable
private fun rememberActive(): Boolean {
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
    return started && LocalViewActive.current
}

/**
 * Draws WebRTC frames on a `TextureView` (clips to rounded cards, unlike a `SurfaceView`) with
 * libwebrtc's `EglRenderer` on the shared EGL context, so hardware-decoded frames stay on the GPU.
 * Stretches the frame to the view; [cover] crops it to the view's aspect ratio instead.
 */
@SuppressLint("ViewConstructor")
private class VideoTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer("camera")
    private var released = false

    var cover = false
        set(value) {
            field = value
            applyLayout()
        }

    init {
        renderer.init(WebRtcEngine.eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onFrame(frame: VideoFrame) {
        if (!released) renderer.onFrame(frame)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        applyLayout()
        renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = applyLayout()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val done = CountDownLatch(1)
        renderer.releaseEglSurface { done.countDown() }
        done.await(1, TimeUnit.SECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun applyLayout() {
        renderer.setLayoutAspectRatio(if (cover && width > 0 && height > 0) width.toFloat() / height else 0f)
    }

    fun release() {
        released = true
        renderer.release()
    }
}
