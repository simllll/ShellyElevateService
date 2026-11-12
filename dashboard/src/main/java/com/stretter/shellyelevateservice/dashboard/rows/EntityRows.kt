package com.stretter.shellyelevateservice.dashboard.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.StateBadge
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.now
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.hass.turnOnOffService
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.format.parseIso
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.HaButton
import com.stretter.shellyelevateservice.dashboard.ui.HaIconButton
import com.stretter.shellyelevateservice.dashboard.ui.HaSwitch
import kotlinx.serialization.json.JsonObject

/**
 * `hui-generic-entity-row`: 40px state badge, name (+ secondary line), trailing [content]
 * (state text, toggle, buttons). Tapping the row runs the row's actions (default more-info).
 * Reusable by custom rows: pass [name]/[secondary] to override the defaults.
 */
@Composable
fun GenericEntityRow(
    ctx: CardContext,
    config: JsonObject,
    entity: EntityState?,
    modifier: Modifier = Modifier,
    name: String? = config.string("name"),
    secondary: String? = null,
    content: @Composable () -> Unit = {},
) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    if (entity == null) {
        // Not received yet, or unknown to HA (the filtered subscription cannot tell the difference).
        PendingEntityRow(name ?: config.string("entity") ?: "", modifier)
        return
    }
    val secondaryText = secondary ?: secondaryInfo(ctx, config, entity)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .haActions(ctx, config, enabled = ActionHandler.isInteractive(config)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateBadge(ctx, entity, icon = config.string("icon"), image = config.string("image"), color = config.string("color") ?: colorFromStateColor(config))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            BasicText(
                name ?: entity.friendlyName,
                style = t.style(t.sizeM, color = c.primaryText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryText != null) {
                BasicText(
                    secondaryText,
                    style = t.style(t.sizeM, color = c.secondaryText),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        content()
    }
}

private fun colorFromStateColor(config: JsonObject): String? = when (config["state_color"]?.toString()) {
    "true" -> "state"
    "false" -> "none"
    else -> null
}

/** `secondary_info` of entity rows. */
@Composable
private fun secondaryInfo(ctx: CardContext, config: JsonObject, entity: EntityState): String? {
    val f = ctx.formatter
    return when (config.string("secondary_info")) {
        null, "none" -> null
        "entity-id" -> entity.entityId
        "last-changed" -> f.relativeTime(entity.lastChanged, ctx.now(), capitalize = true)
        "last-updated" -> f.relativeTime(entity.lastUpdated, ctx.now(), capitalize = true)
        "last-triggered" -> entity.attrString("last_triggered")?.let { parseIso(it) }
            ?.let { f.relativeTime(it, ctx.now(), capitalize = true) } ?: "Nie"
        "position" -> entity.attrDouble("current_position")?.let { "${it.toInt()} %" }
        "tilt-position" -> entity.attrDouble("current_tilt_position")?.let { "${it.toInt()} %" }
        "brightness" -> entity.attrDouble("brightness")?.let { "${Math.round(it / 255 * 100)} %" }
        "state" -> f.formatState(entity, ctx.now())
        else -> null
    }
}

@Composable
private fun PendingEntityRow(label: String, modifier: Modifier) {
    val t = HaTheme.typography
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(40.dp))
        BasicText(
            label,
            style = t.style(t.sizeM, color = HaTheme.colors.disabledText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Right-aligned state text of simple rows (`.text-content .state`). */
@Composable
fun RowStateText(text: String, modifier: Modifier = Modifier) {
    val t = HaTheme.typography
    BasicText(
        text,
        style = t.style(t.sizeM, color = HaTheme.colors.primaryText).copy(textAlign = TextAlign.End),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Rows showing the formatted state (`sensor`, `simple`, `input-select`, `climate`, …). */
class StateRow(private val config: JsonObject) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(config.string("entity"))
        GenericEntityRow(ctx, config, entity, modifier) {
            if (entity != null) RowStateText(ctx.formatter.formatState(entity, ctx.now()))
        }
    }
}

/** `hui-toggle-entity-row`: switch on the right. */
class ToggleRow(private val config: JsonObject) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(config.string("entity"))
        GenericEntityRow(ctx, config, entity, modifier) {
            if (entity != null) {
                HaSwitch(
                    checked = entity.isActive(),
                    onCheckedChange = { on ->
                        val (domain, service) = turnOnOffService(entity.entityId, on)
                        ctx.actions.callService(domain, service, jsonOf("entity_id" to entity.entityId), null)
                    },
                    enabled = !entity.isUnavailable,
                )
            }
        }
    }
}

/** `hui-cover-entity-row` with `ha-cover-controls`: open / stop / close (hidden if unsupported). */
class CoverRow(private val config: JsonObject) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(config.string("entity"))
        GenericEntityRow(ctx, config, entity, modifier) {
            if (entity != null) CoverControls(ctx, entity)
        }
    }
}

/** Cover feature bits (`CoverEntityFeature`). */
private const val COVER_OPEN = 1
private const val COVER_CLOSE = 2
private const val COVER_STOP = 8

