package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.isActive
import io.github.simllll.panelelevate.dashboard.icons.MdiIcon
import io.github.simllll.panelelevate.dashboard.image.RemoteImage
import io.github.simllll.panelelevate.dashboard.json.asArray
import io.github.simllll.panelelevate.dashboard.json.asInt
import io.github.simllll.panelelevate.dashboard.theme.CssColors
import io.github.simllll.panelelevate.dashboard.theme.HaColors
import io.github.simllll.panelelevate.dashboard.theme.HaTheme

/**
 * Icon color of `<state-badge>` for an entity.
 * [color]: `"state"` (entities card default), `"none"`, a theme/CSS color, or null = state color
 * only for lights (the badge default).
 */
fun badgeColor(entity: EntityState?, color: String?, colors: HaColors): Color {
    if (entity == null) return colors.stateIcon
    val mode = color ?: if (entity.domain == "light") "state" else "none"
    if (entity.isUnavailable) return if (mode == "none") colors.stateIcon else colors.stateUnavailable
    return when (mode) {
        "none" -> colors.stateIcon
        "state" -> {
            if (!entity.isActive()) return colors.stateIcon
            val rgb = entity.attr("rgb_color").asArray()?.mapNotNull { it.asInt() }
            val base = if (rgb != null && rgb.size == 3) CssColors.fromRgb(rgb[0], rgb[1], rgb[2])
            else CssColors.stateColor(entity, inactiveColor = colors.stateIcon) ?: colors.stateIcon
            CssColors.brightnessFactor(entity)?.let { base.dimmed(it) } ?: base
        }
        else -> if (entity.isActive()) CssColors.computeCssColor(mode) ?: colors.stateIcon else colors.stateIcon
    }
}

/** CSS `filter: brightness(f)` approximation. */
fun Color.dimmed(factor: Float): Color =
    Color(red = (red * factor).coerceIn(0f, 1f), green = (green * factor).coerceIn(0f, 1f), blue = (blue * factor).coerceIn(0f, 1f), alpha = alpha)

/**
 * `<state-badge>`: 40px circle with the entity icon (24px) or its `entity_picture`.
 * [icon] overrides the entity icon, [image] the picture.
 */
@Composable
fun StateBadge(
    ctx: CardContext,
    entity: EntityState?,
    modifier: Modifier = Modifier,
    icon: String? = null,
    image: String? = null,
    color: String? = null,
    size: Dp = 40.dp,
) {
    val picture = image ?: if (icon == null) entity?.entityPicture else null
    // Only a picture needs the circular clip; a clip on every icon costs a layer per row.
    Box(modifier.size(size).then(if (picture != null) Modifier.clip(CircleShape) else Modifier), contentAlignment = Alignment.Center) {
        if (picture != null) {
            RemoteImage(ctx.images, picture, Modifier.size(size))
        } else {
            MdiIcon(ctx.icons.icon(entity, icon), badgeColor(entity, color, HaTheme.colors))
        }
    }
}
