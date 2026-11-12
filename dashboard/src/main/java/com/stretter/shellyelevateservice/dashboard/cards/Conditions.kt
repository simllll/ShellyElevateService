package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.runtime.Composable
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.json.array
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asStringList
import com.stretter.shellyelevateservice.dashboard.json.double
import com.stretter.shellyelevateservice.dashboard.json.objects
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonObject

/**
 * Evaluates the card's `visibility` conditions (also usable for the `conditional` card).
 * Supported: `state` (`state` / `state_not`, lists allowed), `numeric_state` (`above` / `below`),
 * `and`, `or`, `not`. `screen` and `user` are treated as met (single kiosk screen and user).
 * Legacy conditions without `condition` but with `entity` are treated as `state`.
 */
@Composable
fun CardContext.conditionsMet(config: JsonObject): Boolean {
    val conditions = config.array("visibility") ?: return true
    return conditions.all { c -> c.asObject()?.let { conditionMet(it) } ?: true }
}

@Composable
fun CardContext.conditionMet(condition: JsonObject): Boolean {
    val type = condition.string("condition") ?: if (condition.containsKey("entity")) "state" else return true
    return when (type) {
        "state" -> {
            val entity = entity(condition.string("entity"))
            stateConditionMet(entity, condition)
        }
        "numeric_state" -> {
            val value = entity(condition.string("entity"))?.state?.toDoubleOrNull() ?: return false
            val above = condition.double("above")
            val below = condition.double("below")
            (above == null || value > above) && (below == null || value < below)
        }
        // Evaluate every child so the composition structure does not depend on earlier results.
        "and" -> condition.objects("conditions").map { conditionMet(it) }.all { it }
        "or" -> condition.objects("conditions").map { conditionMet(it) }.any { it }
        "not" -> !condition.objects("conditions").map { conditionMet(it) }.all { it }
        else -> true
    }
}

/** `state` condition on an already resolved entity (null entity → only `state_not` can match). */
fun stateConditionMet(entity: EntityState?, condition: JsonObject): Boolean {
    val state = entity?.state ?: EntityState.UNAVAILABLE
    val expected = condition["state"].asStringList()
    val notExpected = condition["state_not"].asStringList()
    if (expected.isNotEmpty() && state !in expected) return false
    if (notExpected.isNotEmpty() && state in notExpected) return false
    return true
}
