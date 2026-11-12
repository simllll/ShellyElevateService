package com.stretter.shellyelevateservice.dashboard.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.ActionTrigger
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.CardRenderer
import com.stretter.shellyelevateservice.dashboard.cards.GridOptions
import com.stretter.shellyelevateservice.dashboard.cards.HaCard
import com.stretter.shellyelevateservice.dashboard.cards.TileIcon
import com.stretter.shellyelevateservice.dashboard.cards.TileInfo
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.isTemplate
import com.stretter.shellyelevateservice.dashboard.cards.template
import com.stretter.shellyelevateservice.dashboard.hass.TOGGLE_DOMAINS
import com.stretter.shellyelevateservice.dashboard.hass.TemplateResult
import com.stretter.shellyelevateservice.dashboard.json.array
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject

/**
 * `custom:mushroom-template-card` (Mushroom v5): a tile-style card whose `icon`, `color`,
 * `primary`, `secondary` and `picture` are Jinja templates (variables `config` and `entity`).
 * Like Mushroom: tap defaults to more-info and the icon toggles toggleable entities.
 * Features and badges are not rendered.
 */
class MushroomTemplateCard(private val raw: JsonObject) : CardRenderer {
    private val entityId = raw.string("entity")
    private val vertical = raw.bool("vertical") == true
    private val variables = jsonOf("config" to raw, "entity" to entityId, "area" to raw.string("area"))

    private val config: JsonObject = if (entityId == null) raw else JsonObject(
        jsonOf(
            "tap_action" to jsonOf("action" to "more-info"),
            "icon_tap_action" to jsonOf(
                "action" to if (entityId.substringBefore('.') in TOGGLE_DOMAINS || entityId.substringBefore('.') in setOf("button", "input_button", "scene")) "toggle" else "none"
            ),
        ) + raw
    )

    override fun gridOptions(): GridOptions {
        val hasContent = listOf("icon", "picture", "primary", "secondary").any { raw.containsKey(it) }
        var rows = if (hasContent) 1 else 0
        rows += raw.array("features")?.size ?: 0
        if (vertical && (raw.containsKey("primary") || (raw.containsKey("secondary") && !raw.containsKey("icon")))) rows++
        return if (raw.bool("multiline_secondary") == true) GridOptions(columns = 6) else GridOptions(columns = 6, rows = rows)
    }

    @Composable
    private fun value(ctx: CardContext, key: String): String? {
        val text = raw.string(key)?.takeIf { it.isNotEmpty() } ?: return null
        if (!isTemplate(text)) return text
        return (ctx.template(text, variables) as? TemplateResult.Value)?.text?.takeIf { it.isNotEmpty() }
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(entityId)
        val icon = value(ctx, "icon")
        val colorName = value(ctx, "color")
        val primary = value(ctx, "primary")
        val secondary = value(ctx, "secondary")
        val picture = value(ctx, "picture")
        val color = colorName?.let { Css.color(it) } ?: HaTheme.colors.stateInactive
        val iconAction = config.obj("icon_tap_action")
        HaCard(modifier.fillMaxSize()) {
            val content = Modifier
                .fillMaxSize()
                .haActions(ctx, config, pressColor = color, enabled = ActionHandler.isInteractive(config, defaultTap = false))
            val iconView: @Composable () -> Unit = {
                if (icon != null || picture != null) {
                    TileIcon(
                        ctx, entity, icon ?: "no:icon", color, picture?.let { ctx.hass.absoluteUrl(it) },
                        Modifier.then(
                            if (iconAction != null && ActionHandler.hasAction(jsonOf("tap_action" to iconAction), ActionTrigger.TAP)) {
                                Modifier.haActions(
                                    ctx,
                                    jsonOf("tap_action" to iconAction, "hold_action" to config.obj("icon_hold_action"), "double_tap_action" to config.obj("icon_double_tap_action"), "entity" to entityId),
                                    pressColor = color,
                                )
                            } else Modifier
                        ),
                    )
                }
            }
            if (vertical) {
                Column(
                    content.padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                ) {
                    iconView()
                    if (primary != null || secondary != null) TileInfo(primary.orEmpty(), secondary, centered = true)
                }
            } else {
                Row(
                    content.heightIn(min = 56.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    iconView()
                    if (primary != null || secondary != null) TileInfo(primary.orEmpty(), secondary, centered = false, Modifier.weight(1f))
                }
            }
        }
    }
}
