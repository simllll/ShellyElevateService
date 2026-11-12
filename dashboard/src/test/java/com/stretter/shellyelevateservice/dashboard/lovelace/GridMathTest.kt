package com.stretter.shellyelevateservice.dashboard.lovelace

import com.stretter.shellyelevateservice.dashboard.lovelace.GridMath.Item
import com.stretter.shellyelevateservice.dashboard.lovelace.GridMath.Placement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GridMathTest {
    @Test
    fun `items flow left to right and wrap`() {
        val p = GridMath.place(listOf(Item(6), Item(6), Item(12), Item(3)), 12)
        assertEquals(listOf(Placement(0, 0, 6, 1), Placement(0, 6, 6, 1), Placement(1, 0, 12, 1), Placement(2, 0, 3, 1)), p)
    }

    @Test
    fun `sparse placement never goes back, dense fills holes`() {
        val items = listOf(Item(8), Item(6), Item(4))
        val sparse = GridMath.place(items, 12)
        assertEquals(Placement(1, 6, 4, 1), sparse[2])
        val dense = GridMath.place(items, 12, dense = true)
        assertEquals(Placement(0, 8, 4, 1), dense[2])
    }

    @Test
    fun `row spans block cells below`() {
        val p = GridMath.place(listOf(Item(6, 2), Item(6), Item(6)), 12)
        assertEquals(Placement(0, 0, 6, 2), p[0])
        assertEquals(Placement(0, 6, 6, 1), p[1])
        assertEquals(Placement(1, 6, 6, 1), p[2])
    }

    @Test
    fun `spans wider than the grid are clamped`() {
        assertEquals(Placement(0, 0, 12, 1), GridMath.place(listOf(Item(36)), 12).single())
    }

    @Test
    fun `track heights follow the tallest single-row item`() {
        val p = GridMath.place(listOf(Item(6), Item(6), Item(12)), 12)
        assertArrayEquals(intArrayOf(80, 40), GridMath.trackHeights(p, listOf(56, 80, 40), gap = 8))
        assertEquals(128, GridMath.totalHeight(intArrayOf(80, 40), 8))
        assertArrayEquals(intArrayOf(0, 88), GridMath.trackOffsets(intArrayOf(80, 40), 8))
    }

    @Test
    fun `fixed two-row cards spread over empty tracks like CSS`() {
        // A rows: 2 card is 2 * (56 + 8) - 8 = 120px high and spans two auto tracks.
        val p = GridMath.place(listOf(Item(6, 2), Item(6)), 12)
        val tracks = GridMath.trackHeights(p, listOf(120, 56), gap = 8)
        assertArrayEquals(intArrayOf(56, 56), tracks)
    }

    @Test
    fun `shares row detection`() {
        val p = GridMath.place(listOf(Item(6), Item(6), Item(12)), 12)
        assertEquals(true, GridMath.sharesRow(p, 0))
        assertEquals(false, GridMath.sharesRow(p, 2))
    }

    @Test
    fun `sections view column count matches the frontend at 1280px`() {
        // 1280 wide, 32px padding each side, 320px min columns, 32px gaps, max_columns 3, 5 sections (one spanning 3).
        assertEquals(3, GridMath.sectionColumns(1280f, 64f, 320f, 32f, 3, 7))
        assertEquals(2, GridMath.sectionColumns(900f, 64f, 320f, 32f, 4, 7))
        assertEquals(1, GridMath.sectionColumns(1280f, 64f, 320f, 32f, 4, 1))
    }
}
