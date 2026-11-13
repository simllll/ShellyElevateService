package io.github.simllll.panelelevate.dashboard.popup

import io.github.simllll.panelelevate.dashboard.cards.RenderCard
import io.github.simllll.panelelevate.dashboard.cards.CardLayout
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import android.os.SystemClock
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.cards.now
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.TOGGLE_DOMAINS
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.hass.turnOnOffService
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.json.asArray
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.theme.CssColors
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.HaButton
import io.github.simllll.panelelevate.dashboard.ui.HaIconButton
import io.github.simllll.panelelevate.dashboard.ui.HaSlider
import io.github.simllll.panelelevate.dashboard.ui.HaSwitch

/**
 * Minimal native more-info dialog: icon, state, last change and the main controls of the domain
 * (toggle, light brightness, cover open/stop/close/position, climate target temperature and modes,
 * select options, run for scene/script/button).
 */
@Composable
internal fun PopupScope.MoreInfoDialog(entityId: String) {
    val entity = ctx.entity(entityId)
    HaDialog(title = entity?.friendlyName ?: entityId) {
        if (entity == null) {
            BasicText(entityId, style = HaTheme.typography.body.copy(color = HaTheme.colors.secondaryText))
            return@HaDialog
        }
        StateHeader(entity)
        Spacer(Modifier.height(24.dp))
        Controls(entity)
        if (entity.domain !in NO_HISTORY_DOMAINS) History(entity)
    }
}

/** "Verlauf" like `ha-more-info-history`: the last 24 h as a history graph (timeline or line chart). */
@Composable
private fun PopupScope.History(entity: EntityState) {
    val t = HaTheme.typography
    val config = remember(entity.entityId) {
        jsonOf("type" to "history-graph", "hours_to_show" to 24, "entities" to listOf(entity.entityId), "show_names" to false)
    }
    Spacer(Modifier.height(24.dp))
    BasicText("Verlauf", style = t.style(t.sizeL, FontWeight.Medium, t.lineHeightCondensed, color = HaTheme.colors.primaryText))
    Spacer(Modifier.height(8.dp))
    ctx.withLayout(CardLayout.STACK).RenderCard(config, Modifier.fillMaxWidth())
}

private val NO_HISTORY_DOMAINS = setOf("scene", "script", "button", "input_button", "event")

