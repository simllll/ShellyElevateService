package io.github.simllll.panelelevate.dashboard.hass

import androidx.compose.runtime.Immutable
import io.github.simllll.panelelevate.dashboard.json.asDouble
import io.github.simllll.panelelevate.dashboard.json.asString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One entity state, equivalent to `HassEntity` in home-assistant-js-websocket.
 * Timestamps are epoch milliseconds.
 */
@Immutable
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: Long,
    val lastUpdated: Long,
    val contextId: String? = null,
) {
    val domain: String get() = entityId.substringBefore('.')
    val objectId: String get() = entityId.substringAfter('.')

    fun attr(name: String): JsonElement? = attributes[name]
    fun attrString(name: String): String? = attributes[name].asString()
    fun attrDouble(name: String): Double? = attributes[name].asDouble()

    val friendlyName: String get() = attrString("friendly_name") ?: objectId.replace('_', ' ')
    val deviceClass: String? get() = attrString("device_class")
    val unit: String? get() = attrString("unit_of_measurement")
    val icon: String? get() = attrString("icon")
    val entityPicture: String? get() = attrString("entity_picture")
    val supportedFeatures: Int get() = attrDouble("supported_features")?.toInt() ?: 0

    val isUnavailable: Boolean get() = state == UNAVAILABLE
    val isUnknown: Boolean get() = state == UNKNOWN

    companion object {
        const val UNAVAILABLE = "unavailable"
        const val UNKNOWN = "unknown"
        const val ON = "on"
        const val OFF = "off"
    }
}

/** Connection lifecycle of [HassClient]. */
sealed interface ConnectionState {
    /** No URL/token configured. */
    data object NotConfigured : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    /** Lost or failed; a reconnect is scheduled after [retryInMs]. */
    data class Disconnected(val reason: String, val retryInMs: Long) : ConnectionState
    /** The token was rejected. No automatic retry until the config changes. */
    data class AuthFailed(val message: String) : ConnectionState
}

/** Result of a `render_template` subscription. */
@Immutable
sealed interface TemplateResult {
    /** Rendered result. HA returns strings for text templates, but may return numbers/lists/objects. */
    data class Value(val result: JsonElement) : TemplateResult {
        /** The result as text, like the frontend shows it. */
        val text: String get() = result.asString() ?: result.toString()
    }
    data class Error(val message: String, val level: String) : TemplateResult
}

/** One point of `history/history_during_period`. */
@Immutable
data class HistoryPoint(
    val state: String,
    val lastChanged: Long,
    val attributes: JsonObject? = null,
)

/** Thrown for a failed HA command (`success: false`). */
class HassException(val code: String, message: String) : Exception("$code: $message")
