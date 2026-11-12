package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.defaultButtonAction
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asInt
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Core `hui-button-card`: centered icon (40 % of the width, at most 80 % of the height, or
 * `icon_height`), name (16px) and optional state (0.9rem, secondary). Defaults like the frontend:
 * tap = toggle for toggleable domains else more-info, hold = more-info.
 */
class ButtonCard(config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")
    private val showIcon = config.bool("show_icon") ?: true
    private val showName = config.bool("show_name") ?: true
    private val showState = config.bool("show_state") ?: false
    private val iconHeight = config.string("icon_height")?.removeSuffix("px")?.toFloatOrNull()

    /** Config with the card's action defaults merged in (setConfig). */
    private val config: JsonObject = JsonObject(
        jsonOf(
            "tap_action" to jsonOf("action" to defaultButtonAction(entityId)),
            "hold_action" to jsonOf("action" to "more-info"),
            "double_tap_action" to jsonOf("action" to "none"),
        ) + config
    )

    override fun gridOptions(): GridOptions =
        if (showIcon && (showName || showState)) GridOptions(columns = 6, rows = 2, minColumns = 2, minRows = 2)
        else GridOptions(columns = 3, rows = 1, minColumns = 2, minRows = 1)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val entity = ctx.entity(entityId)
        val c = HaTheme.colors
        val t = HaTheme.typography
        val color = stateColor(entity, config.string("color"), c.stateIcon)
        val name = config.string("name") ?: entity?.friendlyName ?: ""
        HaCard(modifier.fillMaxSize()) {
            Layout(
                content = {
                    // Sized by the layout below (fixed constraints override the 0dp preference).
                    if (showIcon) MdiIcon(ctx.icons.icon(entity, config.string("icon")), color, size = 0.dp)
                    if (showName) {
                        BasicText(name, style = t.style(t.sizeL, lineHeight = t.lineHeightCondensed, color = c.primaryText).copy(textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (showState && entity != null) {
                        BasicText(ctx.formatter.formatState(entity, ctx.now()), style = t.style(12.6.sp, lineHeight = t.lineHeightCondensed, color = c.secondaryText).copy(textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                modifier = Modifier.fillMaxSize().haActions(ctx, config, pressColor = color),
            ) { measurables, constraints ->
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
                val padV = (width * 0.04f).roundToInt() // padding: 4% 0 (percentages refer to the width)
                val gap = 8.dp.roundToPx()
                var index = 0
                val iconMeasurable = if (showIcon) measurables[index++] else null
                val texts = measurables.drop(index).map { it.measure(Constraints(maxWidth = width)) }
                val textHeight = texts.sumOf { it.height }
                val available = if (constraints.hasBoundedHeight) constraints.maxHeight - 2 * padV else Int.MAX_VALUE
                var iconSize = iconHeight?.dp?.roundToPx() ?: (width * 0.4f).roundToInt()
                if (constraints.hasBoundedHeight) {
                    iconSize = min(iconSize, (constraints.maxHeight * 0.8f).roundToInt())
                    if (texts.isNotEmpty()) iconSize = min(iconSize, available - gap - textHeight)
                }
                iconSize = max(iconSize, 0)
                val icon: Placeable? = iconMeasurable?.measure(Constraints.fixed(iconSize, iconSize))
                val contentHeight = (icon?.height ?: 0) + (if (icon != null && texts.isNotEmpty()) gap else 0) + textHeight
                val height = if (constraints.hasBoundedHeight) max(constraints.maxHeight, constraints.minHeight)
                else max(contentHeight + 2 * padV, constraints.minHeight)
                layout(width, height) {
                    var y = (height - contentHeight) / 2
                    icon?.let {
                        it.place((width - it.width) / 2, y)
                        y += it.height + if (texts.isNotEmpty()) gap else 0
                    }
                    texts.forEach {
                        it.place((width - it.width) / 2, y)
                        y += it.height
                    }
                }
            }
        }
    }

    companion object {
        /** `--state-color` of the button card (inactive = `--state-icon-color`). */
        fun stateColor(entity: EntityState?, color: String?, stateIcon: Color): Color {
            if (color == "none") return stateIcon
            if (color != null) return if (entity == null || entity.isActive()) CssColors.computeCssColor(color) ?: stateIcon else stateIcon
            if (entity == null) return stateIcon
            val rgb = entity.attr("rgb_color").asArray()?.mapNotNull { it.asInt() }
            if (rgb != null && rgb.size == 3) return CssColors.fromRgb(rgb[0], rgb[1], rgb[2])
            return CssColors.stateColor(entity, inactiveColor = stateIcon) ?: stateIcon
        }
    }
}
