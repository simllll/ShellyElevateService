package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.LoopingVideo
import kotlinx.serialization.json.JsonObject

/**
 * `hui-iframe-card`. There is no WebView: a URL pointing at a video file (`/local/screensaver.mp4`)
 * is played natively ([LoopingVideo]: looped, muted, autoplay, released when the card leaves the
 * screen or the activity stops); any other URL shows a placeholder with the address.
 *
 * Size: an explicit `aspect_ratio` (`100%`, `16:9`, `1.78`) always sets the height, also in grid
 * layouts such as Bubble pop-ups (the frontend ignores the ratio in a grid and uses 4 rows; the
 * entrance screensaver pop-up is configured for a square video). Without it the card fills its
 * grid cell (4 rows) or is 50% as tall as wide in stacks and masonry columns.
 */
class IframeCard(private val config: JsonObject) : CardRenderer {
    private val url = config.string("url").orEmpty()
    private val title = config.string("title")
    private val aspectRatio = config.string("aspect_ratio")
    private val ratio: Float? = if (aspectRatio == null) 0.5f else heightRatio(aspectRatio)
    private val video = isVideoUrl(url)

    override fun gridOptions() =
        GridOptions(columns = GridOptions.FULL, rows = if (aspectRatio == null) 4 else null, minColumns = 3, minRows = 2)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val hideBackground = config.bool("hide_background") == true
        val grid = ctx.layout == CardLayout.GRID && aspectRatio == null
        HaCard(
            modifier.fillMaxWidth(),
            background = if (hideBackground) Color.Transparent else HaTheme.colors.cardBackground,
            border = !hideBackground,
            clip = true,
        ) {
            Column(if (grid) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                if (!title.isNullOrEmpty()) CardHeader(title)
                val frame = when {
                    grid -> Modifier.fillMaxWidth().weight(1f)
                    ratio == null -> Modifier.fillMaxWidth().height(0.dp)
                    else -> Modifier.fillMaxWidth().aspectRatio(1f / ratio)
                }
                if (video) {
                    val request = remember(url) { ctx.hass.request(url) }
                    val headers = remember(request) { request.headers.toMap() }
                    LoopingVideo(request.url.toString(), headers, frame)
                } else {
                    UnsupportedFrame(url, frame)
                }
            }
        }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov", "ts")

        /** True if [url]'s path ends in a video file extension (query and fragment ignored). */
        fun isVideoUrl(url: String): Boolean {
            val path = url.substringBefore('#').substringBefore('?')
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }

        /**
         * `parseAspectRatio` as height ÷ width: `"100%"` → 1, `"16:9"`/`"16x9"` → 0.5625, `"1.78"` →
         * 1 / 1.78. Null for anything unparsable (the frontend then sets no padding: zero height).
         */
        fun heightRatio(input: String): Float? {
            val s = input.trim()
            if (s.isEmpty()) return null
            val (w, h) = if (s.endsWith("%")) {
                100f to (parseFloat(s.dropLast(1)) ?: return null)
            } else {
                val parts = s.replaceFirst(':', 'x').split('x')
                if (parts.size == 1) (parseFloat(parts[0]) ?: return null) to 1f
                else (parseFloat(parts[0]) ?: return null) to (parseFloat(parts[1]) ?: return null)
            }
            return if (w > 0f && h > 0f) h / w else null
        }

        /** JS `parseFloat`: the longest numeric prefix. */
        private fun parseFloat(s: String): Float? =
            Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").find(s)?.value?.trim()?.toFloatOrNull()
    }
}

/** What an iframe would show: a neutral frame with the URL (no WebView on this device). */
@Composable
private fun UnsupportedFrame(url: String, modifier: Modifier) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    Box(modifier.background(c.secondaryBackground), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            MdiIcon("mdi:web", c.secondaryText)
            BasicText(
                url,
                style = t.style(t.sizeS, color = c.secondaryText).copy(textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
