package io.github.simllll.panelelevate.dashboard.cards.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.simllll.panelelevate.dashboard.actions.haActions
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.GridOptions
import io.github.simllll.panelelevate.dashboard.cards.entity
import io.github.simllll.panelelevate.dashboard.cards.now
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.json.EmptyJsonObject
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.json.with
import io.github.simllll.panelelevate.dashboard.theme.CssColors
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Bubble Card `button` (`button_type` `name`, `state` or `switch`; `slider` falls back to `state`).
 *
 * A 56px pill (50px with `card_layout: normal`) in `--bubble-main-background-color` with the
 * round 42px icon container on the left and name/state (13px/600, 12px/0.7) next to it. A
 * `switch` that is on fills with the entity's state color. Actions: `button_action` for the pill
 * (default: toggle for `switch`, more-info for `state`, nothing for `name`), `tap_action` etc. for
 * the icon (default more-info when there is an entity).
 */
class BubbleButtonCard(private val config: JsonObject) : CardRenderer {
    private val entityId = config.string("entity")?.takeIf { it.isNotBlank() }
    private val buttonType = config.string("button_type") ?: if (entityId == null) "name" else "switch"
    private val buttonActions: JsonObject = run {
        val base = (config.obj("button_action") ?: EmptyJsonObject).with("entity", entityId?.let { JsonPrimitive(it) })
        if (base.obj("tap_action") != null) base
        else base.with(
            "tap_action",
            when {
                entityId == null || buttonType == "name" -> jsonOf("action" to "none")
                buttonType == "switch" -> jsonOf("action" to "toggle")
                else -> jsonOf("action" to "more-info")
            },
        )
    }
    private val iconActions: JsonObject = when {
        config.obj("tap_action") != null -> config
        entityId != null -> config.with("tap_action", jsonOf("action" to "more-info"))
        else -> config.with("tap_action", buttonActions.obj("tap_action"))
    }

    override fun gridOptions() = GridOptions(columns = 12)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val c = HaTheme.colors
        val t = HaTheme.typography
        val entity = ctx.entity(entityId)
        val height = bubbleHeight(config)
        val large = height == 56.dp
        val on = buttonType == "switch" && entity?.isActive() == true
        val stateColor = entity?.takeIf { on }?.let { CssColors.stateColor(it) ?: c.stateActive }
        val name = config.string("name") ?: entity?.friendlyName ?: ""
        val icon = config.string("icon")?.takeIf { it.isNotBlank() } ?: entity?.let { ctx.icons.icon(it) }
        val showIcon = config.bool("show_icon") != false && icon != null
        val showName = config.bool("show_name") != false && name.isNotEmpty()
        val showState = config.bool("show_state") == true && entity != null
        val shape = RoundedCornerShape(height / 2)
        Box(
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .background(BubbleColors.main)
                .then(if (stateColor != null) Modifier.background(stateColor.copy(alpha = 0.5f)) else Modifier)
                .haActions(ctx, buttonActions, defaultTap = buttonActions.obj("tap_action"))
                .then(if (entity?.isUnavailable == true) Modifier.alpha(0.5f) else Modifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showIcon) {
                    BubbleIconContainer(
                        if (large) 42.dp else 38.dp,
                        Modifier
                            .padding(start = if (large) 8.dp else 6.dp, end = if (large) 0.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .haActions(ctx, iconActions, defaultTap = iconActions.obj("tap_action")),
                    ) {
                        val tint = stateColor ?: c.primaryText
                        MdiIcon(icon, tint, Modifier.alpha(if (entity != null && !on && buttonType == "switch") 0.6f else 1f))
                    }
                }
                Column(Modifier.padding(start = if (showIcon) 4.dp + (if (large) 8.dp else 0.dp) else 16.dp, end = 16.dp)) {
                    if (showName) {
                        BasicText(
                            name,
                            style = t.style(13.sp, FontWeight.SemiBold, lineHeight = 18f / 13f, color = c.primaryText),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showState) {
                        BasicText(
                            ctx.formatter.formatState(entity!!, ctx.now()),
                            style = t.style(12.sp, lineHeight = 18f / 12f, color = c.primaryText.copy(alpha = 0.7f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
