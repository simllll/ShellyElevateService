package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simllll.panelelevate.dashboard.actions.ActionHandler
import io.github.simllll.panelelevate.dashboard.actions.ActionTrigger
import io.github.simllll.panelelevate.dashboard.actions.haActions
import io.github.simllll.panelelevate.dashboard.camera.CAMERA_STILL_REFRESH_MS
import io.github.simllll.panelelevate.dashboard.camera.CameraPicture
import io.github.simllll.panelelevate.dashboard.camera.FitMode
import io.github.simllll.panelelevate.dashboard.camera.parseAspectRatio
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.TOGGLE_DOMAINS
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.image.RemoteImage
import io.github.simllll.panelelevate.dashboard.json.EmptyJsonObject
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.int
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.json.with
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `--ha-picture-card-background-color` / text color. */
private val PictureBarBackground = Color(0x4D000000)
private val PictureText = Color.White

/**
 * The picture area shared by `picture-entity` and `picture-glance` (`hui-image`): a camera
 * (`camera_image`, live with `camera_view: live`), a state-dependent `state_image`, or `image`.
 * With numeric grid rows the picture fills the card instead of keeping its aspect ratio.
 */
@Composable
internal fun HuiImage(
    ctx: CardContext,
    config: JsonObject,
    cameraEntity: String?,
    image: String?,
    entity: EntityState?,
    modifier: Modifier = Modifier,
) {
    val fillHeight = ctx.layout == CardLayout.GRID && config.obj("grid_options")?.int("rows") != null
    val ratio = if (fillHeight) null else parseAspectRatio(config.string("aspect_ratio"))
    val fit = FitMode.parse(config.string("fit_mode"))
    if (cameraEntity != null) {
        CameraPicture(
            ctx,
            cameraEntity,
            modifier,
            live = config.string("camera_view") == "live",
            aspectRatio = ratio,
            fitMode = fit,
            fillHeight = fillHeight,
        )
        return
    }
    val stateImage = config.obj("state_image")?.let { images -> entity?.let { images[it.state].asString() } }
    val url = stateImage ?: image
    val scale = when (fit) {
        FitMode.CONTAIN -> ContentScale.Fit
        FitMode.FILL -> ContentScale.FillBounds
        else -> ContentScale.Crop
    }
    when {
        fillHeight -> RemoteImage(ctx.images, url, modifier.fillMaxSize(), contentScale = scale)
        ratio != null -> RemoteImage(ctx.images, url, modifier.fillMaxWidth().aspectRatio(ratio), contentScale = scale)
        else -> RemoteImage(ctx.images, url, modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth, keepAspectRatio = true)
    }
}

/**
 * `picture-entity`: an entity's picture with a name/state footer. Camera entities show their
 * stills (refreshed every 10 s) or, with `camera_view: live`, the WebRTC stream. Tap defaults to
 * more-info.
 */
class PictureEntityCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")?.takeIf { it.isNotBlank() }
    private val domain = entityId?.substringBefore('.')
    private val actions = if (config.obj("tap_action") != null) config else config.with("tap_action", jsonOf("action" to "more-info"))

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        if (entityId == null) {
            WarningCard("Entity must be specified").Content(ctx, modifier)
            return
        }
        val entity = ctx.entity(entityId)
        if (entity == null) {
            WarningCard("Entity not available: $entityId").Content(ctx, modifier)
            return
        }
        val showName = config.bool("show_name") != false
        val showState = config.bool("show_state") != false
        var image: String? = null
        if (config.bool("show_entity_picture") == true) image = entity.attrString("entity_picture_local") ?: entity.entityPicture
        if (image == null) image = config.imageSource()
        if (image == null && (domain == "image" || domain == "person")) image = entity.entityPicture
        val camera = if (domain == "camera") entityId else config.string("camera_image")?.takeIf { it.isNotBlank() }

        HaCard(modifier.fillMaxWidth().heightIn(min = 75.dp), clip = true) {
            Box(Modifier.fillMaxWidth().haActions(ctx, actions)) {
                HuiImage(ctx, config, camera, image, entity)
            }
            if (showName || showState) {
                val name = config.string("name") ?: ctx.entityDisplayName(entity, entityId)
                val state = ctx.formatter.formatState(entity, ctx.now())
                val style = HaTheme.typography.style(HaTheme.typography.sizeL, lineHeight = 1f, color = PictureText)
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(PictureBarBackground).padding(16.dp),
                    horizontalArrangement = if (showName && showState) Arrangement.SpaceBetween else Arrangement.Center,
                ) {
                    if (showName) BasicText(name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (showState) BasicText(state, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * `picture-glance`: a picture (image, `image_entity`, `camera_image` with `camera_view`) with a
 * bar of entity icons: toggleable domains on the right (tap toggles), the rest on the left (tap
 * opens more-info, as does hold); `force_dialog` puts all on the left.
 */
class PictureGlanceCard(private val config: JsonObject) : CardRenderer {
    private val entities: List<JsonObject> = (config["entities"] as? JsonArray).orEmpty().mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.asString()?.let { jsonOf("entity" to it) }
            else -> item.asObject()?.takeIf { it.string("entity") != null }
        }
    }
    private val forceDialog = config.bool("force_dialog") == true
    private val dialog = entities.filter { forceDialog || it.string("entity")!!.substringBefore('.') !in TOGGLE_DOMAINS }
    private val toggle = entities.filter { it !in dialog }
    private val camera = config.string("camera_image")?.takeIf { it.isNotBlank() }
    private val imageEntityId = config.string("image_entity")?.takeIf { it.isNotBlank() }
    // Without tap_action: more-info of entity / camera_image / image_entity (ActionHandler default).
    private val hasTap = ActionHandler.hasAction(config, ActionTrigger.TAP) ||
        (config.obj("tap_action") == null && (camera != null || imageEntityId != null || config.string("entity") != null))

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(config.string("entity"))
        val imageEntity = ctx.entity(imageEntityId)
        val image = imageEntity?.entityPicture ?: config.imageSource()
        HaCard(modifier.fillMaxWidth().heightIn(min = 48.dp), clip = true) {
            Box(Modifier.fillMaxWidth().then(if (hasTap) Modifier.haActions(ctx, config) else Modifier)) {
                HuiImage(ctx, config, camera, image, entity)
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(PictureBarBackground).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                config.string("title")?.takeIf { it.isNotEmpty() }?.let { title ->
                    BasicText(
                        title,
                        style = HaTheme.typography.style(HaTheme.typography.sizeL, FontWeight.Medium, HaTheme.typography.lineHeightExpanded, color = PictureText),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    )
                }
                Row { dialog.forEach { GlanceEntity(ctx, it, dialog = true) } }
                Row { toggle.forEach { GlanceEntity(ctx, it, dialog = false) } }
            }
        }
    }

    @Composable
    private fun GlanceEntity(ctx: CardContext, conf: JsonObject, dialog: Boolean) {
        val entityId = conf.string("entity") ?: return
        val state = ctx.entity(entityId) ?: return
        val actions = remember(conf, dialog) {
            var c = conf
            if (c.obj("tap_action") == null) c = c.with("tap_action", jsonOf("action" to if (dialog) "more-info" else "toggle"))
            if (c.obj("hold_action") == null) c = c.with("hold_action", jsonOf("action" to "more-info"))
            c
        }
        val color = if (state.isActive()) PictureText else Color(0xFFA9A9A9)
        val showState = config.bool("show_state") == true || conf.bool("show_state") == true
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).haActions(ctx, actions, entityIdSubstitution = entityId), contentAlignment = Alignment.Center) {
                MdiIcon(ctx.icons.icon(state, conf.string("icon")), color)
            }
            val text = if (!showState) "" else conf.string("attribute")?.let { attr ->
                "${conf.string("prefix").orEmpty()}${state.attrString(attr).orEmpty()}${conf.string("suffix").orEmpty()}"
            } ?: ctx.formatter.formatState(state, ctx.now())
            BasicText(
                text,
                style = HaTheme.typography.style(HaTheme.typography.sizeS, lineHeight = 1f, color = PictureText).copy(textAlign = TextAlign.Center, lineHeight = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `image` as a URL or a media-source object with a resolved `media_content_id`. */
private fun JsonObject.imageSource(): String? =
    (this["image"] as? JsonPrimitive)?.asString()?.takeIf { it.isNotBlank() }
        ?: obj("image")?.string("media_content_id")?.takeIf { it.startsWith("/") || it.startsWith("http") }
