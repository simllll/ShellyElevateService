package com.stretter.shellyelevateservice.dashboard.browsermod

import com.stretter.shellyelevateservice.dashboard.json.EmptyJsonObject
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Messages of the browser_mod 2.x/3.x WebSocket protocol (`custom_components/browser_mod/connection.py`
 * and `browser.py`), as seen by a browser.
 *
 * - `browser_mod/connect {browserID}` is a subscription. HA answers with events of three shapes:
 *   `{result: store}` (the integration's settings store, sent at once and on every change),
 *   `{command: "popup", …service data}` (a service call targeted at this browser) and
 *   `{browserEntities: {…}}` (entity ids of the browser's sensors).
 * - `browser_mod/register {browserID, data?}` creates the browser device (no result is sent).
 * - `browser_mod/update {browserID, data}` reports state: `{browser: {path, visibility, …}}`,
 *   `{activity, activityType}`, `{screen_on, screen_brightness}`, … (no result is sent).
 */
object BrowserModProtocol {
    const val CONNECT = "browser_mod/connect"
    const val REGISTER = "browser_mod/register"
    const val UPDATE = "browser_mod/update"

    sealed interface Event {
        /** Settings store: `browsers` (id → settings), `global_settings`, `user_settings`. */
        data class Config(val store: JsonObject) : Event {
            fun browser(browserId: String): JsonObject? = store.obj("browsers")?.obj(browserId)
            fun isRegistered(browserId: String): Boolean = browser(browserId) != null
            val autoRegister: Boolean get() = store.obj("global_settings")?.get("autoRegister").asString() == "true"
        }

        /** A command: [name] without the `browser_mod.` prefix, [data] the service data. */
        data class Command(val name: String, val data: JsonObject) : Event

        data class Entities(val entities: JsonObject) : Event
    }

    /** Parses one event payload of the `browser_mod/connect` subscription (null if unknown). */
    fun parseEvent(event: JsonElement): Event? {
        val obj = event.asObject() ?: return null
        val command = obj["command"].asString()
        if (command != null) return Event.Command(command, JsonObject(obj - "command"))
        obj.obj("browserEntities")?.let { return Event.Entities(it) }
        obj.obj("result")?.let { return Event.Config(it) }
        return null
    }

    fun connect(browserId: String): JsonObject = jsonOf("type" to CONNECT, "browserID" to browserId)

    fun register(browserId: String): JsonObject = jsonOf("type" to REGISTER, "browserID" to browserId, "data" to EmptyJsonObject)

    fun update(browserId: String, data: JsonObject): JsonObject = jsonOf("type" to UPDATE, "browserID" to browserId, "data" to data)

    /** The `browser` block the frontend sends (`_browser_state_update`); feeds the browser's sensors. */
    data class BrowserState(
        val path: String,
        val visible: Boolean,
        val userAgent: String,
        val width: Int,
        val height: Int,
        /** `auth/current_user` result: `id`, `name`, `is_owner`, `is_admin`, … */
        val user: JsonObject?,
        val darkMode: Boolean = true,
    ) {
        fun toUpdateData(): JsonObject = jsonOf(
            "browser" to jsonOf(
                "path" to path,
                "visibility" to if (visible) "visible" else "hidden",
                "userAgent" to userAgent,
                "currentUser" to user?.get("name").asString(),
                "fullyKiosk" to false,
                "width" to width,
                "height" to height,
                "darkMode" to darkMode,
                "userData" to user,
            )
        )
    }

    fun activity(active: Boolean): JsonObject =
        if (active) jsonOf("activity" to true, "activityType" to "userInteraction")
        else jsonOf("activity" to false, "activityType" to "none")

    /** Service data keys that target browsers; stripped before a command is handled locally. */
    val TARGET_KEYS = setOf("browser_id", "device_id", "area_id", "user_id")

    /**
     * Service of a popup button/timeout/dismiss action like browser_mod resolves it:
     * `action` unless it is missing or `call-service`, then `service`.
     */
    fun serviceOf(action: JsonObject): String? =
        action["action"].asString()?.takeIf { it != "call-service" && it != "perform-action" }
            ?: action["service"].asString() ?: action["perform_action"].asString()
}
