package com.stretter.shellyelevateservice.dashboard.popup

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
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.now
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.TOGGLE_DOMAINS
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.hass.turnOnOffService
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.HaButton
import com.stretter.shellyelevateservice.dashboard.ui.HaIconButton
import com.stretter.shellyelevateservice.dashboard.ui.HaSlider
import com.stretter.shellyelevateservice.dashboard.ui.HaSwitch

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
    }
}

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