@Composable
private fun PopupScope.StateHeader(entity: EntityState) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    val now = ctx.now()
    val color = if (entity.isActive()) CssColors.stateColor(entity) ?: c.stateIcon else c.stateIcon
    Row(verticalAlignment = Alignment.CenterVertically) {
        MdiIcon(ctx.icons.icon(entity), color, size = 48.dp)
        Column(Modifier.padding(start = 16.dp)) {
            BasicText(ctx.formatter.formatState(entity, now), style = t.style(t.size2xl, FontWeight.Normal, t.lineHeightCondensed, color = c.primaryText))
            BasicText(
                ctx.formatter.relativeTime(entity.lastChanged, now, capitalize = true),
                style = t.style(t.sizeM, color = c.secondaryText),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopupScope.Controls(entity: EntityState) {
    val actions = ctx.actions
    val id = entity.entityId
    val target = jsonOf("entity_id" to id)
    val c = HaTheme.colors
    when (entity.domain) {
        "cover" -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HaButton("Öffnen", { actions.callService("cover", "open_cover", target, null) }, icon = "mdi:arrow-up")
                HaButton("Stopp", { actions.callService("cover", "stop_cover", target, null) }, icon = "mdi:stop")
                HaButton("Schließen", { actions.callService("cover", "close_cover", target, null) }, icon = "mdi:arrow-down")
            }
            entity.attrDouble("current_position")?.let { position ->
                Spacer(Modifier.height(16.dp))
                HaSlider(position.toFloat(), { actions.callService("cover", "set_cover_position", jsonOf("entity_id" to id, "position" to it.toInt()), null) }, color = CssColors.stateColor(entity) ?: c.primary)
            }
        }
        "climate", "water_heater" -> {
            val temp = entity.attrDouble("temperature")
            if (temp != null) {
                val step = entity.attrDouble("target_temp_step") ?: if (entity.unit == "°F") 1.0 else 0.5
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HaIconButton("mdi:minus", { actions.callService(entity.domain, "set_temperature", jsonOf("entity_id" to id, "temperature" to temp - step), null) })
                    BasicText(
                        ctx.formatter.formatNumber(temp, 1) + " °",
                        style = HaTheme.typography.style(HaTheme.typography.size3xl, color = c.primaryText),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    HaIconButton("mdi:plus", { actions.callService(entity.domain, "set_temperature", jsonOf("entity_id" to id, "temperature" to temp + step), null) })
                }
            }
            val modes = entity.attr("hvac_modes").asArray()?.mapNotNull { it.asString() }.orEmpty()
            if (modes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { mode ->
                        HaButton(ctx.formatter.translateState(entity, mode), { actions.callService("climate", "set_hvac_mode", jsonOf("entity_id" to id, "hvac_mode" to mode), null) }, filled = mode == entity.state)
                    }
                }
            }
        }
        "select", "input_select" -> {
            val options = entity.attr("options").asArray()?.mapNotNull { it.asString() }.orEmpty()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    HaButton(ctx.formatter.translateState(entity, option), { actions.callService(entity.domain, "select_option", jsonOf("entity_id" to id, "option" to option), null) }, filled = option == entity.state)
                }
            }
        }
        "scene", "script", "button", "input_button" -> {
            val (domain, service) = turnOnOffService(id, true)
            HaButton("Ausführen", { actions.callService(domain, service, target, null) }, filled = true)
        }
        "media_player" -> MediaControls(entity)
        "lock" -> {
            val locked = entity.state == "locked"
            HaButton(if (locked) "Aufsperren" else "Zusperren", { actions.callService("lock", if (locked) "unlock" else "lock", target, null) }, filled = true)
        }
        else -> if (entity.domain in TOGGLE_DOMAINS || entity.domain in setOf("siren", "remote", "vacuum")) {
            ToggleRow(entity)
        }
    }
    if (entity.domain == "light" && entity.isActive()) {
        val brightness = entity.attrDouble("brightness")
        if (brightness != null) {
            Spacer(Modifier.height(16.dp))
            HaSlider(
                (brightness / 255 * 100).toFloat(),
                { actions.callService("light", "turn_on", jsonOf("entity_id" to id, "brightness_pct" to it.toInt().coerceIn(1, 100)), null) },
                range = 1f..100f,
                color = CssColors.stateColor(entity) ?: c.stateActive,
            )
        }
    }
}

@Composable
private fun PopupScope.ToggleRow(entity: EntityState) {
    val on = entity.isActive()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            ctx.formatter.translateState(entity),
            style = HaTheme.typography.style(color = HaTheme.colors.primaryText),
            modifier = Modifier.weight(1f),
        )
        HaSwitch(on, { turnOn ->
            val (domain, service) = turnOnOffService(entity.entityId, turnOn)
            ctx.actions.callService(domain, service, jsonOf("entity_id" to entity.entityId), null)
        }, enabled = !entity.isUnavailable)
    }
}

