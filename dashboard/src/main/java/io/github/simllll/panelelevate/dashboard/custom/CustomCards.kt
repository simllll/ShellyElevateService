package io.github.simllll.panelelevate.dashboard.custom

import io.github.simllll.panelelevate.dashboard.cards.CardRegistry
import io.github.simllll.panelelevate.dashboard.custom.autoentities.AutoEntitiesCard
import io.github.simllll.panelelevate.dashboard.custom.buttoncard.CustomButtonCard
import io.github.simllll.panelelevate.dashboard.custom.timerbar.TimerBarCard
import io.github.simllll.panelelevate.dashboard.custom.timerbar.TimerBarRow
import io.github.simllll.panelelevate.dashboard.rows.RowRegistry
import io.github.simllll.panelelevate.dashboard.rows.StateRow

/** Custom (HACS) cards: button-card, auto-entities, mushroom-template-card, timer-bar-card, fab-card, mushroom-climate-card. */
fun CardRegistry.registerCustomCards() {
    register("custom:button-card") { CustomButtonCard(it) }
    register("custom:auto-entities") { AutoEntitiesCard(it) }
    register("custom:mushroom-template-card") { MushroomTemplateCard(it) }
    register("custom:timer-bar-card") { TimerBarCard(it) }
    register("custom:fab-card") { FabCard(it) }
    register("custom:mushroom-climate-card") { MushroomClimateCard(it) }
}

/** Custom rows used inside entities cards (also as auto-entities results). */
fun RowRegistry.registerCustomRows() {
    register("custom:template-entity-row") { TemplateEntityRow(it) }
    register("custom:timer-bar-card") { TimerBarRow(it) }
    // Core `simple-entity` row (auto-entities templates produce it): state text like `simple`.
    register("simple-entity") { StateRow(it) }
}