@Composable
fun CoverControls(ctx: CardContext, entity: EntityState) {
    val features = entity.supportedFeatures
    val position = entity.attr("current_position").asDouble()
    val assumed = entity.attr("assumed_state")?.toString() == "true"
    val unavailable = entity.isUnavailable
    val fullyOpen = position?.let { it == 100.0 } ?: (entity.state == "open")
    val fullyClosed = position?.let { it == 0.0 } ?: (entity.state == "closed")
    val canOpen = !unavailable && (assumed || (!fullyOpen && entity.state != "opening"))
    val canClose = !unavailable && (assumed || (!fullyClosed && entity.state != "closing"))
    val horizontal = entity.deviceClass in setOf("awning", "door", "gate", "curtain")
    val target = jsonOf("entity_id" to entity.entityId)
    Row {
        CoverButton(features and COVER_OPEN != 0, if (horizontal) "mdi:arrow-expand-horizontal" else "mdi:arrow-up", canOpen) {
            ctx.actions.callService("cover", "open_cover", target, null)
        }
        CoverButton(features and COVER_STOP != 0, "mdi:stop", !unavailable) {
            ctx.actions.callService("cover", "stop_cover", target, null)
        }
        CoverButton(features and COVER_CLOSE != 0, if (horizontal) "mdi:arrow-collapse-horizontal" else "mdi:arrow-down", canClose) {
            ctx.actions.callService("cover", "close_cover", target, null)
        }
    }
}

/** `visibility: hidden` when unsupported: keeps its 48px slot. */
@Composable
private fun CoverButton(supported: Boolean, icon: String, enabled: Boolean, onClick: () -> Unit) {
    if (supported) HaIconButton(icon, onClick, enabled = enabled)
    else Spacer(Modifier.size(48.dp))
}

/** Rows with a text action button: scene (Aktivieren), script (Ausführen), button (Drücken), lock. */
class ActionButtonRow(private val config: JsonObject, private val kind: String) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(config.string("entity"))
        GenericEntityRow(ctx, config, entity, modifier) {
            if (entity == null) return@GenericEntityRow
            val id = entity.entityId
            val (label, call) = when (kind) {
                "scene" -> "Aktivieren" to ("scene" to "turn_on")
                "script" -> (if (entity.state == "on") "Abbrechen" else "Ausführen") to ("script" to if (entity.state == "on") "turn_off" else "turn_on")
                "lock" -> (if (entity.state == "locked") "Aufsperren" else "Zusperren") to ("lock" to if (entity.state == "locked") "unlock" else "lock")
                else -> "Drücken" to turnOnOffService(id, true)
            }
            HaButton(
                label,
                onClick = { ctx.actions.callService(call.first, call.second, jsonOf("entity_id" to id), null) },
                outlined = false,
                modifier = Modifier.alpha(if (entity.isUnavailable) 0.38f else 1f),
            )
        }
    }
}

/** `section` row: optional label with a divider above. */
class SectionRow(private val config: JsonObject) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val t = HaTheme.typography
        Column(modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(HaTheme.colors.divider))
            config.string("label")?.let {
                BasicText(it, style = t.style(t.sizeM, FontWeight.Medium, color = HaTheme.colors.primaryText), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            }
        }
    }
}

/** `divider` row. */
class DividerRow : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        Box(modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(HaTheme.colors.divider))
    }
}

/** `call-service` / `perform-action` row: icon, name, button running `action`. */
class PerformActionRow(private val config: JsonObject) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val t = HaTheme.typography
        Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(8.dp)) { MdiIcon(config.string("icon") ?: "mdi:remote", HaTheme.colors.stateIcon) }
            BasicText(
                config.string("name") ?: "",
                style = t.style(t.sizeM, color = HaTheme.colors.primaryText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            HaButton(config.string("action_name") ?: "Ausführen", onClick = {
                val action = config.obj("action") ?: jsonOf(
                    "action" to "perform-action",
                    "perform_action" to (config.string("perform_action") ?: config.string("service")),
                    "data" to (config.obj("data") ?: config.obj("service_data")),
                    "target" to config.obj("target"),
                )
                ctx.actions.perform(action, config)
            }, outlined = false)
        }
    }
}

/** `attribute` row: an attribute value with optional prefix/suffix. */
class AttributeRow(private val config: JsonObject) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(config.string("entity"))
        val attribute = config.string("attribute") ?: ""
        GenericEntityRow(ctx, config, entity, modifier) {
            val value = entity?.attr(attribute)?.let { it.asString() ?: it.toString() } ?: "—"
            RowStateText((config.string("prefix") ?: "") + value + (config.string("suffix") ?: ""))
        }
    }
}

/** Unknown row type: shows the type name. */
class PlaceholderRow(private val type: String) : RowRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val t = HaTheme.typography
        Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(type, style = t.style(t.sizeM, color = HaTheme.colors.secondaryText), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Registers the core row types. */
fun RowRegistry.registerCoreRows() {
    register("simple") { StateRow(it) }
    register("sensor") { StateRow(it) }
    register("climate") { StateRow(it) }
    register("input-select") { StateRow(it) }
    register("input-number") { StateRow(it) }
    register("input-text") { StateRow(it) }
    register("input-datetime") { StateRow(it) }
    register("valve") { StateRow(it) }
    register("toggle") { ToggleRow(it) }
    register("fan") { ToggleRow(it) }
    register("group") { ToggleRow(it) }
    register("humidifier") { ToggleRow(it) }
    register("cover") { CoverRow(it) }
    register("scene") { ActionButtonRow(it, "scene") }
    register("script") { ActionButtonRow(it, "script") }
    register("button") { ActionButtonRow(it, "button") }
    register("input-button") { ActionButtonRow(it, "button") }
    register("lock") { ActionButtonRow(it, "lock") }
    register("section") { SectionRow(it) }
    register("divider") { DividerRow() }
    register("call-service") { PerformActionRow(it) }
    register("perform-action") { PerformActionRow(it) }
    register("attribute") { AttributeRow(it) }
}
