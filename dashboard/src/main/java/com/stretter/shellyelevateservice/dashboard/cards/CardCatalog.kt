package com.stretter.shellyelevateservice.dashboard.cards

import com.stretter.shellyelevateservice.dashboard.custom.registerCustomCards
import com.stretter.shellyelevateservice.dashboard.custom.registerCustomRows
import com.stretter.shellyelevateservice.dashboard.cards.bubble.BubbleCard
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
        registry.register("entity") { EntityCard(it) }
        registry.register("energy-date-selection") { EnergyDateSelectionCard(it) }
        registry.register("energy-devices-detail-graph") { EnergyDevicesDetailGraphCard(it) }
        registry.register("button") { ButtonCard(it) }
        registry.register("markdown") { MarkdownCard(it) }
        registry.register("picture") { PictureCard(it) }
        registry.register("vertical-stack") { VerticalStackCard(it) }
        registry.register("horizontal-stack") { HorizontalStackCard(it) }
        registry.register("grid") { GridCard(it) }
        registry.register("weather-forecast") { WeatherForecastCard(it) }
        registry.register("history-graph") { HistoryGraphCard(it) }
        registry.register("iframe") { IframeCard(it) }
        registry.register("custom:refreshable-picture-card") { RefreshablePictureCard(it) }
        registry.registerCustomCards()
        registry.register("alarm-panel") { AlarmPanelCard(it) }
        registry.register("custom:bubble-card") { BubbleCard.create(it) }
        registry.register("custom:xiaomi-vacuum-map-card") { com.stretter.shellyelevateservice.dashboard.cards.vacuum.VacuumMapCard(it) }
    }

    fun registerRows(registry: RowRegistry) {
        registry.registerCoreRows()
        registry.registerCustomRows()
    }
}
