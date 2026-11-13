package com.stretter.shellyelevateservice.dashboard.cards

import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.ActionTrigger
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.camera.CameraPicture
import com.stretter.shellyelevateservice.dashboard.camera.FitMode
import com.stretter.shellyelevateservice.dashboard.image.RemoteImage
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.double
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * `custom:refreshable-picture-card`: an image from `url` (or an entity's state/`attribute`),
 * reloaded every `refresh_interval` seconds (default 30) while the previous image stays visible.
 * The `<img>` has its own ha-card style border and radius; without `noMargin` it gets a 5 % margin.
 *
 * Native extension: `camera_view: live` with a camera `entity` (or a `url` of the form
 * `/api/camera_proxy/camera.x`) plays the camera's WebRTC stream instead; `url` (or the
 * camera's picture) is the still shown until the first frame and when WebRTC fails.
 * `camera_view: tap` shows the refreshing still with a "Tippen für Live" hint and switches to
 * the live stream on this device only when tapped (live view can answer an intercom call).
 */
class RefreshablePictureCard(private val config: JsonObject) : CardRenderer {
    private val url = config.string("url")?.takeIf { it.isNotBlank() }
    private val entityId = config.string("entity")?.takeIf { it.isNotBlank() }
    private val attribute = config.string("attribute")?.takeIf { it.isNotBlank() }
    private val intervalMs = ((config.double("refresh_interval")?.takeIf { it > 0 } ?: 30.0) * 1000).toLong()
    private val noMargin = config.bool("noMargin") == true
    private val title = config.string("title")
    private val fallback = config.string("fallback_image")?.takeIf { it.isNotBlank() }
    private val liveCamera = if (config.string("camera_view") == "live") cameraEntityOf(entityId, url) else null
    private val tapCamera = if (config.string("camera_view") == "tap") cameraEntityOf(entityId, url) else null

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (url == null && entityId == null) {
            WarningCard("You need to define either a url or an entity").Content(ctx, modifier)
            return
        }
        if (liveCamera != null) {
            LiveContent(ctx, modifier, liveCamera)
            return
        }
        var tappedLive by remember { mutableStateOf(false) }
        if (tapCamera != null && tappedLive) {
            LiveContent(ctx, modifier, tapCamera)
            return
        }
        val entity = ctx.entity(entityId)
        val source = url ?: entity?.let { e -> if (attribute != null) e.attrString(attribute) else e.state } ?: fallback
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(intervalMs) {
            while (true) {
                delay(intervalMs)
                refresh++
            }
        }
        val d = HaTheme.dimens
        val shape = RoundedCornerShape(d.radiusLg)
        val tap = ActionHandler.hasAction(config, ActionTrigger.TAP)
        val cardModifier = when {
            tapCamera != null -> Modifier.clickable { tappedLive = true }
            tap -> Modifier.haActions(ctx, config)
            else -> Modifier
        }
        HaCard(modifier.fillMaxWidth().then(cardModifier), clip = true) {
            Column(Modifier.fillMaxWidth()) {
                if (!title.isNullOrEmpty()) CardHeader(title)
                Box(contentAlignment = Alignment.BottomCenter) {
                RemoteImage(
                    ctx.images,
                    source,
                    Modifier
                        .fillMaxWidth()
                        .then(if (noMargin) Modifier else Modifier.percentMargin(0.05f))
                        .clip(shape)
                        .border(d.cardBorderWidth, HaTheme.colors.divider, shape),
                    contentScale = ContentScale.FillWidth,
                    keepAspectRatio = true,
                    refreshKey = refresh.takeIf { it > 0 },
                )
                if (tapCamera != null) {
                    Row(
                        Modifier
                            .padding(bottom = 16.dp)
                            .background(Color(0x99000000), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MdiIcon("mdi:video", Color.White, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        BasicText("Tippen für Live", style = HaTheme.typography.style(HaTheme.typography.sizeM, FontWeight.Medium, HaTheme.typography.lineHeightCondensed, color = Color.White))
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun LiveContent(ctx: CardContext, modifier: Modifier, camera: String) {
        val d = HaTheme.dimens
        val shape = RoundedCornerShape(d.radiusLg)
        val tap = ActionHandler.hasAction(config, ActionTrigger.TAP)
        HaCard(modifier.fillMaxWidth().then(if (tap) Modifier.haActions(ctx, config) else Modifier), clip = true) {
            Column(Modifier.fillMaxWidth()) {
                if (!title.isNullOrEmpty()) CardHeader(title)
                CameraPicture(
                    ctx,
                    camera,
                    Modifier
                        .then(if (noMargin) Modifier else Modifier.percentMargin(0.05f))
                        .clip(shape)
                        .border(d.cardBorderWidth, HaTheme.colors.divider, shape),
                    stillUrl = url,
                    fitMode = FitMode.CONTAIN,
                )
            }
        }
    }
}

/** The camera to stream: a `camera.` [entityId], or the entity of a `/api/camera_proxy/camera.x` [url]. */
internal fun cameraEntityOf(entityId: String?, url: String?): String? {
    if (entityId != null && entityId.startsWith("camera.")) return entityId
    val match = url?.let { CAMERA_PROXY.find(it) } ?: return null
    return match.groupValues[1]
}

private val CAMERA_PROXY = Regex("/api/camera_proxy(?:_stream)?/(camera\\.[a-z0-9_]+)")

/** CSS `margin: 5%`: every side is a fraction of the containing block's width. */
private fun Modifier.percentMargin(fraction: Float): Modifier = layout { measurable, constraints ->
    val margin = (constraints.maxWidth * fraction).roundToInt()
    val inner = constraints.copy(
        minWidth = (constraints.minWidth - 2 * margin).coerceAtLeast(0),
        maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
        minHeight = (constraints.minHeight - 2 * margin).coerceAtLeast(0),
        maxHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - 2 * margin).coerceAtLeast(0) else constraints.maxHeight,
    )
    val placeable = measurable.measure(inner)
    layout(placeable.width + 2 * margin, placeable.height + 2 * margin) { placeable.place(margin, margin) }
}
