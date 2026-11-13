package io.github.simllll.panelelevate.dashboard.cards

import androidx.compose.runtime.Composable
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.hass.TemplateResult
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import java.util.concurrent.ConcurrentHashMap

/** The naming parts of an entity registry entry that `formatEntityName` needs. */
private data class RegistryName(val entityName: String?, val deviceId: String?, val nextNamePart: String?)

private val registryNames = ConcurrentHashMap<String, RegistryName>()
private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

/**
 * Default entity name like the frontend's `formatEntityName(stateObj)` (`computeEntityNameDisplay`):
 * device name and/or the entity's own name from the registry, so a weather entity named after its
 * device shows "HomeAssistant Wien" instead of the friendly name "Forecast HomeAssistant Wien".
 * Falls back to the friendly name until the registry entry is loaded, or for entities without one.
 * Parent devices are not resolved.
 */
@Composable
fun CardContext.entityDisplayName(entity: EntityState?, entityId: String?): String {
    val fallback = entity?.friendlyName ?: entityId.orEmpty()
    if (entityId == null || !ENTITY_ID.matches(entityId)) return fallback
    val reg = registryNames[entityId] ?: rememberLoaded(entityId) {
        runCatching {
            val e = hass.command(jsonOf("type" to "config/entity_registry/get", "entity_id" to entityId)).asObject()
                ?: return@runCatching null
            RegistryName(e.string("name") ?: e.string("original_name"), e.string("device_id"), e.string("next_name_part"))
                .also { registryNames[entityId] = it }
        }.getOrNull()
    } ?: return fallback
    // An owner only adds its name while next_name_part links to it; nameless entities use the device.
    val needsDevice = reg.deviceId != null && (reg.nextNamePart == "device" || reg.entityName == null)
    val device = if (needsDevice) {
        val id = reg.deviceId
        (template("{{ device_attr('$id', 'name_by_user') or device_attr('$id', 'name') or '' }}") as? TemplateResult.Value)
            ?.text?.takeIf { it.isNotBlank() && it != "None" }
    } else null
    return listOfNotNull(device, reg.entityName).joinToString(" ").ifEmpty { fallback }
}
