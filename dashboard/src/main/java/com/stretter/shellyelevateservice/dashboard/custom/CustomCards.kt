package com.stretter.shellyelevateservice.dashboard.custom

import com.stretter.shellyelevateservice.dashboard.cards.CardRegistry
import com.stretter.shellyelevateservice.dashboard.custom.autoentities.AutoEntitiesCard
import com.stretter.shellyelevateservice.dashboard.custom.buttoncard.CustomButtonCard
import com.stretter.shellyelevateservice.dashboard.custom.timerbar.TimerBarCard
import com.stretter.shellyelevateservice.dashboard.custom.timerbar.TimerBarRow
import com.stretter.shellyelevateservice.dashboard.rows.RowRegistry
import com.stretter.shellyelevateservice.dashboard.rows.StateRow

/** Custom (HACS) cards: button-card, auto-entities, mushroom-template-card, timer-bar-card. */
fun CardRegistry.registerCustomCards() {
    register("custom:button-card") { CustomButtonCard(it) }
    register("custom:auto-entities") { AutoEntitiesCard(it) }
    register("custom:mushroom-template-card") { MushroomTemplateCard(it) }
    register("custom:timer-bar-card") { TimerBarCard(it) }
}

/** Custom rows used inside entities cards (also as auto-entities results). */
fun RowRegistry.registerCustomRows() {
    register("custom:template-entity-row") { TemplateEntityRow(it) }
    register("custom:timer-bar-card") { TimerBarRow(it) }
    // Core `simple-entity` row (auto-entities templates produce it): state text like `simple`.
    register("simple-entity") { StateRow(it) }
}
