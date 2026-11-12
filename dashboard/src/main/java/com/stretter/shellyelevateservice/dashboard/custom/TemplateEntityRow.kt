package com.stretter.shellyelevateservice.dashboard.custom

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.ActionTrigger
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.StateBadge
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.isTemplate
import com.stretter.shellyelevateservice.dashboard.cards.template
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.TemplateResult
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.hass.turnOnOffService
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.rows.RowRenderer
import com.stretter.shellyelevateservice.dashboard.rows.RowStateText
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import com.stretter.shellyelevateservice.dashboard.ui.HaSwitch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `custom:template-entity-row` (thomasloven): an entity row whose `icon`, `name`, `secondary`,
 * `state`, `color`, `image`, `active`, `condition` and `entity` may be Jinja templates (rendered
 * live with the variable `config`). Like the original, the state falls back to the raw entity state
 * and `toggle: true` shows a switch instead. `this.entity_id` in options is already replaced by
 * auto-entities.
 */
class TemplateEntityRow(private val config: JsonObject) : RowRenderer {
    private val variables = jsonOf("config" to config)

    @Composable
    private fun value(ctx: CardContext, key: String): String? {
        val raw = config[key] ?: return null
        val text = raw.asString() ?: return raw.toString()
        if (!isTemplate(text)) return text
        return when (val r = ctx.template(text, variables)) {
            is TemplateResult.Value -> r.text
            else -> null
        }
    }

    @Composable
    override fun isVisible(ctx: CardContext): Boolean {
        val condition = value(ctx, "condition") ?: return true
        return condition.lowercase() == "true"
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entityId = value(ctx, "entity")
        val entity = ctx.entity(entityId)
        val icon = value(ctx, "icon")
        val name = value(ctx, "name") ?: entity?.friendlyName ?: entityId ?: ""
        val secondary = value(ctx, "secondary")
        val state = value(ctx, "state") ?: entity?.state
        val active = value(ctx, "active")?.lowercase()
        val color = value(ctx, "color")
        val image = value(ctx, "image")
        val toggle = config["toggle"].asString() == "true" && entity != null

        // `active` forces the badge into the on/off look.
        val badgeEntity: EntityState? = remember(entity, active) {
            when {
                entity == null -> null
                active == "true" -> entity.copy(state = "on", attributes = JsonObject(entity.attributes + ("brightness" to JsonPrimitive(255))))
                active == "false" -> entity.copy(state = "off")
                else -> entity
            }
        }
        val actionConfig = remember(entityId) { if (entityId != null) JsonObject(config + ("entity" to JsonPrimitive(entityId))) else config }
        val interactive = entityId != null || ActionHandler.hasAction(actionConfig, ActionTrigger.TAP) ||
            ActionHandler.hasAction(actionConfig, ActionTrigger.HOLD)
        val t = HaTheme.typography
        val c = HaTheme.colors
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .haActions(ctx, actionConfig, enabled = interactive && ActionHandler.isInteractive(actionConfig)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateBadge(
                ctx, badgeEntity,
                icon = icon?.ifEmpty { "no:icon" },
                image = image,
                color = color ?: if (active == "false") "none" else "state",
            )
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                BasicText(name, style = t.style(t.sizeM, color = c.primaryText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!secondary.isNullOrEmpty()) {
                    BasicText(secondary, style = t.style(t.sizeM, color = c.secondaryText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (toggle) {
                HaSwitch(entity.isActive(), { on ->
                    val (domain, service) = turnOnOffService(entity.entityId, on)
                    ctx.actions.callService(domain, service, jsonOf("entity_id" to entity.entityId), null)
                }, enabled = !entity.isUnavailable)
            } else if (state != null) {
                RowStateText(state)
            }
        }
    }
}
