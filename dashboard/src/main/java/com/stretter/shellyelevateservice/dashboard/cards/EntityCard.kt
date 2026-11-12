package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject

/**
 * `entity` card (`hui-entity-card`): name (16px, secondary) and state icon in a 40px header,
 * below the state as a 28px value with the unit (16px, secondary) after it. `attribute` shows that
 * attribute instead, `unit` overrides the unit, `state_color`/`color` color the icon. Footers are
 * not rendered. Tap defaults to more-info.
 */
class EntityCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    private val attribute = config.string("attribute")

    override fun gridOptions() = GridOptions(columns = 6, rows = 2, minColumns = 6, minRows = 2)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(entityId)
        if (entityId == null || entity == null) {
            WarningCard("Entität nicht gefunden: ${entityId.orEmpty()}").Content(ctx, modifier)
            return
        }
        val c = HaTheme.colors
        val t = HaTheme.typography
        val name = config.string("name") ?: ctx.entityDisplayName(entity, entityId)
        val colorConfig = config.string("color")
        val colored = colorConfig != null || (config.bool("state_color") ?: (entity.domain == "light"))
        val iconColor = when {
            !colored -> c.stateIcon
            colorConfig != null && colorConfig != "state" -> if (entity.isActive()) CssColors.computeCssColor(colorConfig) ?: c.stateIcon else c.stateIcon
            else -> CssColors.stateColor(entity, inactiveColor = c.stateIcon) ?: c.stateIcon
        }
        val (value, unit) = if (attribute != null) {
            val raw = entity.attr(attribute)
            (raw?.let { a -> a.toString().trim('"').let { s -> s.toDoubleOrNull()?.let { ctx.formatter.formatNumber(s) } ?: s } }
                ?: ctx.formatter.translateState(entity, "unknown")) to config.string("unit")
        } else {
            val number = entity.state.toDoubleOrNull()
            if (number != null && (config.string("unit") ?: entity.unit) != null) {
                ctx.formatter.formatNumber(entity.state) to (config.string("unit") ?: entity.unit)
            } else {
                ctx.formatter.formatState(entity, ctx.now()) to config.string("unit")
            }
        }
        HaCard(
            modifier.fillMaxSize().haActions(ctx, config, enabled = ActionHandler.isInteractive(config)),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        name,
                        style = t.style(t.sizeL, FontWeight.Medium, color = c.secondaryText),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    MdiIcon(ctx.icons.icon(entity, config.string("icon")), iconColor)
                }
                Row(
                    Modifier.fillMaxWidth().offset(y = (-4).dp).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    BasicText(
                        value,
                        style = t.style(t.size3xl, lineHeight = t.lineHeightCondensed, color = c.primaryText),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    )
                    if (!unit.isNullOrEmpty()) {
                        BasicText(
                            unit,
                            style = t.style(t.sizeL, lineHeight = t.lineHeightCondensed, color = c.secondaryText),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp).alignByBaseline(),
                        )
                    }
                }
            }
        }
    }
}
