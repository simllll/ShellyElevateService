package com.stretter.shellyelevateservice.dashboard.actions

import android.util.Log
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.hass.toggleService
import com.stretter.shellyelevateservice.dashboard.json.EmptyJsonObject
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import com.stretter.shellyelevateservice.dashboard.popup.PopupHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The three gestures of the frontend's action handler and their config keys. */
enum class ActionTrigger(val key: String) {
    TAP("tap_action"),
    HOLD("hold_action"),
    DOUBLE_TAP("double_tap_action"),
}

/**
 * Port of `handleAction` (panels/lovelace/common/handle-action.ts) plus browser_mod emulation.
 *
 * Supported actions: `more-info`, `toggle`, `perform-action` / `call-service`, `navigate`
 * (`#hash` opens bubble-card pop-ups via [PopupHost]), `url` (ignored on the kiosk), `none`,
 * `fire-dom-event` with a `browser_mod` payload (`browser_mod.popup`, `close_popup`, `more_info`,
 * `navigate`, `sequence`, `delay`; any other service is called on HA). `confirmation` is honoured.
 */
class ActionHandler(
    private val hass: HassClient,
    private val popups: PopupHost,
    private val navigator: Navigator,
    private val scope: CoroutineScope,
) {
    /**
     * Handles [trigger] on a card/row [config] (the object holding `tap_action` etc. and `entity`).
     * [defaultAction] replaces the frontend default (`more-info`) when the trigger is not configured.
     * [entityIdSubstitution]: replace the literal `"entity"` in `data.entity_id` / `target.entity_id`
     * with this id (button-card semantics; core cards pass null).
     */
    fun handle(
        config: JsonObject,
        trigger: ActionTrigger,
        defaultAction: JsonObject? = null,
        entityIdSubstitution: String? = null,
    ) {
        val action = config.obj(trigger.key) ?: defaultAction ?: MORE_INFO
        perform(action, config, entityIdSubstitution)
    }

    /** Runs one action object; [context] supplies `entity` fallbacks. */
    fun perform(action: JsonObject, context: JsonObject = EmptyJsonObject, entityIdSubstitution: String? = null) {
        val confirmation = action["confirmation"]
        if (confirmation != null && confirmation.asString() != "false") {
            val text = confirmation.asObject()?.string("text") ?: "Möchtest du die Aktion „${actionLabel(action)}“ ausführen?"
            popups.confirm(text, confirmation.asObject()?.string("title")) { execute(action, context, entityIdSubstitution) }
            return
        }
        execute(action, context, entityIdSubstitution)
    }

    private fun execute(action: JsonObject, context: JsonObject, substitution: String?) {
        when (action.string("action")) {
            null, "none" -> Unit
            "more-info" -> {
                val entityId = action.string("entity") ?: context.string("entity")
                    ?: context.string("camera_image") ?: context.string("image_entity")
                if (entityId != null) popups.showMoreInfo(entityId)
            }
            "toggle" -> {
                val entityId = context.string("entity") ?: return
                val state = hass.store[entityId]
                val (domain, service) = if (state != null) toggleService(state) else ("homeassistant" to "toggle")
                callService(domain, service, jsonOf("entity_id" to entityId), null)
            }
            "perform-action", "call-service" -> {
                val name = action.string("perform_action") ?: action.string("service") ?: return
                val domain = name.substringBefore('.', "")
                val service = name.substringAfter('.', "")
                if (domain.isEmpty() || service.isEmpty()) return
                val data = (action.obj("data") ?: action.obj("service_data"))?.substituteEntity(substitution)
                val target = action.obj("target")?.substituteEntity(substitution)
                callService(domain, service, data, target)
            }
            "navigate" -> action.string("navigation_path")?.let { navigator.navigate(it, action.bool("navigation_replace") == true) }
            "url" -> Log.i(TAG, "url action ignored on the kiosk: ${action.string("url_path")}")
            "fire-dom-event" -> action.obj("browser_mod")?.let { browserMod(it, context) }
            "assist" -> Log.i(TAG, "assist action not supported")
            else -> Log.w(TAG, "unknown action ${action.string("action")}")
        }
    }

    /** Runs a browser_mod call `{service, data}` as the browser would (popups are emulated natively). */
    fun browserMod(payload: JsonObject, context: JsonObject = EmptyJsonObject) {
        val service = payload.string("service")
        val data = payload.obj("data") ?: EmptyJsonObject
        when (service) {
            "browser_mod.popup" -> popups.showBrowserModPopup(data)
            "browser_mod.close_popup" -> popups.dismissTop()
            "browser_mod.more_info" -> data.string("entity")?.let { popups.showMoreInfo(it) }
            "browser_mod.navigate" -> data.string("path")?.let { navigator.navigate(it, false) }
            "browser_mod.sequence" -> scope.launch {
                (data["sequence"] as? JsonArray)?.forEach { step ->
                    step.asObject()?.let { s ->
                        if (s.string("service") == "browser_mod.delay") {
                            kotlinx.coroutines.delay(s.obj("data")?.get("time").asString()?.toLongOrNull() ?: 0L)
                        } else {
                            browserMod(s, context)
                        }
                    }
                }
            }
            "browser_mod.refresh" -> navigator.reload()
            null -> Unit
            else -> {
                // Services of other integrations are called on HA directly.
                val domain = service.substringBefore('.')
                val name = service.substringAfter('.')
                if (domain != "browser_mod") callService(domain, name, data, null)
                else Log.i(TAG, "browser_mod service not emulated: $service")
            }
        }
    }

    fun callService(domain: String, service: String, data: JsonObject?, target: JsonObject?) {
        scope.launch {
            try {
                hass.callService(domain, service, data, target)
            } catch (e: Exception) {
                Log.w(TAG, "$domain.$service failed: ${e.message}")
                popups.toast("$domain.$service: ${e.message ?: "Fehler"}")
            }
        }
    }

    private fun actionLabel(action: JsonObject): String =
        action.string("perform_action") ?: action.string("service") ?: action.string("action") ?: ""

    private fun JsonObject.substituteEntity(entityId: String?): JsonObject {
        if (entityId == null) return this
        val value = this["entity_id"]
        val replaced: JsonElement = when {
            value is JsonPrimitive && value.asString() == "entity" -> JsonPrimitive(entityId)
            value is JsonArray -> JsonArray(value.map { if (it.asString() == "entity") JsonPrimitive(entityId) else it })
            else -> return this
        }
        return JsonObject(this + ("entity_id" to replaced))
    }

    companion object {
        private const val TAG = "ActionHandler"
        private val MORE_INFO = jsonOf("action" to "more-info")

        /** True if [trigger] is configured with an action other than `none`. */
        fun hasAction(config: JsonObject, trigger: ActionTrigger): Boolean {
            val action = config.obj(trigger.key) ?: return false
            return action.string("action").let { it != null && it != "none" }
        }

        /**
         * `hasAnyAction`: whether tapping does anything (the frontend assumes `more-info` when
         * nothing is configured, so only an explicit `none` disables tap).
         */
        fun isInteractive(config: JsonObject, defaultTap: Boolean = true): Boolean {
            val tap = config.obj(ActionTrigger.TAP.key)
            val tapActive = if (tap == null) defaultTap else tap.string("action") != "none"
            return tapActive || hasAction(config, ActionTrigger.HOLD) || hasAction(config, ActionTrigger.DOUBLE_TAP)
        }
    }
}
