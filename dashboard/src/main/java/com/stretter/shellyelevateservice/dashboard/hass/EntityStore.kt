package com.stretter.shellyelevateservice.dashboard.hass

import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.asStringList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Entity states, fed by the compressed `subscribe_entities` events:
 *
 * ```
 * {"a": {id: {"s", "a", "c", "lc", "lu"}},            // added / full state
 *  "c": {id: {"+": {"s", "a", "c", "lc", "lu"},       // changed: values to set
 *             "-": {"a": [attribute names]}}},        // changed: attributes to drop
 *  "r": [id, ...]}                                    // removed
 * ```
 *
 * Every entity has its own [StateFlow] so a state change only recomposes the cards showing that
 * entity. Applying the same event twice is harmless (overlapping subscriptions rely on this).
 */
class EntityStore {
    private val states = ConcurrentHashMap<String, EntityState>()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<EntityState?>>()
    private val revision = MutableStateFlow(0L)

    /** Live state of one entity; null while unknown (not subscribed yet, or removed). */
    fun flow(entityId: String): StateFlow<EntityState?> =
        flows.getOrPut(entityId) { MutableStateFlow(states[entityId]) }.asStateFlow()

    operator fun get(entityId: String): EntityState? = states[entityId]

    /** Snapshot of all known states. */
    fun snapshot(): Map<String, EntityState> = HashMap(states)

    /** Emits a snapshot after every applied event. Only for cards that really need all states. */
    val all: Flow<Map<String, EntityState>> get() = revision.map { snapshot() }

    /** Applies one compressed `subscribe_entities` event. */
    fun applyEvent(event: JsonObject) {
        event["a"].asObject()?.forEach { (entityId, compressed) ->
            compressed.asObject()?.let { put(decodeFull(entityId, it)) }
        }
        event["r"].asStringList().forEach { remove(it) }
        event["c"].asObject()?.forEach { (entityId, diff) ->
            val current = states[entityId] ?: return@forEach // HA never sends a diff before the full state
            diff.asObject()?.let { put(applyDiff(current, it)) }
        }
        revision.value++
    }

    /** Sets a state directly (from `get_states` or tests). */
    fun put(state: EntityState) {
        states[state.entityId] = state
        flows[state.entityId]?.value = state
    }

    fun remove(entityId: String) {
        states.remove(entityId)
        flows[entityId]?.value = null
    }

    fun clear() {
        states.clear()
        flows.values.forEach { it.value = null }
        revision.value++
    }

    companion object {
        fun decodeFull(entityId: String, c: JsonObject): EntityState {
            val lastChanged = c["lc"].toMillis() ?: 0L
            return EntityState(
                entityId = entityId,
                state = c["s"].asString() ?: EntityState.UNKNOWN,
                attributes = c["a"].asObject() ?: JsonObject(emptyMap()),
                lastChanged = lastChanged,
                lastUpdated = c["lu"].toMillis() ?: lastChanged,
                contextId = contextId(c["c"]),
            )
        }

        fun applyDiff(current: EntityState, diff: JsonObject): EntityState {
            var next = current
            diff["+"].asObject()?.let { add ->
                add["s"].asString()?.let { next = next.copy(state = it) }
                contextId(add["c"])?.let { next = next.copy(contextId = it) }
                val lc = add["lc"].toMillis()
                val lu = add["lu"].toMillis()
                if (lc != null) next = next.copy(lastChanged = lc, lastUpdated = lc)
                else if (lu != null) next = next.copy(lastUpdated = lu)
                add["a"].asObject()?.let { next = next.copy(attributes = JsonObject(next.attributes + it)) }
            }
            diff["-"].asObject()?.get("a").asArray()?.let { removed ->
                val names = removed.mapNotNull { it.asString() }.toSet()
                next = next.copy(attributes = JsonObject(next.attributes.filterKeys { it !in names }))
            }
            return next
        }

        /** Context is either the context id (string) or a full context object. */
        private fun contextId(c: JsonElement?): String? = c.asString() ?: c.asObject()?.get("id").asString()

        /** Compressed timestamps are float seconds since the epoch. */
        private fun JsonElement?.toMillis(): Long? = asDouble()?.let { (it * 1000).toLong() }
    }
}
