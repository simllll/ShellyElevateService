package com.stretter.shellyelevateservice.dashboard.hass

import android.util.Log
import androidx.compose.runtime.Immutable
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.asStringList
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The entity registry fields filter rules need (`EntityRegistryEntry`, abridged). */
@Immutable
data class RegistryEntry(
    val entityId: String,
    val hiddenBy: String? = null,
    val labels: List<String> = emptyList(),
    val platform: String? = null,
    val configEntryId: String? = null,
    val entityCategory: String? = null,
    val deviceId: String? = null,
    val areaId: String? = null,
) {
    companion object {
        fun fromJson(entityId: String, o: JsonObject) = RegistryEntry(
            entityId = entityId,
            hiddenBy = o.string("hidden_by"),
            labels = o["labels"].asStringList(),
            platform = o.string("platform"),
            configEntryId = o.string("config_entry_id"),
            entityCategory = o.string("entity_category"),
            deviceId = o.string("device_id"),
            areaId = o.string("area_id"),
        )
    }
}

/**
 * On-demand entity registry, fetched per entity with `config/entity_registry/get_entries`.
 *
 * The full `config/entity_registry/list` is several MB on a large instance and
 * `list_for_display` drops `hidden_by` (only a boolean), so cards request exactly the entities
 * they filter. Entries are cached for the process and refreshed on `entity_registry_updated`.
 */
class EntityRegistryCache(private val hass: HassClient, private val scope: CoroutineScope) {
    private val _entries = MutableStateFlow<Map<String, RegistryEntry?>>(emptyMap())

    /** Fetched entries; an absent key = not fetched yet, a null value = not in the registry. */
    val entries: StateFlow<Map<String, RegistryEntry?>> = _entries.asStateFlow()

    private val _labels = MutableStateFlow<Map<String, String>?>(null)

    /** Label id → name (`config/label_registry/list`); null until [requestLabels] finished. */
    val labels: StateFlow<Map<String, String>?> = _labels.asStateFlow()

    private val inFlight = HashSet<String>()
    private var listening = false
    private var labelsRequested = false

    /** Fetches the entries of [entityIds] that are not cached yet. */
    fun request(entityIds: Collection<String>) {
        val missing: List<String>
        synchronized(inFlight) {
            val known = _entries.value
            missing = entityIds.filter { it !in known && it !in inFlight }.distinct()
            if (missing.isEmpty()) return
            inFlight += missing
            if (!listening) {
                listening = true
                listenForUpdates()
            }
        }
        scope.launch {
            val fetched = try {
                val result = hass.command(jsonOf("type" to "config/entity_registry/get_entries", "entity_ids" to missing)).asObject()
                missing.associateWith { id -> result?.get(id).asObject()?.let { RegistryEntry.fromJson(id, it) } }
            } catch (e: Exception) {
                Log.w(TAG, "get_entries failed: ${e.message}")
                missing.associateWith { null }
            }
            synchronized(inFlight) { inFlight -= missing.toSet() }
            _entries.update { it + fetched }
        }
    }

    /** Loads the label registry once (for `label` rules matching label names). */
    fun requestLabels() {
        synchronized(inFlight) {
            if (labelsRequested) return
            labelsRequested = true
        }
        scope.launch {
            _labels.value = try {
                hass.command(jsonOf("type" to "config/label_registry/list")).asArray()
                    ?.mapNotNull { it.asObject() }
                    ?.mapNotNull { o -> o.string("label_id")?.let { id -> id to (o.string("name") ?: id) } }
                    ?.toMap() ?: emptyMap()
            } catch (e: Exception) {
                Log.w(TAG, "label registry failed: ${e.message}")
                emptyMap()
            }
        }
    }

    private fun listenForUpdates() {
        scope.launch {
            try {
                hass.subscribeEvents("entity_registry_updated").collect { event ->
                    val data = event["data"].asObject() ?: return@collect
                    val id = data.string("entity_id") ?: return@collect
                    val oldId = data.string("old_entity_id")
                    if (id !in _entries.value && oldId !in _entries.value) return@collect
                    _entries.update { it - id - (oldId ?: "") }
                    if (data["action"].asString() != "remove") request(listOf(id))
                }
            } catch (e: Exception) {
                Log.w(TAG, "entity_registry_updated subscription ended: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "EntityRegistry"
    }
}
