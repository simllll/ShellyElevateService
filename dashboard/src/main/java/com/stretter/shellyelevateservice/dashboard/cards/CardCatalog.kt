package com.stretter.shellyelevateservice.dashboard.cards

import com.stretter.shellyelevateservice.dashboard.custom.registerCustomCards
import com.stretter.shellyelevateservice.dashboard.custom.registerCustomRows
import com.stretter.shellyelevateservice.dashboard.rows.RowRegistry
import com.stretter.shellyelevateservice.dashboard.rows.registerCoreRows

/**
 * All card and row types the dashboard knows. Add new types here (one line each); every type not
 * listed renders a [PlaceholderCard] showing its name.
 */
object CardCatalog {
    fun registerCards(registry: CardRegistry) {
        registry.register("heading") { HeadingCard(it) }
        registry.register("entities") { EntitiesCard(it) }
        registry.register("tile") { TileCard(it) }
        registry.register("button") { ButtonCard(it) }
        registry.register("markdown") { MarkdownCard(it) }
        registry.register("picture") { PictureCard(it) }
        registry.register("vertical-stack") { VerticalStackCard(it) }
        registry.register("horizontal-stack") { HorizontalStackCard(it) }
        registry.register("grid") { GridCard(it) }
        registry.registerCustomCards()
        // Custom cards (implemented separately):
        // registry.register("custom:bubble-card") { ... }
        // registry.register("custom:xiaomi-vacuum-map-card") { ... }
        // registry.register("custom:refreshable-picture-card") { ... }
        // registry.register("weather-forecast") { ... }
        // registry.register("history-graph") { ... }
    }

    fun registerRows(registry: RowRegistry) {
        registry.registerCoreRows()
        registry.registerCustomRows()
    }
}
