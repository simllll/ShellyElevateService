package com.stretter.shellyelevateservice.dashboard.hass

import android.util.Log
import com.stretter.shellyelevateservice.dashboard.json.HaJson
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asInt
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import com.stretter.shellyelevateservice.dashboard.format.isoUtc
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where to reach Home Assistant. [url] is the base URL, e.g. `http://192.168.1.20:8123`. */
data class HassConfig(val url: String, val token: String)

/**
 * Home Assistant WebSocket client.
 *
 * - Authenticates, reconnects with exponential backoff (1 s … 30 s) and re-sends every live
 *   subscription after a reconnect, so flows obtained from this client survive connection loss.
 * - Entity states: call [watchEntities] for the ids you show (ref-counted, closed when no longer
 *   shown) and observe [entity]. In Compose use `CardContext.entity()`, which does both.
 * - All protocol state lives on one single-threaded dispatcher; the public API is thread-safe.
 */
class HassClient(
    private val scope: CoroutineScope,
    val http: OkHttpClient = defaultHttpClient(),
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val proto = Dispatchers.Default.limitedParallelism(1)

    val store = EntityStore()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.NotConfigured)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _haVersion = MutableStateFlow<String?>(null)
    /** Core version reported by `auth_ok`. */
    val haVersion: StateFlow<String?> = _haVersion.asStateFlow()

    @Volatile var config: HassConfig? = null
        private set

    // ---- protocol state, only touched on [proto] ----
    private var socket: WebSocket? = null
    private var generation = 0
    private var authenticated = false
    private var nextId = 1
    private var attempt = 0
    private var reconnectJob: Job? = null
    private val pending = HashMap<Int, CompletableDeferred<JsonElement>>()
    private val subscriptions = LinkedHashSet<ActiveSubscription>()
    private val subscriptionsById = HashMap<Int, ActiveSubscription>()
    private val planner = EntitySubscriptionPlanner()
    private val entitySubs = HashMap<Int, ActiveSubscription>()
    private var nextEntitySubKey = 1
    private var reconcileJob: Job? = null
    private var reconcileDueAt = Long.MAX_VALUE

    private val templateCache = ConcurrentHashMap<String, SharedFlow<TemplateResult>>()

    private class ActiveSubscription(
        val message: JsonObject,
        val onEvent: (JsonElement) -> Unit,
        val onError: (Throwable) -> Unit,
    ) {
        var id: Int? = null
    }

    // ------------------------------------------------------------------ connection

    /** Sets (or clears) the connection config and reconnects. */
    fun configure(newConfig: HassConfig?) {
        scope.launch(proto) {
            if (newConfig == config && socket != null) return@launch
            config = newConfig
            store.clear()
            disconnect("reconfigured")
            if (newConfig == null) {
                _connectionState.value = ConnectionState.NotConfigured
            } else {
                attempt = 0
                connect()
            }
        }
    }

    /** Skips the backoff wait and reconnects now if the connection is down (e.g. on resume). */
    fun reconnectNow() {
        scope.launch(proto) {
            if (config == null || _connectionState.value !is ConnectionState.Disconnected) return@launch
            attempt = 0
            disconnect("manual reconnect")
            connect()
        }
    }

    private fun connect() {
        val cfg = config ?: return
        reconnectJob?.cancel()
        val wsUrl = websocketUrl(cfg.url)
        if (wsUrl == null) {
            _connectionState.value = ConnectionState.AuthFailed("Invalid URL: ${cfg.url}")
            return
        }
        val gen = ++generation
        authenticated = false
        _connectionState.value = ConnectionState.Connecting
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), Listener(gen))
    }

    private fun disconnect(reason: String) {
        reconnectJob?.cancel()
        generation++
        socket?.close(1000, reason)
        socket = null
        onSocketGone(IOException(reason))
    }

    private fun onSocketGone(error: Throwable) {
        authenticated = false
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        subscriptionsById.clear()
        subscriptions.forEach { it.id = null }
    }

    private fun scheduleReconnect(reason: String) {
        if (config == null) return
        val delayMs = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        attempt++
        _connectionState.value = ConnectionState.Disconnected(reason, delayMs)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(proto) {
            delay(delayMs)
            connect()
        }
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch(proto) { if (gen == generation) onText(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            gone("closed ($code${if (reason.isNotEmpty()) " $reason" else ""})")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            gone(t.message ?: t.javaClass.simpleName)

        private fun gone(reason: String) {
            scope.launch(proto) {
                if (gen != generation) return@launch
                Log.i(TAG, "connection lost: $reason")
                socket = null
                onSocketGone(IOException(reason))
                if (_connectionState.value !is ConnectionState.AuthFailed) scheduleReconnect(reason)
            }
        }
    }

    private fun onText(ws: WebSocket, text: String) {
        val parsed = try {
            HaJson.parseToJsonElement(text)
        } catch (e: Exception) {
            Log.w(TAG, "unparsable message", e)
            return
        }
        // With coalesce_messages HA may batch several messages into one array.
        when (parsed) {
            is JsonArray -> parsed.forEach { it.asObject()?.let { msg -> onMessage(ws, msg) } }
            is JsonObject -> onMessage(ws, parsed)
            else -> Unit
        }
    }

    private fun onMessage(ws: WebSocket, msg: JsonObject) {
        when (msg["type"].asString()) {
            "auth_required" -> ws.send(jsonOf("type" to "auth", "access_token" to config?.token).toString())
            "auth_ok" -> {
                authenticated = true
                attempt = 0
                _haVersion.value = msg["ha_version"].asString()
                _connectionState.value = ConnectionState.Connected
                nextId = 1
                sendRaw(jsonOf("type" to "supported_features", "features" to mapOf("coalesce_messages" to 1)))
                subscriptions.forEach { sendSubscribe(it) }
            }
            "auth_invalid" -> {
                _connectionState.value = ConnectionState.AuthFailed(msg["message"].asString() ?: "Invalid access token")
                generation++
                ws.close(1000, "auth invalid")
                socket = null
                onSocketGone(IOException("auth invalid"))
            }
            "result" -> {
                val id = msg["id"].asInt() ?: return
                val success = msg["success"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
                pending.remove(id)?.let { deferred ->
                    if (success) deferred.complete(msg["result"] ?: JsonNull)
                    else deferred.completeExceptionally(msg.toHassException())
                    return
                }
                if (!success) {
                    subscriptionsById.remove(id)?.let { sub ->
                        subscriptions.remove(sub)
                        sub.onError(msg.toHassException())
                    }
                }
            }
            "event" -> {
                val id = msg["id"].asInt() ?: return
                val event = msg["event"] ?: return
                subscriptionsById[id]?.onEvent?.invoke(event)
            }
        }
    }

    private fun JsonObject.toHassException(): HassException {
        val error = this["error"].asObject()
        return HassException(error?.get("code").asString() ?: "unknown_error", error?.get("message").asString() ?: "")
    }

    /** Sends a message with a fresh id; returns the id or null when not connected. */
    private fun sendRaw(message: JsonObject): Int? {
        val ws = socket ?: return null
        if (!authenticated) return null
        val id = nextId++
        ws.send(JsonObject(message + ("id" to JsonPrimitive(id))).toString())
        return id
    }

    private fun sendSubscribe(sub: ActiveSubscription) {
        val id = sendRaw(sub.message) ?: return
        sub.id = id
        subscriptionsById[id] = sub
    }

    // ------------------------------------------------------------------ generic API

    /**
     * Sends a command and returns its `result`. Waits up to [timeoutMs] for a connection.
     * @throws HassException when HA answers with `success: false`.
     */
    suspend fun command(message: JsonObject, timeoutMs: Long = 30_000): JsonElement = withTimeout(timeoutMs) {
        connectionState.first { it is ConnectionState.Connected }
        val deferred = CompletableDeferred<JsonElement>()
        withContext(proto) {
            val id = sendRaw(message) ?: throw IOException("not connected")
            pending[id] = deferred
        }
        deferred.await()
    }

    /**
     * Sends a message without waiting for a result, for commands HA never answers (e.g.
     * `browser_mod/update`). Dropped silently when not connected.
     */
    fun send(message: JsonObject) {
        scope.launch(proto) { sendRaw(message) }
    }

    /**
     * A subscription (`subscribe_events`, `render_template`, `subscribe_trigger`, …) as a cold flow of
     * its `event` payloads. Survives reconnects; collecting starts it, cancelling sends
     * `unsubscribe_events`. Completes with [HassException] if HA rejects the subscription.
     */
    fun subscribe(message: JsonObject): Flow<JsonElement> = callbackFlow {
        val sub = ActiveSubscription(message, { trySend(it) }, { close(it) })
        withContext(proto) {
            subscriptions += sub
            if (authenticated) sendSubscribe(sub)
        }
        awaitClose {
            scope.launch(proto) {
                subscriptions.remove(sub)
                sub.id?.let { id ->
                    subscriptionsById.remove(id)
                    sendRaw(jsonOf("type" to "unsubscribe_events", "subscription" to id))
                }
            }
        }
    }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED)

    // ------------------------------------------------------------------ entities

    /** Live state of an entity. Only updated while some [watchEntities] handle covers the id. */
    fun entity(entityId: String): StateFlow<EntityState?> = store.flow(entityId)

    /**
     * Declares that [entityIds] are on screen. HA only sends states for watched entities
     * (`subscribe_entities` with an `entity_ids` filter). Close the handle when done.
     */
    fun watchEntities(entityIds: Collection<String>): Closeable {
        val ids = entityIds.filter { it.contains('.') }.toList()
        if (ids.isEmpty()) return Closeable { }
        scope.launch(proto) {
            planner.acquire(ids)
            scheduleReconcile(ACQUIRE_DELAY_MS)
        }
        return OnceCloseable {
            scope.launch(proto) {
                planner.release(ids)
                scheduleReconcile(RELEASE_DELAY_MS)
            }
        }
    }

    /** Watches every entity (unfiltered subscription). Only for cards like auto-entities. */
    fun watchAllEntities(): Closeable {
        scope.launch(proto) {
            planner.acquireAll()
            scheduleReconcile(ACQUIRE_DELAY_MS)
        }
        return OnceCloseable {
            scope.launch(proto) {
                planner.releaseAll()
                scheduleReconcile(RELEASE_DELAY_MS)
            }
        }
    }

    /** All known states, re-emitted after every update. Use with [watchAllEntities]. */
    val allStates: Flow<Map<String, EntityState>> get() = store.all

    private fun scheduleReconcile(delayMs: Long) {
        val due = System.currentTimeMillis() + delayMs
        if (reconcileJob?.isActive == true && reconcileDueAt <= due) return
        reconcileJob?.cancel()
        reconcileDueAt = due
        reconcileJob = scope.launch(proto) {
            delay(delayMs)
            reconcileDueAt = Long.MAX_VALUE
            reconcileEntitySubscriptions()
        }
    }

    private fun reconcileEntitySubscriptions() {
        val current = entitySubs.map { (key, sub) ->
            EntitySubscriptionPlanner.Sub(key, sub.message["entity_ids"].asArray()?.mapNotNull { it.asString() }?.toSet())
        }
        for (step in planner.plan(current)) when (step) {
            is EntitySubscriptionPlanner.Step.Subscribe -> {
                val message = if (step.ids == null) jsonOf("type" to "subscribe_entities")
                else jsonOf("type" to "subscribe_entities", "entity_ids" to step.ids.sorted())
                val sub = ActiveSubscription(message, { ev -> ev.asObject()?.let(store::applyEvent) }, { e ->
                    Log.w(TAG, "subscribe_entities failed", e)
                })
                entitySubs[nextEntitySubKey++] = sub
                subscriptions += sub
                if (authenticated) sendSubscribe(sub)
            }
            is EntitySubscriptionPlanner.Step.Unsubscribe -> {
                val sub = entitySubs.remove(step.key) ?: continue
                subscriptions.remove(sub)
                sub.id?.let { id ->
                    subscriptionsById.remove(id)
                    sendRaw(jsonOf("type" to "unsubscribe_events", "subscription" to id))
                }
            }
        }
    }

    // ------------------------------------------------------------------ services & templates

    /**
     * Calls a service (`call_service`). [data] is the service data, [target] the optional target
     * (`entity_id`, `device_id`, `area_id`). Returns the service response when [returnResponse] is set.
     */
    suspend fun callService(
        domain: String,
        service: String,
        data: JsonObject? = null,
        target: JsonObject? = null,
        returnResponse: Boolean = false,
    ): JsonElement? {
        val result = command(
            jsonOf(
                "type" to "call_service",
                "domain" to domain,
                "service" to service,
                "service_data" to data,
                "target" to target,
                "return_response" to if (returnResponse) true else null,
            )
        )
        return result.asObject()?.get("response")
    }

    /**
     * Renders a template and re-renders whenever its inputs change (`render_template` subscription).
     * Identical template/variables share one subscription; the last result is replayed instantly
     * to new collectors (e.g. when a popup is reopened).
     */
    fun renderTemplate(template: String, variables: JsonObject? = null, strict: Boolean = false): Flow<TemplateResult> {
        val key = "$strict|$template|$variables"
        return templateCache.getOrPut(key) {
            val message = jsonOf(
                "type" to "render_template",
                "template" to template,
                "variables" to variables,
                "strict" to if (strict) true else null,
                "report_errors" to true,
            )
            subscribe(message)
                .mapNotNull { ev ->
                    val obj = ev.asObject() ?: return@mapNotNull null
                    when {
                        obj.containsKey("result") -> TemplateResult.Value(obj["result"]!!)
                        obj.containsKey("error") -> TemplateResult.Error(obj["error"].asString() ?: "", obj["level"].asString() ?: "ERROR")
                        else -> null
                    }
                }
                .catch { e -> emit(TemplateResult.Error(e.message ?: e.toString(), "ERROR")) }
                .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000), replay = 1)
        }
    }

    /** Events of one type (`subscribe_events`), as the event objects (`event_type`, `data`, …). */
    fun subscribeEvents(eventType: String): Flow<JsonObject> =
        subscribe(jsonOf("type" to "subscribe_events", "event_type" to eventType)).mapNotNull { it.asObject() }

    // ------------------------------------------------------------------ lovelace

    /** Dashboard config (`lovelace/config`). [urlPath] null = default dashboard. */
    suspend fun fetchLovelaceConfig(urlPath: String?, force: Boolean = false): JsonObject =
        command(jsonOf("type" to "lovelace/config", "url_path" to urlPath, "force" to force)).asObject()
            ?: throw HassException("invalid_config", "lovelace/config returned no object")

    /** Emits whenever the dashboard [urlPath] was saved (`lovelace_updated`). */
    fun lovelaceUpdates(urlPath: String?): Flow<Unit> = subscribeEvents("lovelace_updated")
        .filter { it["data"].asObject()?.get("url_path").asString() == urlPath }
        .map { }

    /** Core config (`get_config`): language, units, time zone, … */
    suspend fun getConfig(): JsonObject = command(jsonOf("type" to "get_config")).asObject() ?: JsonObject(emptyMap())

    /** Backend translations (`frontend/get_translations`), flat `component.x.y` keys. */
    suspend fun getTranslations(language: String, category: String, integrations: List<String>? = null): Map<String, String> {
        val result = command(
            jsonOf("type" to "frontend/get_translations", "language" to language, "category" to category, "integration" to integrations)
        )
        return result.asObject()?.get("resources").asObject()?.mapValues { it.value.asString() ?: "" } ?: emptyMap()
    }

    // ------------------------------------------------------------------ history & forecast

    /**
     * State history (`history/history_during_period`), oldest first per entity.
     * [startMs]/[endMs] are epoch millis; [endMs] null = now.
     */
    suspend fun history(
        entityIds: List<String>,
        startMs: Long,
        endMs: Long? = null,
        minimalResponse: Boolean = true,
        noAttributes: Boolean = true,
        significantChangesOnly: Boolean = true,
    ): Map<String, List<HistoryPoint>> {
        val result = command(
            jsonOf(
                "type" to "history/history_during_period",
                "start_time" to isoUtc(startMs),
                "end_time" to endMs?.let { isoUtc(it) },
                "entity_ids" to entityIds,
                "minimal_response" to minimalResponse,
                "no_attributes" to noAttributes,
                "significant_changes_only" to significantChangesOnly,
            ),
            timeoutMs = 60_000,
        )
        return parseHistory(result)
    }

    /**
     * Weather forecast (`weather/subscribe_forecast`); emits the forecast list on every update.
     * [type] is `daily`, `hourly` or `twice_daily`.
     */
    fun forecast(entityId: String, type: String): Flow<JsonArray> =
        subscribe(jsonOf("type" to "weather/subscribe_forecast", "entity_id" to entityId, "forecast_type" to type))
            .mapNotNull { it.asObject()?.get("forecast").asArray() }

    // ------------------------------------------------------------------ HTTP helpers

    /** Resolves a path like `/local/x.png` or `/api/camera_proxy/...` against the HA base URL. */
    fun absoluteUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        val base = config?.url?.trimEnd('/') ?: return pathOrUrl
        return base + (if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl")
    }

    /** GET request for [url]; adds the bearer token only for requests to the HA host itself. */
    fun request(url: String): Request {
        val absolute = absoluteUrl(url)
        val builder = Request.Builder().url(absolute)
        val cfg = config
        if (cfg != null) {
            val target = absolute.toHttpUrlOrNull()
            val base = cfg.url.toHttpUrlOrNull()
            if (target != null && base != null && target.host == base.host && target.port == base.port) {
                builder.header("Authorization", "Bearer ${cfg.token}")
            }
        }
        return builder.build()
    }

    private class OnceCloseable(private val action: () -> Unit) : Closeable {
        @Volatile private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            action()
        }
    }

    companion object {
        private const val TAG = "HassClient"
        private const val ACQUIRE_DELAY_MS = 16L
        private const val RELEASE_DELAY_MS = 3_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS) // detects dead Wi-Fi links
            .build()

        /** `http(s)://host:port[/…]` → `ws(s)://host:port/api/websocket`. */
        fun websocketUrl(baseUrl: String): String? {
            val url = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            val scheme = if (url.isHttps) "wss" else "ws"
            val port = if (url.port == (if (url.isHttps) 443 else 80)) "" else ":${url.port}"
            return "$scheme://${url.host}$port/api/websocket"
        }

        /** Parses the compressed history result `{entity_id: [{s, a?, lc?, lu}]}`. */
        fun parseHistory(result: JsonElement): Map<String, List<HistoryPoint>> {
            val obj = result.asObject() ?: return emptyMap()
            return obj.mapValues { (_, points) ->
                var lastChanged = 0L
                points.asArray()?.mapNotNull { p ->
                    val o = p.asObject() ?: return@mapNotNull null
                    val lc = (o["lc"] ?: o["lu"]).asDouble()?.let { (it * 1000).toLong() } ?: lastChanged
                    lastChanged = lc
                    HistoryPoint(o["s"].asString() ?: EntityState.UNKNOWN, lc, o["a"].asObject())
                } ?: emptyList()
            }
        }
    }
}
