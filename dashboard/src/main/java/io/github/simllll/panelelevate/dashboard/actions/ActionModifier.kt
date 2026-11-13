package io.github.simllll.panelelevate.dashboard.actions

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import io.github.simllll.panelelevate.dashboard.ui.PressIndication
import kotlinx.serialization.json.JsonObject

/**
 * Tap / hold / double-tap handling like the frontend's `actionHandler` directive, for a card or row
 * [config] (the object with `tap_action`, `hold_action`, `double_tap_action`, `entity`).
 *
 * Hold and double tap are only detected when configured (otherwise a long press is a tap and taps
 * are not delayed), exactly like `hasHold` / `hasDoubleClick` in the frontend. Pass the effective
 * config, i.e. with the card's own defaults (e.g. button card: `hold_action: more-info`) merged in.
 *
 * @param defaultTap action used when `tap_action` is missing (frontend default: more-info).
 * @param entityIdSubstitution see [ActionHandler.handle].
 * @param pressColor overlay color while pressed (`--ha-ripple-color`).
 */
@Composable
fun Modifier.haActions(
    ctx: CardContext,
    config: JsonObject,
    defaultTap: JsonObject? = null,
    entityIdSubstitution: String? = null,
    pressColor: Color = HaTheme.colors.primaryText,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val interactionSource = remember { MutableInteractionSource() }
    val hasHold = ActionHandler.hasAction(config, ActionTrigger.HOLD)
    val hasDouble = ActionHandler.hasAction(config, ActionTrigger.DOUBLE_TAP)
    val actions = ctx.actions
    return combinedClickable(
        interactionSource = interactionSource,
        indication = remember(pressColor) { PressIndication(pressColor) },
        onClick = { actions.handle(config, ActionTrigger.TAP, defaultTap, entityIdSubstitution) },
        onLongClick = if (hasHold) ({ actions.handle(config, ActionTrigger.HOLD, null, entityIdSubstitution) }) else null,
        onDoubleClick = if (hasDouble) ({ actions.handle(config, ActionTrigger.DOUBLE_TAP, null, entityIdSubstitution) }) else null,
    )
}