/**
 * Media player controls like `more-info-media_player`: now playing, power/previous/play-pause/
 * next (as supported), volume with mute, adaptive -/+ (or volume_down/up) and a slider, sources.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopupScope.MediaControls(entity: EntityState) {
    val c = HaTheme.colors
    val t = HaTheme.typography
    val id = entity.entityId
    val f = entity.attrDouble("supported_features")?.toInt() ?: 0
    fun has(bit: Int) = f and bit != 0
    fun call(service: String, vararg extra: Pair<String, Any?>) =
        ctx.actions.callService("media_player", service, jsonOf("entity_id" to id, *extra), null)
    val off = entity.state == "off"

    val title = entity.attr("media_title").asString()
    val artist = entity.attr("media_artist").asString() ?: entity.attr("app_name").asString()
    if (!off && (title != null || artist != null)) {
        if (title != null) BasicText(title, style = t.style(t.sizeL, FontWeight.Medium, t.lineHeightCondensed, color = c.primaryText), maxLines = 2)
        if (artist != null) BasicText(artist, style = t.style(t.sizeM, color = c.secondaryText), maxLines = 1)
        Spacer(Modifier.height(16.dp))
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (has(MP_TURN_ON) || has(MP_TURN_OFF)) HaIconButton("mdi:power", { call(if (off) "turn_on" else "turn_off") })
        if (!off) {
            if (has(MP_PREVIOUS)) HaIconButton("mdi:skip-previous", { call("media_previous_track") })
            if (has(MP_PAUSE) || has(MP_PLAY)) {
                val playing = entity.state == "playing"
                HaIconButton(if (playing) "mdi:pause" else "mdi:play", { call("media_play_pause") }, size = 56.dp, iconSize = 32.dp)
            }
            if (has(MP_NEXT)) HaIconButton("mdi:skip-next", { call("media_next_track") })
        }
    }

    if (!off && (has(MP_VOLUME_SET) || has(MP_VOLUME_STEP))) {
        val reported = (entity.attrDouble("volume_level") ?: 0.0).coerceIn(0.0, 1.0)
        val muted = entity.attr("is_volume_muted").toString().trim('"') == "true"
        // Adaptive -/+: a single tap moves 1 %, taps in quick succession accelerate (1, 1, 2, 2, 3,
        // 3, 5 %). Taps build on the pending value, not on the (lagging) reported volume.
        var pending by remember { mutableStateOf<Double?>(null) }
        var lastTap by remember { mutableLongStateOf(0L) }
        var streak by remember { mutableIntStateOf(0) }
        LaunchedEffect(lastTap) {
            if (lastTap == 0L) return@LaunchedEffect
            delay(VOLUME_PENDING_MS)
            pending = null
        }
        val volume = pending ?: reported
        fun step(direction: Int) {
            val now = SystemClock.uptimeMillis()
            streak = if (now - lastTap <= VOLUME_STREAK_MS) streak + 1 else 0
            lastTap = now
            if (!has(MP_VOLUME_SET)) {
                call(if (direction > 0) "volume_up" else "volume_down")
                return
            }
            val increment = VOLUME_STEPS[minOf(streak, VOLUME_STEPS.lastIndex)]
            val target = (((pending ?: reported) + direction * increment).coerceIn(0.0, 1.0) * 100).roundToInt() / 100.0
            pending = target
            call("volume_set", "volume_level" to target)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (has(MP_VOLUME_MUTE)) HaIconButton(if (muted) "mdi:volume-off" else "mdi:volume-high", { call("volume_mute", "is_volume_muted" to !muted) })
            HaIconButton("mdi:minus", { step(-1) })
            if (has(MP_VOLUME_SET)) {
                HaSlider(
                    (volume * 100).toFloat(),
                    { pending = null; call("volume_set", "volume_level" to (it / 100.0)) },
                    modifier = Modifier.weight(1f),
                    color = c.primary,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            HaIconButton("mdi:plus", { step(1) })
            if (has(MP_VOLUME_SET)) {
                BasicText("${(volume * 100).roundToInt()} %", style = t.style(t.sizeM, color = c.secondaryText), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }

    val sources = entity.attr("source_list").asArray()?.mapNotNull { it.asString() }.orEmpty()
    if (!off && has(MP_SELECT_SOURCE) && sources.isNotEmpty()) {
        val current = entity.attr("source").asString()
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { source -> HaButton(source, { call("select_source", "source" to source) }, filled = source == current) }
        }
    }
}

private const val MP_PAUSE = 1
private val VOLUME_STEPS = doubleArrayOf(0.01, 0.01, 0.02, 0.02, 0.03, 0.03, 0.05)
private const val VOLUME_STREAK_MS = 1_000L
private const val VOLUME_PENDING_MS = 3_000L
private const val MP_VOLUME_SET = 4
private const val MP_VOLUME_MUTE = 8
private const val MP_PREVIOUS = 16
private const val MP_NEXT = 32
private const val MP_TURN_ON = 128
private const val MP_TURN_OFF = 256
private const val MP_VOLUME_STEP = 1024
private const val MP_SELECT_SOURCE = 2048
private const val MP_PLAY = 16384
