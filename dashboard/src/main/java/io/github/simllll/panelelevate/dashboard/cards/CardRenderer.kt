package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import io.github.simllll.panelelevate.dashboard.json.asInt
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Renders one Lovelace card type natively.
 *
 * A renderer is created once per card config by its [CardFactory] (parse the config there, not in
 * [Content]) and lives as long as the card is on screen.
 *
 * Contract for implementations:
 * - Apply `modifier` to the root. In a grid section it carries the cell size; a card with fixed
 *   `rows` gets an exact height, an auto-height card a minimum height (CSS `align-items: stretch`).
 *   Most cards wrap their content in [HaCard], which honours both.
 * - Read entity states through `ctx.entity(id)` and templates through `ctx.template(…)` so only
 *   what is on screen is subscribed, and only the card that reads a state recomposes.
 * - Never throw from [Content]; render a [WarningCard] for bad configs instead.
 */
interface CardRenderer {
    /**
     * Default size in a grid section, like `getGridOptions()` in the frontend. The card config's
     * `grid_options` (or legacy `layout_options`) are merged on top by the layout.
     */
    fun gridOptions(): GridOptions = GridOptions()

    /**
     * False hides the card and frees its grid cell (the frontend's `hidden`/`card-visibility-changed`),
     * e.g. auto-entities with `show_empty: false`. Called in composition: read state here so it updates.
     * The generic `visibility` conditions of the config are handled by the host, not here.
     */
    @Composable
    fun isVisible(ctx: CardContext): Boolean = true

    /**
     * Composed while the card is part of the view, even when [isVisible] is false. For non-visual
     * work such as registering a pop-up (`PopupHost.HashPopup`) or keeping a subscription alive.
     */
    @Composable
    fun Effects(ctx: CardContext) {}

    @Composable
    fun Content(ctx: CardContext, modifier: Modifier)
}

/** Creates the renderer for one card config. May throw for invalid configs (an error card is shown). */
fun interface CardFactory {
    fun create(config: JsonObject): CardRenderer
}

/**
 * Grid size of a card (`LovelaceGridOptions`). [columns] is in 12ths of a section column;
 * [FULL] spans the whole section. [rows] null means "auto" (content height, spans one grid row).
 */
@Immutable
data class GridOptions(
    val columns: Int? = null,
    val rows: Int? = null,
    val minColumns: Int? = null,
    val maxColumns: Int? = null,
    val minRows: Int? = null,
    val maxRows: Int? = null,
    /** True when [rows] was given explicitly as "auto" (overrides a numeric default). */
    val rowsAuto: Boolean = false,
) {
    /** Merges config options over these defaults (`{...elementOptions, ...configOptions}`). */
    fun mergedWith(config: GridOptions): GridOptions = GridOptions(
        columns = config.columns ?: columns,
        rows = if (config.rowsAuto) null else config.rows ?: rows,
        minColumns = config.minColumns ?: minColumns,
        maxColumns = config.maxColumns ?: maxColumns,
        minRows = config.minRows ?: minRows,
        maxRows = config.maxRows ?: maxRows,
        rowsAuto = config.rowsAuto || (config.rows == null && rowsAuto),
    )

    /** `computeCardGridSize`: defaults (12 columns, auto rows) and min/max clamping. */
    fun resolve(): CardGridSize {
        val cols = columns ?: DEFAULT_COLUMNS
        val clampedCols = if (cols == FULL) FULL else cols.clamp(minColumns, maxColumns)
        val clampedRows = if (rowsAuto) null else rows?.clamp(minRows, maxRows)
        return CardGridSize(clampedCols, clampedRows)
    }

    private fun Int.clamp(min: Int?, max: Int?): Int {
        var v = this
        if (min != null && v < min) v = min
        if (max != null && v > max) v = max
        return v
    }

    companion object {
        const val FULL = Int.MAX_VALUE
        const val DEFAULT_COLUMNS = 12
        private const val LAYOUT_COLUMN_MULTIPLIER = 3

        /** Reads `grid_options`, or migrates legacy `layout_options` (columns × 3). */
        fun fromConfig(config: JsonObject): GridOptions {
            config["grid_options"].asObject()?.let { o ->
                return GridOptions(
                    columns = columnsOf(o["columns"]),
                    rows = o["rows"].asInt(),
                    minColumns = o["min_columns"].asInt(),
                    maxColumns = o["max_columns"].asInt(),
                    minRows = o["min_rows"].asInt(),
                    maxRows = o["max_rows"].asInt(),
                    rowsAuto = o["rows"].asString() == "auto",
                )
            }
            config["layout_options"].asObject()?.let { o ->
                return GridOptions(
                    columns = columnsOf(o["grid_columns"])?.let { if (it == FULL) it else it * LAYOUT_COLUMN_MULTIPLIER },
                    rows = o["grid_rows"].asInt(),
                    minColumns = o["grid_min_columns"].asInt()?.times(LAYOUT_COLUMN_MULTIPLIER),
                    maxColumns = o["grid_max_columns"].asInt()?.times(LAYOUT_COLUMN_MULTIPLIER),
                    minRows = o["grid_min_rows"].asInt(),
                    maxRows = o["grid_max_rows"].asInt(),
                    rowsAuto = o["grid_rows"].asString() == "auto",
                )
            }
            return GridOptions()
        }

        private fun columnsOf(e: JsonElement?): Int? = if (e.asString() == "full") FULL else e.asInt()
    }
}

/** Resolved grid size: [columns] (or [GridOptions.FULL]) and [rows] (null = auto). */
@Immutable
data class CardGridSize(val columns: Int, val rows: Int?)
