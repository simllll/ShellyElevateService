package com.stretter.shellyelevateservice.dashboard.lovelace

import com.stretter.shellyelevateservice.dashboard.json.array
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.int
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor
import kotlin.math.max

/**
 * Column logic of `hui-masonry-view` (frontend 20261028.5), pure so it can be unit tested.
 *
 * - [columnCount]: one column per matching `(min-width: 300/600/900/1200px)` query, at least one
 *   (the docked-sidebar correction does not apply: the wall display has no sidebar).
 * - [assignColumns]: cards are placed in order; each goes to the first column whose size is
 *   below 5, else to the smallest one (`getColumnIndex`). Sizes come from [cardSize].
 */
object MasonryMath {
    private val BREAKPOINTS = intArrayOf(300, 600, 900, 1200)

    /** Number of columns for a view [width] in CSS px. */
    fun columnCount(width: Float): Int = max(1, BREAKPOINTS.count { width >= it })

    /** `getColumnIndex`: picks the column for a card of [size] and adds the size to it. */
    fun columnIndex(columnSizes: DoubleArray, size: Double): Int {
        var minIndex = 0
        for (i in columnSizes.indices) {
            if (columnSizes[i] < 5) {
                minIndex = i
                break
            }
            if (columnSizes[i] < columnSizes[minIndex]) minIndex = i
        }
        columnSizes[minIndex] += size
        return minIndex
    }

    /**
     * Column index for every card. The view creates `min(columns, cards)` columns; columns that
     * end up empty are dropped by the caller (the frontend removes them from the DOM).
     */
    fun assignColumns(sizes: List<Double>, columns: Int): List<Int> {
        val count = minOf(columns, sizes.size)
        if (count <= 0) return emptyList()
        val columnSizes = DoubleArray(count)
        return sizes.map { columnIndex(columnSizes, it) }
    }

    /**
     * `computeCardSize` estimated from the config, following each card's `getCardSize()` (1 for
     * cards without one). Cards whose size depends on live data (auto-entities) are sized from
     * their static config, like the frontend does before the first update.
     */
    fun cardSize(config: JsonObject): Double = when (config.string("type")) {
        "entities" -> entitiesSize(config, config.array("entities")?.size ?: 0).toDouble()
        "markdown" -> (
            config.int("card_size")
                ?: (jsRound((config.string("content").orEmpty().split("\n").size) / 2.0) + if (config.string("title") != null) 1 else 0)
            ).toDouble()
        "vertical-stack" -> config.objects("cards").sumOf { cardSize(it) }
        "horizontal-stack" -> config.objects("cards").maxOfOrNull { cardSize(it) } ?: 0.0
        "grid" -> gridSize(config)
        "picture", "custom:refreshable-picture-card" -> 5.0
        "tile" -> 1.0 + (if (config.bool("vertical") == true) 1 else 0) + (config.array("features")?.size ?: 0)
        "button" -> ((if (config.bool("show_icon") != false) 4 else 0) + (if (config.bool("show_name") != false) 1 else 0)).toDouble()
        "heading" -> 1.0
        "iframe" -> 1 + (config.string("aspect_ratio")?.replace("%", "")?.trim()?.toDoubleOrNull() ?: 50.0) / 25
        "weather-forecast" -> 1.0 +
            (if (config.bool("show_current") != false) 1 else 0) +
            (if (config.bool("show_forecast") != false) 1 else 0) +
            (if (config.string("forecast_type") == "daily") 1 else 0)
        // `title ? 2 : 0 + 2 * n` (sic: the frontend's operator precedence)
        "history-graph" -> if (config.string("title") != null) 2.0 else 2.0 * max(1, config.array("entities")?.size ?: 0)
        "alarm-panel" -> 10.0
        "custom:fab-card" -> 0.0
        "entity" -> 2.0
        "energy-date-selection" -> 1.0
        "energy-devices-detail-graph" -> 3.0
        "custom:mushroom-climate-card" -> 1.0 + (if (config.bool("show_temperature_control") == true) 1 else 0)
        "custom:button-card" -> (config.int("card_size")?.takeIf { it != 0 } ?: 3).toDouble()
        "custom:auto-entities" -> autoEntitiesSize(config)
        "custom:mushroom-template-card" -> 1.0 + if (config.string("layout") == "vertical" || config.bool("vertical") == true) 1 else 0
        else -> 1.0
    }

    private fun entitiesSize(config: JsonObject, entities: Int): Int =
        (if (config.string("title") != null || config.bool("show_header_toggle") == true) 2 else 0) +
            max(entities, 1) +
            (if (config.obj("header") != null) 1 else 0) +
            (if (config.obj("footer") != null) 1 else 0)

    private fun gridSize(config: JsonObject): Double {
        val cards = config.objects("cards")
        val columns = config.int("columns")?.takeIf { it > 0 } ?: 3
        val title = if (config.string("title") != null) 1 else 0
        if (config.bool("square") != false) {
            val rowHeight = SQUARE_ROW_HEIGHTS[columns] ?: 1
            val rows = if (cards.size < columns) rowHeight.toDouble() else cards.size.toDouble() / columns * rowHeight
            return rows + title
        }
        val sizes = cards.map { cardSize(it) }
        return title + sizes.chunked(columns).sumOf { it.max() }
    }

    /** auto-entities: the wrapped card's size; 1 → number of entities, 0 → number of include rules. */
    private fun autoEntitiesSize(config: JsonObject): Double {
        val entities = config.array("entities")?.size ?: 0
        val card = config.obj("card") ?: JsonObject(mapOf("type" to JsonPrimitive("entities")))
        var size = if (card.string("type") == "entities") entitiesSize(card, entities).toDouble() else cardSize(card)
        if (size == 1.0 && entities > 0) size = entities.toDouble()
        if (size == 0.0) size = (config.obj("filter")?.array("include")?.size ?: 0).toDouble()
        return size
    }

    /** `Math.round` (halves round up). */
    private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()

    private val SQUARE_ROW_HEIGHTS = mapOf(1 to 5, 2 to 3, 3 to 2)

    /** The view's `cards`, skipping anything that is not an object. */
    fun cards(view: JsonObject): List<JsonObject> = view.array("cards")?.mapNotNull { it.asObject() } ?: emptyList()
}
