package io.github.simllll.panelelevate.dashboard.hass

/*
 * Ports of small frontend helpers (src/common/entity, src/data/entity).
 */

/** Domains whose state is a timestamp; active unless unavailable. */
private val TIMESTAMP_STATE_DOMAINS = setOf("button", "event", "input_button", "scene")

/** Port of `stateActive` (common/entity/state_active.ts). */
fun EntityState.isActive(compareState: String = state): Boolean {
    if (domain in TIMESTAMP_STATE_DOMAINS) return compareState != EntityState.UNAVAILABLE
    if (compareState == EntityState.UNAVAILABLE || compareState == EntityState.UNKNOWN) return false
    if (compareState == EntityState.OFF && domain != "alert") return false
    return when (domain) {
        "alarm_control_panel" -> compareState != "disarmed"
        "alert" -> compareState != "idle"
        "cover", "valve" -> compareState != "closed"
        "device_tracker", "person" -> compareState != "not_home"
        "lawn_mower" -> compareState !in setOf("docked", "paused", "idle")
        "lock" -> compareState != "locked"
        "media_player" -> compareState != "standby"
        "vacuum" -> compareState !in setOf("idle", "docked", "paused")
        "plant" -> compareState == "problem"
        "group" -> compareState in setOf("on", "home", "open", "locked", "problem")
        "timer" -> compareState == "active"
        "camera" -> compareState in setOf("streaming", "recording")
        else -> true
    }
}

/** Domains that can be toggled from a row/tile (`DOMAINS_TOGGLE` in the frontend). */
val TOGGLE_DOMAINS = setOf(
    "fan", "input_boolean", "light", "switch", "group", "automation", "humidifier", "valve",
)

/** States treated as "off" when toggling (`STATES_OFF`). */
private val STATES_OFF = setOf("closed", "locked", "off")

/** Service call for turning an entity on/off (`turnOnOffEntity` in data/entity.ts). */
fun turnOnOffService(entityId: String, turnOn: Boolean): Pair<String, String> {
    val domain = entityId.substringBefore('.')
    val serviceDomain = if (domain == "group") "homeassistant" else domain
    val service = when (domain) {
        "lock" -> if (turnOn) "unlock" else "lock"
        "cover" -> if (turnOn) "open_cover" else "close_cover"
        "valve" -> if (turnOn) "open_valve" else "close_valve"
        "button", "input_button" -> "press"
        "scene" -> "turn_on"
        else -> if (turnOn) "turn_on" else "turn_off"
    }
    return serviceDomain to service
}

/** Service call for toggling (`toggleEntity`): turn on when currently off/closed/locked. */
fun toggleService(state: EntityState): Pair<String, String> = turnOnOffService(state.entityId, state.state in STATES_OFF)

/** Default tap action of the button card (`getEntityDefaultButtonAction`). */
fun defaultButtonAction(entityId: String?): String {
    val domain = entityId?.substringBefore('.') ?: return "more-info"
    return if (domain in TOGGLE_DOMAINS) "toggle" else "more-info"
}
