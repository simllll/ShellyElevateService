package com.stretter.shellyelevateservice.dashboard.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.cards.CardContext
import com.stretter.shellyelevateservice.dashboard.cards.TileCard
import com.stretter.shellyelevateservice.dashboard.cards.conditionsMet
import com.stretter.shellyelevateservice.dashboard.cards.entity
import com.stretter.shellyelevateservice.dashboard.cards.isTemplate
import com.stretter.shellyelevateservice.dashboard.cards.now
import com.stretter.shellyelevateservice.dashboard.cards.template
import com.stretter.shellyelevateservice.dashboard.hass.TemplateResult
import com.stretter.shellyelevateservice.dashboard.hass.isActive
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.image.RemoteImage
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlin.math.max

/** Height of a badge (`--ha-badge-size`). */
private val BADGE_SIZE = 36.dp

/**
 * `hui-view-badges`: the view's badges as a centered, wrapping row with 8px gaps
 * (`flex-wrap: wrap; justify-content: center; gap: var(--ha-space-2)`). Hidden badges
 * (`visibility`, empty Mushroom badges) emit nothing and take no space.
 *
 * [verticalMargin] is the element's top/bottom margin; without a visible badge the row is empty
 * and both margins collapse into one, as in CSS.
 */
@Composable
fun ViewBadges(badges: List<BadgeSpec>, ctx: CardContext, modifier: Modifier = Modifier, verticalMargin: Dp = 0.dp) {
    val gapDp = HaTheme.dimens.space2
    Layout(
        content = {
            badges.forEach { badge ->
                key(badge) {
                    if (ctx.conditionsMet(badge.config)) {
                        when (badge) {
                            is BadgeSpec.Entity -> EntityBadge(badge, ctx)
                            is BadgeSpec.MushroomTemplate -> MushroomTemplateBadge(badge, ctx)
                            is BadgeSpec.Unsupported -> BadgePill(PaddingValues(horizontal = 12.dp)) { BadgeInfo(null, badge.type) }
                        }
                    }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = gapDp.roundToPx()
        val margin = verticalMargin.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        // A badge is never wider than the row (its text ellipsizes instead of overflowing).
        val placeables = measurables.map { it.measure(Constraints(maxWidth = max(width, 0))) }
        val rows = ArrayList<MutableList<Int>>()
        var rowWidth = 0
        placeables.forEachIndexed { i, p ->
            val current = rows.lastOrNull()
            if (current == null || rowWidth + gap + p.width > width) {
                rows += mutableListOf(i)
                rowWidth = p.width
            } else {
                current += i
                rowWidth += gap + p.width
            }
        }
        val rowHeights = rows.map { row -> row.maxOf { placeables[it].height } }
        val height = if (rows.isEmpty()) margin else rowHeights.sum() + gap * (rows.size - 1) + 2 * margin
        layout(width, height) {
            var y = margin
            rows.forEachIndexed { r, row ->
                val used = row.sumOf { placeables[it].width } + gap * (row.size - 1)
                var x = (width - used) / 2
                // align-items: flex-start
                row.forEach { i ->
                    placeables[i].place(x, y)
                    x += placeables[i].width + gap
                }
                y += rowHeights[r] + gap
            }
        }
    }
}

/**
 * `hui-entity-badge`: icon in the state color (primary when active without a state color,
 * `--state-inactive-color` otherwise), state as content, name as small label above when both
 * are shown, icon only when neither. Tap defaults to more-info.
 */
@Composable
private fun EntityBadge(spec: BadgeSpec.Entity, ctx: CardContext) {
    val c = HaTheme.colors
    // Not rendered until the entity is known (the frontend shows "entity not found" instead).
    val entity = ctx.entity(spec.entityId) ?: return
    val active = entity.isActive()
    val color = when {
        spec.color != null -> if (active) CssColors.computeCssColor(spec.color) else null
        entity.domain == "light" -> TileCard.tileColor(entity, null)
        else -> CssColors.stateColor(entity)
    } ?: if (active) c.primary else c.stateInactive
    val name = spec.name ?: entity.friendlyName
    val state = if (spec.showState) ctx.formatter.formatState(entity, ctx.now()) else null
    val label = if (spec.showState && spec.showName) name else null
    val content = state ?: if (spec.showName) name else null
    val picture = if (spec.showEntityPicture) entity.entityPicture else null
    // `padding: 0 12px`, the slotted icon has `margin-inline-start: -4px` (a picture -10px).
    val start = when {
        content == null -> 0.dp
        !spec.showIcon -> 12.dp
        picture != null -> 2.dp
        else -> 8.dp
    }
    BadgePill(
        padding = PaddingValues(start = start, end = if (content == null) 0.dp else 12.dp),
        gap = HaTheme.dimens.space2,
        modifier = Modifier.haActions(ctx, spec.config, defaultTap = MORE_INFO, pressColor = color, enabled = ActionHandler.isInteractive(spec.config)),
    ) {
        if (spec.showIcon) {
            if (picture != null) RemoteImage(ctx.images, picture, Modifier.size(30.dp).clip(CircleShape))
            else MdiIcon(ctx.icons.icon(entity, spec.icon), color, size = 18.dp)
        }
        if (content != null) BadgeInfo(label, content, contentLineHeight = HaTheme.typography.lineHeightCondensed * 12f)
    }
}

/**
 * `custom:mushroom-template-badge`: `icon`, `color`, `label`, `content` and `picture` rendered
 * via `render_template` (variables `config` and `entity`). Picture (30px circle) wins over the
 * icon; without content the badge is a 36px circle. A badge whose templates are all empty (or
 * not rendered yet) is hidden instead of showing an empty pill.
 */
@Composable
private fun MushroomTemplateBadge(spec: BadgeSpec.MushroomTemplate, ctx: CardContext) {
    val c = HaTheme.colors
    val icon = templateValue(ctx, spec.icon, spec)
    val colorValue = templateValue(ctx, spec.color, spec)
    val content = templateValue(ctx, spec.content, spec)
    val label = templateValue(ctx, spec.label, spec)
    val picture = templateValue(ctx, spec.picture, spec)
    var pictureFailed by remember(picture) { mutableStateOf(false) }
    if (content == null && icon == null && picture == null) return
    // `--badge-color` unset → the icon inherits the text color.
    val color = colorValue?.let { CssColors.computeCssColor(it) } ?: c.primaryText
    val hasIcon = icon != null || picture != null
    // `padding: 0 8px`; a picture next to content has `margin-inline-start: -6px`.
    BadgePill(
        padding = when {
            content == null -> PaddingValues(0.dp)
            picture != null && !pictureFailed -> PaddingValues(start = 2.dp, end = 8.dp)
            else -> PaddingValues(horizontal = 8.dp)
        },
        gap = 8.dp,
        modifier = Modifier.haActions(ctx, spec.config, defaultTap = NONE, pressColor = color, enabled = ActionHandler.isInteractive(spec.config, defaultTap = false)),
    ) {
        // A picture that fails to load (404, SVG the renderer rejects) falls back to the icon.
        if (picture != null && !pictureFailed) {
            RemoteImage(ctx.images, picture, Modifier.size(30.dp).clip(CircleShape), onResult = { pictureFailed = !it })
        } else if (icon != null) MdiIcon(icon, color, size = 18.dp)
        if (content != null) {
            BadgeInfo(
                label,
                content,
                contentLineHeight = 16f,
                modifier = Modifier.padding(start = if (hasIcon) 0.dp else 4.dp, end = 4.dp),
            )
        }
    }
}

/** A Mushroom field: literal text, or the live template result (whitespace collapsed like HTML). */
@Composable
private fun templateValue(ctx: CardContext, value: String?, spec: BadgeSpec.MushroomTemplate): String? {
    if (value.isNullOrEmpty()) return null
    if (!isTemplate(value)) return BadgeSpec.collapseWhitespace(value)
    val result = ctx.template(value, spec.variables) as? TemplateResult.Value ?: return null
    return BadgeSpec.collapseWhitespace(result.text)
}

/**
 * `ha-badge` chrome: 36px tall pill (fully rounded), card background, 1px divider border,
 * content centered in a row.
 */
@Composable
private fun BadgePill(
    padding: PaddingValues,
    gap: Dp = 8.dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = HaTheme.colors
    val shape = RoundedCornerShape(BADGE_SIZE / 2)
    Row(
        Modifier
            .height(BADGE_SIZE)
            .widthIn(min = BADGE_SIZE)
            .clip(shape)
            .background(c.cardBackground, shape)
            .border(HaTheme.dimens.cardBorderWidth, c.divider, shape)
            .then(modifier)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        content = content,
    )
}

/** `.info`: optional label (10px/500, secondary, line-height 10px) above the content (12px/500). */
@Composable
private fun BadgeInfo(label: String?, content: String, contentLineHeight: Float = 16f, modifier: Modifier = Modifier) {
    val t = HaTheme.typography
    val c = HaTheme.colors
    Column(modifier, horizontalAlignment = Alignment.Start) {
        if (label != null) {
            BasicText(
                label,
                style = TextStyle(fontFamily = t.fontFamily, fontSize = t.sizeXs, fontWeight = FontWeight.Medium, lineHeight = 10.sp, letterSpacing = 0.1.sp, color = c.secondaryText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicText(
            content,
            style = TextStyle(fontFamily = t.fontFamily, fontSize = t.sizeS, fontWeight = FontWeight.Medium, lineHeight = contentLineHeight.sp, letterSpacing = 0.1.sp, color = c.primaryText),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val MORE_INFO = jsonOf("action" to "more-info")
private val NONE = jsonOf("action" to "none")
