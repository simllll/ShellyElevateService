package com.stretter.shellyelevateservice.dashboard.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import java.io.File

/**
 * Small image loader (no Coil/Glide to keep the APK lean): OkHttp with a disk cache, decoding
 * subsampled to the display size, and a memory LRU. HA-relative URLs (`/local/…`, `/api/…`) are
 * resolved against the HA base URL and get the bearer token.
 */
class ImageLoader(private val hass: HassClient, cacheDir: File) {
    private val client = hass.http.newBuilder()
        .cache(Cache(File(cacheDir, "images"), 20L * 1024 * 1024))
        .build()

    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(url: String, targetWidth: Int): ImageBitmap? = memory.get(key(url, targetWidth))

    /**
     * Loads [url] decoded to roughly [targetWidth] px wide (never upscaled). [bypassCache] skips
     * both caches (for refreshing camera/webcam pictures). Returns null on any error.
     */
    suspend fun load(url: String, targetWidth: Int, bypassCache: Boolean = false): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = key(url, targetWidth)
        if (!bypassCache) memory.get(key)?.let { return@withContext it }
        try {
            val request = hass.request(url).newBuilder()
                .apply { if (bypassCache) cacheControl(CacheControl.FORCE_NETWORK) }
                .build()
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $url")
                    return@withContext null
                }
                response.body.bytes()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            if (targetWidth > 0) while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            bitmap.prepareToDraw()
            bitmap.asImageBitmap().also { memory.put(key, it) }
        } catch (e: Exception) {
            Log.w(TAG, "loading $url failed: ${e.message}")
            null
        }
    }

    private fun key(url: String, width: Int) = "$width|$url"

    private companion object {
        const val TAG = "ImageLoader"
    }
}

/**
 * Shows a remote image. With [keepAspectRatio] the height follows the image (like `<img>` with
 * `width: 100%`); otherwise the image fills the given size with [contentScale].
 * Changing [refreshKey] reloads from the network while the previous image stays visible.
 */
@Composable
fun RemoteImage(
    loader: ImageLoader,
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    keepAspectRatio: Boolean = false,
    alignment: Alignment = Alignment.Center,
    refreshKey: Any? = null,
    contentDescription: String? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    if (url != null && size.width > 0) {
        LaunchedEffect(url, size.width, refreshKey) {
            val fresh = refreshKey != null && bitmap != null
            loader.load(url, size.width, bypassCache = fresh)?.let { bitmap = it }
        }
    }
    val image = bitmap
    val sized = if (keepAspectRatio && image != null) modifier.aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)) else modifier
    Box(sized.onSizeChanged { size = it }) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
