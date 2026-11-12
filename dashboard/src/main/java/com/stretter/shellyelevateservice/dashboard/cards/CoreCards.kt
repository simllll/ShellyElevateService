package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.actions.ActionHandler
import com.stretter.shellyelevateservice.dashboard.actions.ActionTrigger
import com.stretter.shellyelevateservice.dashboard.actions.haActions
import com.stretter.shellyelevateservice.dashboard.hass.TemplateResult
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.image.RemoteImage
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.int
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.lovelace.CardGrid
import com.stretter.shellyelevateservice.dashboard.lovelace.StretchRow
import com.stretter.shellyelevateservice.dashboard.markdown.Markdown
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.roundToInt

/** `hui-heading-card`: title (16px) or subtitle (14px medium, secondary) with optional icon, bottom aligned. */
class HeadingCard(private val config: JsonObject) : CardRenderer {
    override fun gridOptions() = GridOptions(columns = GridOptions.FULL, minColumns = 3)

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val t = HaTheme.typography
        val c = HaTheme.colors
        val subtitle = config.string("heading_style") == "subtitle"
        val actionable = ActionHandler.hasAction(config, ActionTrigger.TAP)
        val color = if (subtitle) c.secondaryText else c.primaryText
        val style = if (subtitle) t.style(t.sizeM, FontWeight.Medium, t.lineHeightNormal, 0.1.sp, color)
        else t.style(t.sizeL, FontWeight.Normal, t.lineHeightNormal, 0.1.sp, color)
        Box(modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.BottomStart) {
            Row(
                Modifier
                    .padding(horizontal = 4.dp)
                    .then(if (actionable) Modifier.haActions(ctx, config) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                config.string("icon")?.let { MdiIcon(it, color, size = 18.dp) }
                config.string("heading")?.let {
                    BasicText(it, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (actionable) MdiIcon("mdi:chevron-right", color, size = 18.dp)
            }
        }
    }
}

/**
 * `hui-markdown-card`: content rendered via `render_template` (variables: `config`), optional
 * title header, `text_only` without card chrome, `show_empty: false` hides an empty result.
 */
class MarkdownCard(private val config: JsonObject) : CardRenderer {
    private val content = config.string("content").orEmpty()
    private val variables = jsonOf("config" to config)

    @Composable
    override fun isVisible(ctx: CardContext): Boolean {
        if (config.bool("show_empty") != false) return true
        val result = ctx.template(content, variables)
        return !(result is TemplateResult.Value && result.text.isEmpty())
    }

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val result = ctx.template(content, variables)
        val textOnly = config.bool("text_only") == true
        val title = if (textOnly) null else config.string("title")
        HaCard(
            modifier.fillMaxWidth(),
            background = if (textOnly) Color.Transparent else HaTheme.colors.cardBackground,
            border = !textOnly,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (config.containsKey("tap_action")) Modifier.haActions(ctx, config) else Modifier)
            ) {
                if (title != null) CardHeader(title)
                val padding = when {
                    textOnly -> Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    title != null -> Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    else -> Modifier.padding(16.dp)
                }
                when (result) {
                    is TemplateResult.Value -> Markdown(result.text, padding.fillMaxWidth())
                    is TemplateResult.Error -> BasicText(
                        result.message,
                        style = HaTheme.typography.body.copy(color = HaTheme.colors.error),
                        modifier = padding,
                    )
                    null -> Box(padding.heightIn(min = 22.dp))
                }
            }
        }
    }
}

/** `hui-picture-card`: an image (`image` URL or `image_entity` picture) at full width, rounded. */
class PictureCard(private val config: JsonObject) : CardRenderer {
    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val imageEntity = ctx.entity(config.string("image_entity"))
        val url = imageEntity?.entityPicture ?: config.string("image")
        val interactive = ActionHandler.hasAction(config, ActionTrigger.TAP) || ActionHandler.hasAction(config, ActionTrigger.HOLD)
        HaCard(modifier.fillMaxWidth()) {
            RemoteImage(
                ctx.images,
                url,
                Modifier
                    .fillMaxWidth()
                    .then(if (interactive) Modifier.haActions(ctx, config) else Modifier),
                contentScale = ContentScale.FillWidth,
                keepAspectRatio = true,
                contentDescription = config.string("alt_text"),
            )
        }
    }
}

