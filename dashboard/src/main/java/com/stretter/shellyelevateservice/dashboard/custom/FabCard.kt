package com.stretter.shellyelevateservice.dashboard.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.CardRenderer
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject

/**
 * `custom:fab-card` (bhuebschen/fab-card 1.0.4): a floating action button, `position: fixed` in
 * the web, so it takes no space in the view and is drawn in [com.stretter.shellyelevateservice.dashboard.popup.PopupHost.FloatingLayer].
 *
 * Like the original: a 3.5em circle (em = 14px → 49px) at 1em from the edges given by `position`
 * (`bottom-right` default, `bottom-left`, `top-*`, `middle-*`) plus `offset_x`/`offset_y` (px),
 * `button_color` (default primary), `icon_color` (default white), shadow `0 2px 5px`, icon 1.5em,
 * `disabled` (50% opacity, no clicks), one `action` object. `active_color` (default accent) is
 * used while `entity` is in an "on"-like state and, as tap feedback, while pressed.
 */
class FabCard(private val config: JsonObject) : CardRenderer {
    private val action = config.obj("action")
    private val entityId = config.string("entity")
    private val icon = config.string("icon")
    private val buttonColor = CssColors.computeCssColor(config.string("button_color"))
    private val iconColor = CssColors.computeCssColor(config.string("icon_color"))
    private val activeColor = CssColors.computeCssColor(config.string("active_color"))
    private val disabled = config.bool("disabled") == true
    private val position = config.string("position") ?: "bottom-right"
    private val offsetX = px(config.string("offset_x"))
    private val offsetY = px(config.string("offset_y"))

    @Composable
    override fun isVisible(ctx: CardContext): Boolean = false

    @Composable
    override fun Effects(ctx: CardContext) {
        ctx.popups.Floating { Button(ctx) }
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {}

    @Composable
    private fun Button(ctx: CardContext) {
        val c = HaTheme.colors
        val entity = ctx.entity(entityId)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val active = pressed || entity?.state in POSITIVE_STATES
        val color = if (active) activeColor ?: c.accent else buttonColor ?: c.primary
        val (alignment, dx, dy) = placement()
        Box(Modifier.fillMaxSize().padding(MARGIN), contentAlignment = alignment) {
            Box(
                Modifier
                    .offset(dx, dy)
                    .size(SIZE)
                    .alpha(if (disabled) 0.5f else 1f)
                    .shadow(3.dp, CircleShape)
                    .background(color, CircleShape)
                    .clickable(interaction, indication = null, enabled = !disabled && action != null) {
                        action?.let { ctx.actions.perform(it, config) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon(icon, iconColor ?: Color.White, size = ICON_SIZE)
            }
        }
    }

    /** Alignment and the `offset_x`/`offset_y` shift (moving away from the anchored edge). */
    private fun placement(): Triple<Alignment, Dp, Dp> = when (position) {
        "top-right" -> Triple(Alignment.TopEnd, -offsetX, offsetY)
        "top-left" -> Triple(Alignment.TopStart, offsetX, offsetY)
        "middle-right" -> Triple(Alignment.CenterEnd, -offsetX, offsetY)
        "middle-left" -> Triple(Alignment.CenterStart, offsetX, offsetY)
        "middle-top" -> Triple(Alignment.TopCenter, 0.dp, offsetY)
        "middle-bottom" -> Triple(Alignment.BottomCenter, 0.dp, -offsetY)
        "bottom-left" -> Triple(Alignment.BottomStart, offsetX, -offsetY)
        else -> Triple(Alignment.BottomEnd, -offsetX, -offsetY)
    }

    companion object {
        private val SIZE = 49.dp
        private val ICON_SIZE = 21.dp
        private val MARGIN = 14.dp
        private val POSITIVE_STATES = setOf(
            "true", "on", "open", "active", "detected", "occupied", "unlocked", "home", "above_horizon", "ready",
        )

        /** `offset_x: 20px` / `20` → 20dp; other units are ignored. */
        private fun px(value: String?): Dp = value?.trim()?.removeSuffix("px")?.trim()?.toFloatOrNull()?.dp ?: 0.dp
    }
}
