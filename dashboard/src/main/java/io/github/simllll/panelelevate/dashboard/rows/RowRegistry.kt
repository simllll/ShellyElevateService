package io.github.simllll.panelelevate.dashboard.rows

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.simllll.panelelevate.dashboard.cards.CardContext
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Renders one row of an entities card (`hui-*-entity-row`, `section`, `divider`, custom rows like
 * `custom:template-entity-row`). Created once per row config by a [RowFactory].
 */
interface RowRenderer {
    /** False hides the row (no gap). Read state here so it updates. */
    @Composable
    fun isVisible(ctx: CardContext): Boolean = true

    @Composable
    fun Content(ctx: CardContext, modifier: Modifier)
}

fun interface RowFactory {
    fun create(config: JsonObject): RowRenderer
}

/**
 * Row types for the entities card. Rows without `type` are typed by domain like
 * `DOMAIN_TO_ELEMENT_TYPE` (create-row-element.ts). Unknown types show a placeholder row.
 */
class RowRegistry {
    private val factories = HashMap<String, RowFactory>()

    fun register(type: String, factory: RowFactory) {
        factories[type] = factory
    }

    /** Creates the renderer for a row; never throws. */
    fun create(config: JsonObject): RowRenderer {
        val type = config.string("type") ?: domainRowType(config.string("entity"))
        val factory = factories[type] ?: return PlaceholderRow(type)
        return try {
            factory.create(config)
        } catch (e: Exception) {
            Log.w("RowRegistry", "invalid $type row", e)
            PlaceholderRow("$type: ${e.message}")
        }
    }

    companion object {
        /** Entities card entries are `"light.x"` or `{entity: …, …}`. */
        fun normalize(entry: JsonElement): JsonObject? =
            entry.asObject() ?: entry.asString()?.let { jsonOf("entity" to it) }

        fun domainRowType(entityId: String?): String = when (entityId?.substringBefore('.')) {
            null -> "simple"
            "alert", "automation", "input_boolean", "light", "remote", "siren", "switch", "vacuum", "fan", "humidifier", "group" -> "toggle"
            "button" -> "button"
            "input_button" -> "input-button"
            "climate", "water_heater" -> "climate"
            "cover" -> "cover"
            "lock" -> "lock"
            "scene" -> "scene"
            "script" -> "script"
            "sensor" -> "sensor"
            "input_select", "select" -> "input-select"
            "input_number", "number" -> "input-number"
            "valve" -> "valve"
            else -> "simple"
        }
    }
}
