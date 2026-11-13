package io.github.simllll.panelelevate.dashboard.cards.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simllll.panelelevate.dashboard.actions.haActions
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.GridOptions
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.cards.now
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.image.RemoteImage
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.json.with
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.PressIndication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * Bubble Card `media-player`: a 56px pill with the cover art (or icon) in the round icon
 * container, title/artist while media is loaded (name/state otherwise) and the buttons Bubble
 * shows in this order: power, previous, next, volume, play/pause (accent filled). Buttons appear
 * only when the player supports them; when the player is off only power remains. `hide.*`
 * (`power_button`, `previous_button`, `next_button`, `volume_button`, `play_pause_button`)
 * removes buttons. The volume button swaps the row for mute, a volume slider and close, like
 * Bubble. Tapping the card or icon opens more-info (configurable via `button_action`/`tap_action`).
 */
class BubbleMediaPlayerCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    private val hide = config.obj("hide")
    private val buttonActions = (config.obj("button_action") ?: config).with("entity", config["entity"]).let {
        if (it.obj("tap_action") != null) it else it.with("tap_action", jsonOf("action" to "more-info"))
    }
    private val iconActions = if (config.obj("tap_action") != null) config else config.with("tap_action", jsonOf("action" to "more-info"))

    override fun gridOptions() = GridOptions(columns = 12)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val c = HaTheme.colors
        val entity = ctx.entity(entityId)
        val height = bubbleHeight(config)
        var volumeOpen by remember { mutableStateOf(false) }
        Box(
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(BubbleColors.main)
                .haActions(ctx, buttonActions, defaultTap = buttonActions.obj("tap_action"))
                .then(if (entity?.isUnavailable == true) Modifier.alpha(0.5f) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (entity == null) return@Box
            if (volumeOpen) {
                VolumeRow(ctx, entity, onClose = { volumeOpen = false })
                return@Box
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (config.bool("show_icon") != false) {
                    BubbleIconContainer(
                        if (height == 56.dp) 42.dp else 38.dp,
                        Modifier
                            .padding(start = if (height == 56.dp) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .haActions(ctx, iconActions, defaultTap = iconActions.obj("tap_action")),
                    ) {
                        val picture = entity.entityPicture?.takeIf { config.bool("force_icon") != true && config.bool("cover_background") != true }
                        if (picture != null) RemoteImage(ctx.images, ctx.hass.absoluteUrl(picture), Modifier.fillMaxSize())
                        else MdiIcon(config.string("icon")?.takeIf { it.isNotBlank() } ?: ctx.icons.icon(entity), c.primaryText)
                    }
                }
                Info(ctx, entity, Modifier.weight(1f))
                Buttons(ctx, entity, onVolume = { volumeOpen = true })
            }
        }
    }

    @Composable
    private fun Info(ctx: CardContext, entity: EntityState, modifier: Modifier) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val title = entity.attrString("media_title")?.takeIf { it.isNotBlank() && entity.state !in OFF_STATES }
        val artist = entity.attrString("media_artist") ?: entity.attrString("media_album_name")
        val first: String
        val second: String?
        if (title != null) {
            first = title
            second = artist
        } else {
            first = if (config.bool("show_name") != false) config.string("name") ?: entity.friendlyName else ""
            second = if (config.bool("show_state") == true || config.bool("show_last_changed") == true) {
                listOfNotNull(
                    if (config.bool("show_state") == true) ctx.formatter.formatState(entity, ctx.now()) else null,
                    if (config.bool("show_last_changed") == true) ctx.formatter.relativeTime(entity.lastChanged, ctx.now(), capitalize = true) else null,
                ).joinToString(" • ")
            } else null
        }
        Column(modifier.padding(start = 12.dp, end = 16.dp)) {
            if (first.isNotEmpty()) {
                BasicText(first, style = t.style(13.sp, FontWeight.SemiBold, lineHeight = 18f / 13f, color = c.primaryText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!second.isNullOrEmpty()) {
                BasicText(second, style = t.style(12.sp, lineHeight = 18f / 12f, color = c.primaryText.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun Buttons(ctx: CardContext, entity: EntityState, onVolume: () -> Unit) {
        val f = entity.supportedFeatures
        val off = entity.state in OFF_STATES
        val playing = entity.state == "playing"
        fun hidden(key: String) = hide?.bool(key) == true
        fun call(service: String, data: JsonObject = jsonOf()) =
            ctx.actions.callService("media_player", service, JsonObject(data + ("entity_id" to JsonPrimitive(entity.entityId))), null)
        Row(
            Modifier.padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!hidden("power_button") && (f and (TURN_ON or TURN_OFF)) != 0) {
                MediaButton("mdi:power", { call(if (off) "turn_on" else "turn_off") })
            }
            if (!off) {
                if (!hidden("previous_button") && f and PREVIOUS_TRACK != 0) MediaButton("mdi:skip-previous", { call("media_previous_track") })
                if (!hidden("next_button") && f and NEXT_TRACK != 0) MediaButton("mdi:skip-next", { call("media_next_track") })
                if (!hidden("volume_button") && f and VOLUME_SET != 0) MediaButton("mdi:volume-high", onVolume)
                if (!hidden("play_pause_button") && f and (PAUSE or PLAY or STOP) != 0) {
                    MediaButton(
                        if (playing) "mdi:pause" else "mdi:play",
                        onClick = {
                            call(
                                when {
                                    !playing -> "media_play"
                                    f and PAUSE != 0 -> "media_pause"
                                    else -> "media_stop"
                                }
                            )
                        },
                        background = BubbleColors.accent,
                        tint = HaTheme.colors.primaryBackground,
                    )
                }
            }
        }
    }

    /** Mute button, volume slider with value, close button (`.bubble-volume-slider-wrapper`). */
    @Composable
    private fun VolumeRow(ctx: CardContext, entity: EntityState, onClose: () -> Unit) {
        val c = HaTheme.colors
        val muted = entity.attr("is_volume_muted")?.toString() == "true"
        val volume = (entity.attrDouble("volume_level") ?: 0.0).toFloat()
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MediaButton(if (muted) "mdi:volume-off" else "mdi:volume-high", {
                ctx.actions.callService("media_player", "volume_mute", jsonOf("entity_id" to entity.entityId, "is_volume_muted" to !muted), null)
            }, size = 42.dp)
            VolumeSlider(volume, Modifier.weight(1f)) { level ->
                ctx.actions.callService("media_player", "volume_set", jsonOf("entity_id" to entity.entityId, "volume_level" to (level * 100).roundToInt() / 100.0), null)
            }
            MediaButton("mdi:close", onClose, size = 42.dp)
        }
    }

    companion object {
        private const val PAUSE = 1
        private const val VOLUME_SET = 4
        private const val PREVIOUS_TRACK = 16
        private const val NEXT_TRACK = 32
        private const val TURN_ON = 128
        private const val TURN_OFF = 256
        private const val STOP = 4096
        private const val PLAY = 16384
        private val OFF_STATES = setOf("off", "unavailable", "unknown", "standby")
    }
}

@Composable
private fun MediaButton(
    icon: String,
    onClick: () -> Unit,
    background: Color = Color.Transparent,
    tint: Color = HaTheme.colors.primaryText,
    size: Dp = 36.dp,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = remember(tint) { PressIndication(tint) },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { MdiIcon(icon, tint, size = 20.dp) }
}

/** Bubble's volume slider: accent fill on a translucent track, value in percent on the fill. */
@Composable
private fun VolumeSlider(value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: value).coerceIn(0f, 1f)
    fun at(x: Float) = (x / width).coerceIn(0f, 1f)
    val t = HaTheme.typography
    Box(
        modifier
            .height(42.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .clip(RoundedCornerShape(21.dp))
            .background(HaTheme.colors.primaryText.copy(alpha = 0.1f))
            .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { drag = at(it.x) },
                    onDragEnd = { drag?.let(onChange); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = at(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(BubbleColors.accent))
        BasicText(
            "${(shown * 100).roundToInt()}%",
            style = t.style(12.sp, FontWeight.SemiBold, color = HaTheme.colors.primaryText),
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
