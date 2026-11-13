package io.github.simllll.panelelevate.dashboard.lovelace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import io.github.simllll.panelelevate.dashboard.cards.CardGridSize
import io.github.simllll.panelelevate.dashboard.cards.GridOptions
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Natural height of [m] at [width], or null if the subtree does not support intrinsics
 * (SubcomposeLayout/lazy lists). Used to emulate CSS `align-items: stretch`.
 */
private fun intrinsicHeightOrNull(m: Measurable, width: Int): Int? = try {
    m.maxIntrinsicHeight(width)
} catch (e: IllegalStateException) {
    null
}

/**
 * The card grid of `hui-grid-section`: [columns] equal columns (12 × section span), cards placed by
 * CSS auto-placement. A card with numeric `rows` spans that many rows with the exact height
 * `rows × (rowHeight + rowGap) − rowGap`; auto cards take their content height and are stretched to
 * their row's height. [sizes] holds one entry per emitted child, in order.
 */
@Composable
fun CardGrid(
    columns: Int,
    sizes: List<CardGridSize>,
    rowHeight: Dp,
    rowGap: Dp,
    columnGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gapX = columnGap.roundToPx()
        val gapY = rowGap.roundToPx()
        val rowPx = rowHeight.roundToPx()
        val colWidth = (width - gapX * (columns - 1)).toFloat() / columns
        val n = minOf(measurables.size, sizes.size)
        val items = (0 until n).map { i ->
            val s = sizes[i]
            GridMath.Item(if (s.columns == GridOptions.FULL) columns else s.columns.coerceIn(1, columns), s.rows ?: 1)
        }
        val placements = GridMath.place(items, columns)
        fun widthOf(p: GridMath.Placement) = (colWidth * p.columnSpan + gapX * (p.columnSpan - 1)).roundToInt()

        val placeables = arrayOfNulls<Placeable>(n)
        val heights = MutableList(n) { 0 }
        val stretch = BooleanArray(n)
        for (i in 0 until n) {
            val p = placements[i]
            val w = widthOf(p)
            val rows = sizes[i].rows
            if (rows != null) {
                val h = rows * (rowPx + gapY) - gapY
                placeables[i] = measurables[i].measure(Constraints.fixed(w, h))
                heights[i] = h
            } else if (GridMath.sharesRow(placements, i)) {
                val natural = intrinsicHeightOrNull(measurables[i], w)
                if (natural != null) {
                    heights[i] = natural
                    stretch[i] = true
                } else {
                    placeables[i] = measurables[i].measure(Constraints(w, w, 0, Constraints.Infinity))
                    heights[i] = placeables[i]!!.height
                }
            } else {
                placeables[i] = measurables[i].measure(Constraints(w, w, 0, Constraints.Infinity))
                heights[i] = placeables[i]!!.height
            }
        }
        var tracks = GridMath.trackHeights(placements, heights, gapY)
        if (stretch.any { it }) {
            for (i in 0 until n) if (stretch[i]) {
                val p = placements[i]
                val target = tracks[p.row]
                placeables[i] = measurables[i].measure(Constraints(widthOf(p), widthOf(p), target, Constraints.Infinity))
                heights[i] = placeables[i]!!.height
            }
            tracks = GridMath.trackHeights(placements, heights, gapY)
        }
        val offsets = GridMath.trackOffsets(tracks, gapY)
        val height = GridMath.totalHeight(tracks, gapY)
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            for (i in 0 until n) {
                val p = placements[i]
                placeables[i]?.place((p.column * (colWidth + gapX)).roundToInt(), offsets[p.row])
            }
        }
    }
}

/**
 * The section grid of `hui-sections-view`: [columns] equal columns, sections spanning
 * `column_span` × `row_span`, `align-items: flex-start` (no stretching), optional dense placement.
 */
@Composable
fun SectionsGrid(
    columns: Int,
    spans: List<GridMath.Item>,
    rowGap: Dp,
    columnGap: Dp,
    dense: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gapX = columnGap.roundToPx()
        val gapY = rowGap.roundToPx()
        val colWidth = (width - gapX * (columns - 1)).toFloat() / columns
        val n = minOf(measurables.size, spans.size)
        val placements = GridMath.place(spans.take(n), columns, dense)
        val placeables = (0 until n).map { i ->
            val p = placements[i]
            val w = (colWidth * p.columnSpan + gapX * (p.columnSpan - 1)).roundToInt()
            measurables[i].measure(Constraints(w, w, 0, Constraints.Infinity))
        }
        val tracks = GridMath.trackHeights(placements, placeables.map { it.height }, gapY)
        val offsets = GridMath.trackOffsets(tracks, gapY)
        layout(width, GridMath.totalHeight(tracks, gapY).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            for (i in 0 until n) {
                val p = placements[i]
                placeables[i].place((p.column * (colWidth + gapX)).roundToInt(), offsets[p.row])
            }
        }
    }
}

/**
 * Flex row with `flex: 1 1 0` children and `align-items: stretch` (`hui-horizontal-stack-card`):
 * equal widths, all children as tall as the tallest.
 */
@Composable
fun StretchRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val gapPx = gap.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val childWidth = ((width - gapPx * (n - 1)).toFloat() / n)
        val widths = (0 until n).map { i -> ((i + 1) * childWidth).roundToInt() - (i * childWidth).roundToInt() }
        val fixedHeight = constraints.hasFixedHeight
        val naturals = if (n > 1 && !fixedHeight) measurables.mapIndexed { i, m -> intrinsicHeightOrNull(m, widths[i]) } else null
        val target = when {
            fixedHeight -> constraints.maxHeight
            naturals != null && naturals.all { it != null } -> max(naturals.maxOf { it!! }, constraints.minHeight)
            else -> constraints.minHeight
        }
        val placeables = measurables.mapIndexed { i, m ->
            m.measure(Constraints(widths[i], widths[i], target, if (fixedHeight) target else Constraints.Infinity))
        }
        val height = max(placeables.maxOf { it.height }, constraints.minHeight).coerceAtMost(constraints.maxHeight)
        layout(width, height) {
            var x = 0
            placeables.forEachIndexed { i, p ->
                p.place(x, 0)
                x += widths[i] + gapPx
            }
        }
    }
}