/** Optional stack/grid title (`.card-header` of hui-stack-card). */
@Composable
private fun StackTitle(title: String?) {
    if (title.isNullOrEmpty()) return
    val t = HaTheme.typography
    BasicText(title, style = t.cardHeader.copy(color = HaTheme.colors.primaryText), modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
}

/** `hui-vertical-stack-card`: cards in a column with 8px gaps. */
class VerticalStackCard(private val config: JsonObject) : CardRenderer {
    private val cards = config.objects("cards")

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val child = ctx.withLayout(CardLayout.STACK)
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HaTheme.dimens.stackGap)) {
            StackTitle(config.string("title"))
            cards.forEach { card -> key(card) { child.RenderCard(card, Modifier.fillMaxWidth()) } }
        }
    }
}

/** `hui-horizontal-stack-card`: equal-width cards (`flex: 1 1 0`), 8px gaps, stretched to equal height. */
class HorizontalStackCard(private val config: JsonObject) : CardRenderer {
    private val cards = config.objects("cards")

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val child = ctx.withLayout(CardLayout.STACK)
        Column(modifier.fillMaxWidth()) {
            StackTitle(config.string("title"))
            StretchRow(HaTheme.dimens.stackGap, Modifier.fillMaxWidth()) {
                cards.forEach { card -> key(card) { child.RenderCard(card) } }
            }
        }
    }
}

/** `hui-grid-card`: `columns` (default 3) equal columns, square cells unless `square: false`. */
class GridCard(private val config: JsonObject) : CardRenderer {
    private val cards = config.objects("cards")
    private val columns = (config.int("columns") ?: 3).coerceAtLeast(1)
    private val square = config.bool("square") != false

    @Composable
    override fun Content(ctx: CardContext, modifier: Modifier) {
        val child = ctx.withLayout(CardLayout.STACK)
        val gap = HaTheme.dimens.stackGap
        Column(modifier.fillMaxWidth()) {
            StackTitle(config.string("title"))
            if (square) {
                SquareGrid(columns, Modifier.fillMaxWidth()) {
                    cards.forEach { card -> key(card) { child.RenderCard(card, Modifier.fillMaxSize()) } }
                }
            } else {
                CardGrid(
                    columns = columns,
                    sizes = List(cards.size) { CardGridSize(1, null) },
                    rowHeight = 0.dp,
                    rowGap = gap,
                    columnGap = gap,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    cards.forEach { card -> key(card) { child.RenderCard(card) } }
                }
            }
        }
    }
}

/** Square cells (`grid-auto-rows: 1fr` with a square first row): every row as tall as a cell is wide. */
@Composable
private fun SquareGrid(columns: Int, modifier: Modifier, content: @Composable () -> Unit) {
    val gapDp = HaTheme.dimens.stackGap
    Layout(content, modifier) { measurables, constraints ->
        val gap = gapDp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val cell = (width - gap * (columns - 1)).toFloat() / columns
        val cellPx = cell.roundToInt()
        val placeables = measurables.map { it.measure(Constraints(cellPx, cellPx, cellPx, Constraints.Infinity)) }
        val rowHeight = max(cellPx, placeables.maxOfOrNull { it.height } ?: 0)
        val rows = (placeables.size + columns - 1) / columns
        val height = if (rows == 0) 0 else rows * rowHeight + (rows - 1) * gap
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                p.place(((i % columns) * (cell + gap)).roundToInt(), (i / columns) * (rowHeight + gap))
            }
        }
    }
}
