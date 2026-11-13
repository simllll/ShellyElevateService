package io.github.simllll.panelelevate.dashboard.browsermod

import android.os.Build
import android.util.Log
import io.github.simllll.panelelevate.dashboard.actions.ActionHandler
import io.github.simllll.panelelevate.dashboard.hass.ConnectionState
import io.github.simllll.panelelevate.dashboard.hass.HassClient
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Makes the native dashboard a browser_mod browser, so automations written for the web
 * dashboard keep working: it connects as [browserId] (by default the id the web dashboard used),
 * registers if needed, reports the `browser` sensors (path, visibility, size, user, …) and the
 * activity sensor, and runs the commands HA sends (popup, close_popup, notification, navigate,
 * refresh, more_info, sequence, javascript heartbeat) through [ActionHandler.browserModCommand].
 * `player-*` commands go to [player] (`media_player.<browser>`), whose state is reported back.
 *
 * All state lives on the main thread ([scope] must use the main dispatcher).
 */
class BrowserMod(
    private val hass: HassClient,
    private val actions: ActionHandler,
    private val scope: CoroutineScope,
    private val pathProvider: () -> String,
    private val player: BrowserModPlayer? = null,
) {
    /** Current browser id; null disables browser_mod. */
    var browserId: String? = null
        private set

    private var job: Job? = null
    private var ready = false
    private var user: JsonObject? = null
    private var visible = true
    private var width = 0
    private var height = 0
    private var lastSent: JsonObject? = null
    private var lastPlayerSent: JsonObject? = null
    private var activity = false
    private var activityReset: Job? = null

    /** Incremented on every connection loss; the subscription is re-sent on the next connection. */
    private var epoch = 0

    init {
        player?.onStateChanged = ::sendPlayer
        scope.launch {
            // A new connection re-sends the connect subscription; state must be reported again.
            hass.connectionState.collect {
                if (it !is ConnectionState.Connected) {
                    ready = false
                    lastSent = null
                    lastPlayerSent = null
                    epoch++
                }
            }
        }
    }

    /** Starts (or restarts with another id); null or blank stops it. */
    fun setBrowserId(id: String?) {
        val newId = id?.trim()?.takeIf { it.isNotEmpty() }
        if (newId == browserId && job != null) return
        browserId = newId
        job?.cancel()
        job = null
        ready = false
        lastSent = null
        if (newId != null) job = scope.launch { run(newId) }
    }

    private suspend fun run(id: String) {
        while (true) {
            var resubscribe = false
            try {
                hass.connectionState.first { it is ConnectionState.Connected }
                // HA attaches this connection to the browser device only if the browser is
                // registered when `connect` arrives, so a fresh registration needs a new connect.
                var first = true
                var registeredAtConnect = false
                var seenEpoch = epoch
                hass.subscribe(BrowserModProtocol.connect(id))
                    .takeWhile { event ->
                        if (seenEpoch != epoch) {
                            // HassClient re-subscribed after a reconnect: a new `connect`.
                            seenEpoch = epoch
                            first = true
                        }
                        when (val e = BrowserModProtocol.parseEvent(event)) {
                            is BrowserModProtocol.Event.Config -> {
                                val registered = e.isRegistered(id)
                                when {
                                    first && registered -> { registeredAtConnect = true; onReady() }
                                    !registered && (first || registeredAtConnect) -> {
                                        Log.i(TAG, "registering browser_mod browser \"$id\"")
                                        registeredAtConnect = false
                                        ready = false
                                        hass.send(BrowserModProtocol.register(id))
                                    }
                                    registered && !registeredAtConnect -> resubscribe = true
                                }
                                first = false
                            }
                            is BrowserModProtocol.Event.Command -> onCommand(e)
                            else -> Unit
                        }
                        !resubscribe
                    }
                    .collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "browser_mod/connect failed (is browser_mod installed?): ${e.message}")
            }
            ready = false
            lastSent = null
            if (!resubscribe) delay(RETRY_MS)
        }
    }

    private suspend fun onReady() {
        if (user == null) {
            user = try {
                hass.command(jsonOf("type" to "auth/current_user")).asObject()
            } catch (e: Exception) {
                Log.w(TAG, "auth/current_user failed: ${e.message}")
                null
            }
        }
        ready = true
        lastSent = null
        lastPlayerSent = null
        sendState()
        sendActivity()
        sendPlayer()
    }

    private fun onCommand(command: BrowserModProtocol.Event.Command) {
        Log.i(TAG, "command ${command.name}")
        val data = JsonObject(command.data - BrowserModProtocol.TARGET_KEYS)
        if (command.name.startsWith(PlayerCommand.PREFIX)) {
            val parsed = PlayerCommand.parse(command.name, data)
            if (parsed == null || player == null) Log.i(TAG, "player command ignored: ${command.name}")
            else try {
                player.handle(parsed)
            } catch (e: Exception) {
                Log.w(TAG, "browser_mod command ${command.name} failed", e)
            }
            return
        }
        try {
            actions.browserModCommand(command.name, data)
        } catch (e: Exception) {
            Log.w(TAG, "browser_mod command ${command.name} failed", e)
        }
    }

    /** Activity started/stopped (`document.visibilityState`). */
    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        sendState()
    }

    /** Window size in CSS px (`innerWidth`/`innerHeight`). */
    fun setSize(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        sendState()
    }

    /** The view changed (`path` sensor). */
    fun onPathChanged() = sendState()

    /** User interaction: `activity` turns on, and off after 15 s without interaction. */
    fun reportActivity() {
        if (!activity) {
            activity = true
            sendActivity()
        }
        activityReset?.cancel()
        activityReset = scope.launch {
            delay(ACTIVITY_COOLDOWN_MS)
            activity = false
            sendActivity()
        }
    }

    private fun sendActivity() {
        val id = browserId ?: return
        if (!ready) return
        hass.send(BrowserModProtocol.update(id, BrowserModProtocol.activity(activity)))
    }

    /** `media_player.<browser>` state (sent unchanged values are skipped). */
    private fun sendPlayer() {
        val id = browserId ?: return
        val p = player ?: return
        if (!ready) return
        val data = p.state.toUpdateData()
        if (data == lastPlayerSent) return
        lastPlayerSent = data
        hass.send(BrowserModProtocol.update(id, data))
    }

    private fun sendState() {
        val id = browserId ?: return
        if (!ready) return
        val data = BrowserModProtocol.BrowserState(
            path = pathProvider(),
            visible = visible,
            userAgent = USER_AGENT,
            width = width,
            height = height,
            user = user,
        ).toUpdateData()
        if (data == lastSent) return
        lastSent = data
        hass.send(BrowserModProtocol.update(id, data))
    }

    companion object {
        private const val TAG = "BrowserMod"
        private const val RETRY_MS = 60_000L
        private const val ACTIVITY_COOLDOWN_MS = 15_000L
        val USER_AGENT = "PanelElevate native dashboard (Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL})"
    }
}
