package io.github.simllll.panelelevate.dashboard.cards

import android.util.Log
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.serialization.json.JsonObject

/**
 * Maps Lovelace card types (`entities`, `custom:button-card`, …) to [CardFactory]s.
 * Unknown types render a [PlaceholderCard]; factories that throw render a [WarningCard].
 *
 * Built-in registrations live in [CardCatalog]; add new card types there.
 */
class CardRegistry {
    private val factories = HashMap<String, CardFactory>()

    /** Registers [factory] for [type] (as written in the config, e.g. `custom:bubble-card`). */
    fun register(type: String, factory: CardFactory) {
        factories[type] = factory
    }

    fun isRegistered(type: String): Boolean = type in factories

    /** Creates the renderer for [config]; never throws. */
    fun create(config: JsonObject): CardRenderer {
        val type = config.string("type") ?: return WarningCard("Card without type")
        val factory = factories[type] ?: return PlaceholderCard(type)
        return try {
            factory.create(config)
        } catch (e: Exception) {
            Log.w("CardRegistry", "invalid $type config", e)
            WarningCard("$type: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
