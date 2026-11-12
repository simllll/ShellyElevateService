package com.stretter.shellyelevateservice.dashboard.icons

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import kotlinx.serialization.json.JsonObject

/**
 * Default entity icons, like `<ha-state-icon>`: `icon` attribute, then the backend icon
 * translations (`frontend/get_icons`, category `entity_component`: per domain and device class,
 * with per-state variants), then a small built-in fallback.
 *
 * Not covered yet: integration-specific icons that depend on the entity registry translation key.
 */
@Stable
class IconResolver {
    /** `domain -> (device_class or "_") -> {default, state: {..}}`. Compose state: icons update once loaded. */
    private var iconTranslations: JsonObject by mutableStateOf(JsonObject(emptyMap()))

    /** Feeds the `resources` object of `frontend/get_icons` (category `entity_component`). */
    fun setComponentIcons(resources: JsonObject) {
        iconTranslations = resources
    }

    /** Icon for [entity] in [state]; [override] (card config `icon`) wins. */
    fun icon(entity: EntityState?, override: String? = null, state: String? = entity?.state): String {
        if (!override.isNullOrBlank()) return override
        if (entity == null) return DEFAULT_ICON
        entity.icon?.takeIf { it.isNotBlank() }?.let { return it }
        val s = state ?: entity.state
        if (entity.domain == "sensor" && entity.deviceClass == "battery") batteryIcon(s)?.let { return it }
        componentIcon(entity.domain, entity.deviceClass, s)?.let { return it }
        return FALLBACK[entity.domain] ?: DEFAULT_ICON
    }

    /** Icon of a domain without an entity (e.g. for a placeholder). */
    fun domainIcon(domain: String, deviceClass: String? = null, state: String? = null): String =
        componentIcon(domain, deviceClass, state) ?: FALLBACK[domain] ?: DEFAULT_ICON

    private fun componentIcon(domain: String, deviceClass: String?, state: String?): String? {
        val domainIcons = iconTranslations[domain].asObject() ?: return null
        val candidates = listOfNotNull(deviceClass?.let { domainIcons[it].asObject() }, domainIcons["_"].asObject())
        for (entry in candidates) {
            state?.let { entry["state"].asObject()?.get(it).asString() }?.let { return it }
            entry["default"].asString()?.let { return it }
        }
        return null
    }

    private fun batteryIcon(state: String): String? {
        val level = state.toDoubleOrNull() ?: return null
        val rounded = (Math.round(level / 10) * 10).toInt().coerceIn(0, 100)
        return when {
            rounded >= 100 -> "mdi:battery"
            rounded <= 5 -> "mdi:battery-outline"
            else -> "mdi:battery-$rounded"
        }
    }

    companion object {
        const val DEFAULT_ICON = "mdi:bookmark"

        /** Used until the icon translations are loaded. */
        private val FALLBACK = mapOf(
            "air_quality" to "mdi:air-filter",
            "alarm_control_panel" to "mdi:shield",
            "automation" to "mdi:robot",
            "binary_sensor" to "mdi:radiobox-blank",
            "button" to "mdi:button-pointer",
            "calendar" to "mdi:calendar",
            "camera" to "mdi:video",
            "climate" to "mdi:thermostat",
            "cover" to "mdi:window-open",
            "device_tracker" to "mdi:account",
            "fan" to "mdi:fan",
            "group" to "mdi:google-circles-communities",
            "humidifier" to "mdi:air-humidifier",
            "image" to "mdi:image",
            "input_boolean" to "mdi:toggle-switch",
            "input_button" to "mdi:button-pointer",
            "input_datetime" to "mdi:calendar-clock",
            "input_number" to "mdi:ray-vertex",
            "input_select" to "mdi:format-list-bulleted",
            "input_text" to "mdi:form-textbox",
            "light" to "mdi:lightbulb",
            "lock" to "mdi:lock",
            "media_player" to "mdi:cast",
            "number" to "mdi:ray-vertex",
            "person" to "mdi:account",
            "remote" to "mdi:remote",
            "scene" to "mdi:palette",
            "script" to "mdi:script-text",
            "select" to "mdi:format-list-bulleted",
            "sensor" to "mdi:eye",
            "siren" to "mdi:bullhorn",
            "sun" to "mdi:white-balance-sunny",
            "switch" to "mdi:toggle-switch-variant",
            "timer" to "mdi:timer-outline",
            "update" to "mdi:package-up",
            "vacuum" to "mdi:robot-vacuum",
            "valve" to "mdi:valve",
            "water_heater" to "mdi:thermometer",
            "weather" to "mdi:weather-partly-cloudy",
            "zone" to "mdi:map-marker-radius",
        )
    }
}
