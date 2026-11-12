package com.stretter.shellyelevateservice.dashboard.lovelace

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MasonryMathTest {
    private fun card(json: String): JsonObject = HaJson.parseToJsonElement(json).jsonObject

    @Test
    fun `column count follows the 300, 600, 900 and 1200px breakpoints`() {
        assertEquals(1, MasonryMath.columnCount(200f))
        assertEquals(1, MasonryMath.columnCount(480f))
        assertEquals(1, MasonryMath.columnCount(599f))
        assertEquals(2, MasonryMath.columnCount(600f))
        assertEquals(3, MasonryMath.columnCount(1024f))
        assertEquals(4, MasonryMath.columnCount(1280f))
    }

    @Test
    fun `small columns are filled before the smallest one is chosen`() {
        // Column 0 takes cards until it reaches size 5, then column 1, then the smallest.
        assertEquals(listOf(0, 0, 0, 1, 1, 2), MasonryMath.assignColumns(listOf(1.0, 2.0, 3.0, 4.0, 1.0, 1.0), 3))
    }

    @Test
    fun `large cards go to the currently smallest column`() {
        assertEquals(listOf(0, 1, 2, 2, 1), MasonryMath.assignColumns(listOf(6.0, 5.0, 3.0, 3.0, 1.0), 3))
    }

    @Test
    fun `no more columns than cards and one column on narrow screens`() {
        assertEquals(listOf(0, 1), MasonryMath.assignColumns(listOf(9.0, 9.0), 4))
        assertEquals(listOf(0, 0, 0), MasonryMath.assignColumns(listOf(9.0, 9.0, 9.0), 1))
        assertEquals(emptyList<Int>(), MasonryMath.assignColumns(emptyList(), 3))
    }

    @Test
    fun `card sizes follow the frontend getCardSize`() {
        assertEquals(5.0, MasonryMath.cardSize(card("""{"type":"entities","title":"T","entities":["a.b","c.d","e.f"]}""")), 0.0)
        assertEquals(1.0, MasonryMath.cardSize(card("""{"type":"entities","entities":[]}""")), 0.0)
        // 3 lines → round(1.5) = 2, plus 1 for the title
        assertEquals(3.0, MasonryMath.cardSize(card("""{"type":"markdown","title":"x","content":"a\nb\nc"}""")), 0.0)
        assertEquals(7.0, MasonryMath.cardSize(card("""{"type":"markdown","card_size":7,"content":"a"}""")), 0.0)
        assertEquals(5.0, MasonryMath.cardSize(card("""{"type":"picture","image":"/local/x.png"}""")), 0.0)
        assertEquals(5.0, MasonryMath.cardSize(card("""{"type":"button","entity":"light.x"}""")), 0.0)
        assertEquals(3.0, MasonryMath.cardSize(card("""{"type":"custom:button-card"}""")), 0.0)
        assertEquals(5.0, MasonryMath.cardSize(card("""{"type":"iframe","url":"/local/v.mp4","aspect_ratio":"100%"}""")), 0.0)
        assertEquals(3.0, MasonryMath.cardSize(card("""{"type":"iframe","url":"/x"}""")), 0.0)
        assertEquals(1.0, MasonryMath.cardSize(card("""{"type":"custom:unknown"}""")), 0.0)
    }

    @Test
    fun `stacks sum or take the maximum of their children`() {
        val vertical = card("""{"type":"vertical-stack","cards":[{"type":"custom:button-card"},{"type":"heading"},{"type":"picture","image":"x"}]}""")
        assertEquals(9.0, MasonryMath.cardSize(vertical), 0.0)
        val horizontal = card("""{"type":"horizontal-stack","cards":[{"type":"custom:button-card"},{"type":"picture","image":"x"}]}""")
        assertEquals(5.0, MasonryMath.cardSize(horizontal), 0.0)
        assertEquals(0.0, MasonryMath.cardSize(card("""{"type":"horizontal-stack","cards":[]}""")), 0.0)
    }

    @Test
    fun `square grids use the row height table, others the tallest card per row`() {
        val square = card("""{"type":"grid","columns":3,"cards":[{"type":"heading"},{"type":"picture","image":"x"},{"type":"heading"},{"type":"heading"}]}""")
        assertEquals(4.0 / 3 * 2, MasonryMath.cardSize(square), 1e-9)
        val few = card("""{"type":"grid","square":true,"cards":[{"type":"heading"}]}""")
        assertEquals(2.0, MasonryMath.cardSize(few), 0.0)
        val flat = card("""{"type":"grid","columns":2,"square":false,"title":"t","cards":[{"type":"heading"},{"type":"picture","image":"x"},{"type":"custom:button-card"}]}""")
        assertEquals(1.0 + 5 + 3, MasonryMath.cardSize(flat), 0.0)
    }

    @Test
    fun `auto-entities is sized from its wrapped card`() {
        val auto = card("""{"type":"custom:auto-entities","card":{"type":"entities","title":"Eingeschaltet"},"filter":{"include":[{"domain":"light"}]}}""")
        assertEquals(3.0, MasonryMath.cardSize(auto), 0.0)
        val plain = card("""{"type":"custom:auto-entities","card":{"type":"entities"},"entities":["a.b","c.d"]}""")
        assertEquals(2.0, MasonryMath.cardSize(plain), 0.0)
    }
}
