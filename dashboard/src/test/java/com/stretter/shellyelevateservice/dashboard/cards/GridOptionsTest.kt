package com.stretter.shellyelevateservice.dashboard.cards

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GridOptionsTest {
    private fun config(json: String) = HaJson.parseToJsonElement(json) as JsonObject

    @Test
    fun `defaults are 12 columns and auto rows`() {
        assertEquals(CardGridSize(12, null), GridOptions().resolve())
    }

    @Test
    fun `config grid_options override card defaults`() {
        val tile = GridOptions(columns = 6, rows = 1, minColumns = 6, minRows = 1)
        val merged = tile.mergedWith(GridOptions.fromConfig(config("""{"grid_options": {"columns": 12}}""")))
        assertEquals(CardGridSize(12, 1), merged.resolve())
    }

    @Test
    fun `full and auto are understood`() {
        val button = GridOptions(columns = 3, rows = 1)
        val merged = button.mergedWith(GridOptions.fromConfig(config("""{"grid_options": {"columns": "full", "rows": "auto"}}""")))
        assertEquals(CardGridSize(GridOptions.FULL, null), merged.resolve())
    }

    @Test
    fun `min and max clamp the size`() {
        val options = GridOptions(columns = 6, minColumns = 6).mergedWith(GridOptions.fromConfig(config("""{"grid_options": {"columns": 3}}""")))
        assertEquals(6, options.resolve().columns)
    }

    @Test
    fun `legacy layout_options are migrated`() {
        val options = GridOptions.fromConfig(config("""{"layout_options": {"grid_columns": 2, "grid_rows": 2}}"""))
        assertEquals(CardGridSize(6, 2), options.resolve())
    }
}
