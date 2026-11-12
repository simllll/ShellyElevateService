package com.stretter.shellyelevateservice.dashboard.lovelace

import kotlin.math.max
import kotlin.math.min

/**
 * The parts of CSS grid the frontend layouts rely on, as pure functions (unit tested):
 * auto-placement (`grid-auto-flow: row` / `row dense`) and sizing of `auto` row tracks.
 */
object GridMath {

    data class Item(val columnSpan: Int, val rowSpan: Int = 1)

    data class Placement(val row: Int, val column: Int, val columnSpan: Int, val rowSpan: Int)

    /**
     * CSS grid auto-placement of [items] into [columns] columns. Spans are clamped to the grid.
     * Sparse (default) never places an item before the previous one; [dense] fills holes.
     */
    fun place(items: List<Item>, columns: Int, dense: Boolean = false): List<Placement> {
        val cols = max(columns, 1)
        val occupied = ArrayList<BooleanArray>()
        fun isFree(row: Int, col: Int, cs: Int, rs: Int): Boolean {
            if (col + cs > cols) return false
            for (r in row until row + rs) {
                val line = occupied.getOrNull(r) ?: continue
                for (c in col until col + cs) if (line[c]) return false
            }
            return true
        }
        fun occupy(p: Placement) {
            while (occupied.size < p.row + p.rowSpan) occupied += BooleanArray(cols)
            for (r in p.row until p.row + p.rowSpan) for (c in p.column until p.column + p.columnSpan) occupied[r][c] = true
        }

        var cursorRow = 0
        var cursorCol = 0
        return items.map { item ->
            val cs = item.columnSpan.coerceIn(1, cols)
            val rs = max(item.rowSpan, 1)
            var row = if (dense) 0 else cursorRow
            var col = if (dense) 0 else cursorCol
            while (!isFree(row, col, cs, rs)) {
                col++
                if (col + cs > cols) {
                    col = 0
                    row++
                }
            }
            val p = Placement(row, col, cs, rs)
            occupy(p)
            if (!dense) {
                cursorRow = row
                cursorCol = col + cs
            }
            p
        }
    }

    /** Number of row tracks used by [placements]. */
    fun rowCount(placements: List<Placement>): Int = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0

    /**
     * Heights of `auto` row tracks for items of [heights] (px), like CSS: single-row items set the
     * track to their height; taller spanning items grow the spanned tracks that have no single-row
     * content (evenly), or all spanned tracks if each has some. [gap] is the row gap.
     */
    fun trackHeights(placements: List<Placement>, heights: List<Int>, gap: Int): IntArray {
        val tracks = IntArray(rowCount(placements))
        // Tracks sized by single-row items are "limited" (CSS growth limit = their content size).
        val limited = BooleanArray(tracks.size)
        placements.forEachIndexed { i, p ->
            if (p.rowSpan == 1) {
                tracks[p.row] = max(tracks[p.row], heights[i])
                limited[p.row] = true
            }
        }
        placements.forEachIndexed { i, p ->
            if (p.rowSpan <= 1) return@forEachIndexed
            val spanned = (p.row until p.row + p.rowSpan)
            val available = spanned.sumOf { tracks[it] } + gap * (p.rowSpan - 1)
            val missing = heights[i] - available
            if (missing <= 0) return@forEachIndexed
            // Extra space goes to tracks that can still grow; only if there are none, to all of them.
            val growable = spanned.filter { !limited[it] }.ifEmpty { spanned.toList() }
            val each = missing / growable.size
            var rest = missing - each * growable.size
            for (r in growable) {
                tracks[r] += each + if (rest > 0) 1 else 0
                if (rest > 0) rest--
            }
        }
        return tracks
    }

    /** Top offsets of each track. */
    fun trackOffsets(tracks: IntArray, gap: Int): IntArray {
        val offsets = IntArray(tracks.size)
        var y = 0
        for (i in tracks.indices) {
            offsets[i] = y
            y += tracks[i] + gap
        }
        return offsets
    }

    /** Total height of [tracks] including gaps. */
    fun totalHeight(tracks: IntArray, gap: Int): Int =
        if (tracks.isEmpty()) 0 else tracks.sum() + gap * (tracks.size - 1)

    /** Whether item [index] shares one of its rows with another item (then it may need stretching). */
    fun sharesRow(placements: List<Placement>, index: Int): Boolean {
        val p = placements[index]
        return placements.withIndex().any { (j, o) ->
            j != index && o.row < p.row + p.rowSpan && p.row < o.row + o.rowSpan
        }
    }

    /**
     * Column count of the sections view (`hui-sections-view`): as many min-width columns as fit,
     * clamped to `max_columns` and to the total span of the visible sections.
     */
    fun sectionColumns(width: Float, padding: Float, minColumnWidth: Float, columnGap: Float, maxColumns: Int, totalSpan: Int): Int {
        val fit = ((width - padding + columnGap) / (minColumnWidth + columnGap)).toInt().coerceAtLeast(1)
        val clamped = min(fit, max(maxColumns, 1))
        return max(min(clamped, totalSpan), 1)
    }
}
