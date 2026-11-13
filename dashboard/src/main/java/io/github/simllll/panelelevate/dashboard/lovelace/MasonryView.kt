package io.github.simllll.panelelevate.dashboard.lovelace

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.badges.BadgeSpec
import io.github.simllll.panelelevate.dashboard.badges.ViewBadges
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.cards.CardLayout
import io.github.simllll.panelelevate.dashboard.cards.RenderCard
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The default view (`hui-masonry-view`, a view without `type`): badges on top, then the cards in
 * [MasonryMath.columnCount] columns, each card assigned once by its estimated size
 * ([MasonryMath.assignColumns]). Scrolls vertically.
 *
 * Geometry as in the frontend: 4px top padding; badges with `4px 8px` margins; columns as
 * `flex: 1 0 0` with `max-width: 500px` (600px below 600px width), centered, inside 4px side
 * margins; cards with `4px 4px 8px` margins (the frontend's `max-width: 500px` rule that drops the
 * side margins loses to a more specific selector, so they stay 4px), vertical margins collapsing
 * to 8px between cards. Hidden cards take no space; a column without visible cards collapses.
 */
@Composable
fun MasonryView(view: JsonObject, ctx: CardContext, modifier: Modifier = Modifier) {
    val cards = remember(view) { MasonryMath.cards(view) }
    val badges = remember(view) { BadgeSpec.parseAll(view) }
    val sizes = remember(cards) { cards.map { MasonryMath.cardSize(it) } }
    val cardCtx = remember(ctx) { ctx.withLayout(CardLayout.STACK) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = MasonryMath.columnCount(maxWidth.value)
        val assignment = remember(sizes, columns) { MasonryMath.assignColumns(sizes, columns) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp)
        ) {
            ViewBadges(badges, ctx, Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalMargin = 4.dp)
            MasonryColumns(columnCount = assignment.maxOrNull()?.plus(1) ?: 0, modifier = Modifier.fillMaxWidth()) {
                cards.forEachIndexed { i, card ->
                    key(card) {
                        cardCtx.RenderCard(card, Modifier.layoutId(assignment[i]))
                    }
                }
            }
        }
    }
}

/**
 * Lays out cards (direct children tagged with their column index via `layoutId`) in columns.
 * Only columns with at least one emitted (= visible) card share the width, like the frontend's
 * `.column:not(:has(> *:not([hidden]))) { flex-grow: 0 }`.
 */
@Composable
private fun MasonryColumns(columnCount: Int, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val side = 4.dp.roundToPx()          // #columns margin and card side margin
        val top = 4.dp.roundToPx()           // card margin-top
        val gap = 8.dp.roundToPx()           // collapsed margin between cards / last card's margin-bottom
        val maxColumnWidth = (if (width.toDp() < 600.dp) 600.dp else 500.dp).roundToPx()

        val byColumn = Array(max(columnCount, 0)) { ArrayList<Int>() }
        measurables.forEachIndexed { i, m ->
            val column = (m.layoutId as? Int)?.takeIf { it in byColumn.indices } ?: 0
            if (byColumn.isNotEmpty()) byColumn[column] += i
        }
        val used = byColumn.indices.filter { byColumn[it].isNotEmpty() }
        if (used.isEmpty()) return@Layout layout(width, 0) {}

        val available = (width - 2 * side).coerceAtLeast(0)
        val columnWidth = min(available.toFloat() / used.size, maxColumnWidth.toFloat())
        val startX = side + (available - columnWidth * used.size) / 2f
        val cardWidth = (columnWidth - 2 * side).roundToInt().coerceAtLeast(0)
        val cardConstraints = Constraints(minWidth = cardWidth, maxWidth = cardWidth)

        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        val positions = IntArray(measurables.size * 2)
        var height = 0
        used.forEachIndexed { slot, column ->
            val x = (startX + slot * columnWidth).roundToInt() + side
            var y = top
            byColumn[column].forEach { i ->
                val p = measurables[i].measure(cardConstraints)
                placeables[i] = p
                positions[2 * i] = x
                positions[2 * i + 1] = y
                y += p.height + gap
            }
            height = max(height, y)
        }
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p?.place(positions[2 * i], positions[2 * i + 1]) }
        }
    }
}
