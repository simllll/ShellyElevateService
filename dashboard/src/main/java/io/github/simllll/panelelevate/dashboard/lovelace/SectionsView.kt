package io.github.simllll.panelelevate.dashboard.lovelace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardGridSize
import io.github.simllll.panelelevate.dashboard.cards.CardRenderer
import io.github.simllll.panelelevate.dashboard.cards.GridOptions
import io.github.simllll.panelelevate.dashboard.cards.conditionsMet
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.int
import io.github.simllll.panelelevate.dashboard.json.objects
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_MAX_COLUMNS = 4

/**
 * A view of type `sections` (`hui-sections-view`): sections in a centered grid of 320–500px columns
 * (at most `max_columns`), 32px column gap, 24px row gap and outer padding, optional
 * `dense_section_placement`. Scrolls vertically. Badges, header and sidebar are not rendered
 * (kiosk mode hides them on the wall display).
 */
@Composable
fun SectionsView(view: JsonObject, ctx: CardContext, modifier: Modifier = Modifier) {
    val d = HaTheme.dimens
    val sections = view.objects("sections")
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth <= 600.dp
        val columnGap = if (narrow) d.viewNarrowColumnGap else d.viewColumnGap
        val visible = sections.filter { ctx.conditionsMet(it) }
        val totalSpan = visible.sumOf { (it.int("column_span") ?: 1).coerceAtLeast(1) }
        val columns = GridMath.sectionColumns(
            width = maxWidth.value,
            padding = columnGap.value * 2,
            minColumnWidth = d.viewColumnMinWidth.value,
            columnGap = columnGap.value,
            maxColumns = view.int("max_columns") ?: DEFAULT_MAX_COLUMNS,
            totalSpan = totalSpan,
        )
        val maxContentWidth: Dp = d.viewColumnMaxWidth * columns + columnGap * (columns - 1)
        val spans = visible.map { GridMath.Item((it.int("column_span") ?: 1).coerceIn(1, columns), (it.int("row_span") ?: 1).coerceAtLeast(1)) }
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            SectionsGrid(
                columns = columns,
                spans = spans,
                rowGap = d.viewRowGap,
                columnGap = columnGap,
                dense = view.bool("dense_section_placement") == true,
                modifier = Modifier
                    .padding(horizontal = columnGap, vertical = d.viewRowGap)
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth(),
            ) {
                visible.forEachIndexed { i, section ->
                    key(section) {
                        Section(section, spans[i].columnSpan, ctx)
                    }
                }
            }
        }
    }
}

/** One section; `grid` is the only section type in current HA, so every section renders as one. */
@Composable
fun Section(section: JsonObject, columnSpan: Int, ctx: CardContext) {
    GridSection(section, columnSpan, ctx)
}

/**
 * `hui-grid-section`: a 12 × [columnSpan] column grid with 56px rows and 8px gaps. Cards size from
 * their renderer's [CardRenderer.gridOptions] merged with the config's `grid_options`.
 */
@Composable
fun GridSection(section: JsonObject, columnSpan: Int, ctx: CardContext) {
    val d = HaTheme.dimens
    val configs = section.objects("cards")
    val shown = configs.mapNotNull { config ->
        key(config) {
            val renderer = remember { ctx.cards.create(config) }
            // Effects run for every card, visible or not (pop-up registration etc.).
            renderer.Effects(ctx)
            val visible = renderer.isVisible(ctx)
            if (visible && ctx.conditionsMet(config)) config to renderer else null
        }
    }
    val sizes: List<CardGridSize> = shown.map { (config, renderer) ->
        renderer.gridOptions().mergedWith(GridOptions.fromConfig(config)).resolve()
    }
    CardGrid(
        columns = 12 * columnSpan,
        sizes = sizes,
        rowHeight = d.gridRowHeight,
        rowGap = d.gridRowGap,
        columnGap = d.gridColumnGap,
        modifier = Modifier.fillMaxWidth(),
    ) {
        shown.forEach { (config, renderer) ->
            key(config) {
                // Exactly one layout node per card, whatever the card emits; min constraints pass through.
                Box(propagateMinConstraints = true) { renderer.Content(ctx, Modifier) }
            }
        }
    }
}
