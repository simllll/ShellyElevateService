package io.github.simllll.panelelevate.dashboard.actions

import android.util.Log
import io.github.simllll.panelelevate.dashboard.browsermod.BrowserModProtocol
import io.github.simllll.panelelevate.dashboard.browsermod.JsServiceCall
import io.github.simllll.panelelevate.dashboard.hass.HassClient
import io.github.simllll.panelelevate.dashboard.hass.toggleService
import io.github.simllll.panelelevate.dashboard.json.EmptyJsonObject
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.bool
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.popup.PopupHost
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
 * `fire-dom-event` with a `browser_mod` payload (see [browserModCommand]; any other service is
 * called on HA). `confirmation` is honoured.
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
            "fire-dom-event" -> action.obj("browser_mod")?.let { browserMod(it) }
            "assist" -> Log.i(TAG, "assist action not supported")
            else -> Log.w(TAG, "unknown action ${action.string("action")}")
        }
    }

    /**
     * Runs a browser_mod call `{service, data, target}` as the browser would (`_service_action`):
     * `browser_mod.*` services run locally (popups are emulated natively) unless they target
     * browsers explicitly (`browser_id`/`user_id`), everything else is called on HA.
     */
    fun browserMod(payload: JsonObject) {
        val service = payload.string("service") ?: return
        var data = payload.obj("data") ?: EmptyJsonObject
        val local = service.startsWith("browser_mod.") || !service.contains('.')
        // `browser_id: THIS` targets the browser itself.
        if (data.string("browser_id") == "THIS") data = JsonObject(data - "browser_id")
        if (local && data["browser_id"] == null && data["user_id"] == null) {
            browserModCommand(service.removePrefix("browser_mod."), data)
            return
        }
        val domain = service.substringBefore('.')
        val name = service.substringAfter('.')
        callService(domain, name, data, payload.obj("target"))
    }

    /**
     * A browser_mod command addressed to this browser, from a dashboard action or from HA
     * (`browser_mod/connect` events). [name] has no `browser_mod.` prefix.
     */
    fun browserModCommand(name: String, data: JsonObject) {
        when (name) {
            "popup" -> popups.showBrowserModPopup(data)
            "close_popup" -> popups.closeBrowserModPopups(data.string("tag"))
            "more_info" -> data.string("entity")?.let { popups.showMoreInfo(it) }
            "navigate" -> data.string("path")?.let { navigator.navigate(it, false) }
            "refresh" -> navigator.reload()
            "notification" -> data.string("message")?.let { message ->
                val action = data["action"]
                popups.notify(
                    message = message,
                    durationMs = data["duration"].asString()?.toDoubleOrNull()?.toLong(),
                    actionText = data.string("action_text")?.takeIf { it.isNotBlank() && action != null },
                    onAction = { runBrowserModActions(action) },
                    dismissable = data.bool("dismissable") ?: true,
                )
            }
            "sequence" -> scope.launch {
                (data["sequence"] as? JsonArray)?.forEach { step ->
                    val s = step.asObject() ?: return@forEach
                    if (s.string("service")?.removePrefix("browser_mod.") == "delay") {
                        kotlinx.coroutines.delay(s.obj("data")?.get("time").asString()?.toDoubleOrNull()?.toLong() ?: 0L)
                    } else {
                        browserMod(s)
                    }
                }
            }
            "delay" -> Unit // only meaningful inside a sequence
            "javascript" -> {
                val code = data.string("code").orEmpty()
                val call = JsServiceCall.parse(code)
                if (call != null) callService(call.domain, call.service, call.data, call.target)
                else Log.i(TAG, "browser_mod.javascript ignored (not a hass.callService call): ${code.take(120)}")
            }
            else -> Log.i(TAG, "browser_mod command not emulated: $name")
        }
    }

    /**
     * Runs a popup/notification action like browser_mod: one object or a list of
     * `{action|service, data, target}` (an `action` other than `call-service` names the service).
     */
    fun runBrowserModActions(actions: JsonElement?) {
        val list = when (actions) {
            is JsonArray -> actions.mapNotNull { it.asObject() }
            is JsonObject -> listOf(actions)
            else -> return
        }
        for (a in list) {
            val service = BrowserModProtocol.serviceOf(a) ?: continue
            browserMod(jsonOf("service" to service, "data" to a.obj("data"), "target" to a.obj("target")))
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
