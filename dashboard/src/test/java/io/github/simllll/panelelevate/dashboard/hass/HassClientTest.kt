package io.github.simllll.panelelevate.dashboard.hass

import io.github.simllll.panelelevate.dashboard.json.HaJson
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.asString
import io.github.simllll.panelelevate.dashboard.json.asStringList
import io.github.simllll.panelelevate.dashboard.json.int
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ExperimentalOkHttpApi
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Protocol tests against a fake HA WebSocket server. */
@OptIn(ExperimentalOkHttpApi::class)
class HassClientTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fake = FakeHomeAssistant()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repeat(3) { server.enqueue(MockResponse.Builder().webSocketUpgrade(fake).build()) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        // Closing may report the socket the client already dropped (auth failure); irrelevant here.
        runCatching { server.close() }
    }

    private fun client(token: String = "good") = HassClient(scope).also {
        it.configure(HassConfig(server.url("/").toString().trimEnd('/'), token))
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `authenticates and connects`() {
        val hass = client()
        await { hass.connectionState.first { it is ConnectionState.Connected } }
        assertEquals("2026.11.0", hass.haVersion.value)
        assertEquals("good", fake.received.first { it.string("type") == "auth" }.string("access_token"))
    }

    @Test
    fun `invalid token fails without retry`() {
        val hass = client("bad")
        val state = await { hass.connectionState.filterIsInstance<ConnectionState.AuthFailed>().first() }
        assertTrue(state.message.isNotEmpty())
    }

    @Test
    fun `watched entities are subscribed with a filter and kept up to date`() {
        val hass = client()
        val handle = hass.watchEntities(listOf("light.kitchen", "sensor.temp"))
        val light = await { hass.entity("light.kitchen").first { it != null }!! }
        assertEquals("on", light.state)
        val subscribe = fake.received.first { it.string("type") == "subscribe_entities" }
        assertEquals(listOf("light.kitchen", "sensor.temp"), subscribe["entity_ids"].asStringList())

        fake.sendEvent(subscribe.int("id")!!, """{"c": {"light.kitchen": {"+": {"s": "off", "lc": 1700000100}}}}""")
        await { hass.entity("light.kitchen").first { it?.state == "off" } }
        handle.close()
    }

    @Test
    fun `call_service sends domain, service, data and target`() {
        val hass = client()
        await { hass.callService("light", "turn_on", jsonOf("brightness_pct" to 50), jsonOf("entity_id" to "light.kitchen")) }
        val call = fake.received.first { it.string("type") == "call_service" }
        assertEquals("light", call.string("domain"))
        assertEquals("turn_on", call.string("service"))
        assertEquals("50", call["service_data"].asObject()?.string("brightness_pct"))
        assertEquals("light.kitchen", call["target"].asObject()?.string("entity_id"))
    }

    @Test
    fun `render_template emits results`() {
        val hass = client()
        val result = await { hass.renderTemplate("{{ 1 + 1 }}").first() }
        assertEquals(TemplateResult.Value(kotlinx.serialization.json.JsonPrimitive("rendered: {{ 1 + 1 }}")), result)
    }

    @Test
    fun `subscriptions are restored after a reconnect`() {
        val hass = client()
        hass.watchEntities(listOf("light.kitchen"))
        await { hass.entity("light.kitchen").first { it != null } }
        fake.dropConnection()
        await { hass.connectionState.first { it is ConnectionState.Disconnected } }
        await { hass.connectionState.first { it is ConnectionState.Connected } }
        await {
            while (fake.received.count { it.string("type") == "subscribe_entities" } < 2) kotlinx.coroutines.delay(20)
        }
        assertEquals(2, fake.received.count { it.string("type") == "auth" })
    }

    /** Minimal HA WebSocket API: auth, subscribe_entities, call_service, render_template. */
    private class FakeHomeAssistant : WebSocketListener() {
        val received = CopyOnWriteArrayList<JsonObject>()
        @Volatile private var current: WebSocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            current = webSocket
            webSocket.send("""{"type": "auth_required", "ha_version": "2026.11.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = HaJson.parseToJsonElement(text).asObject() ?: return
            received += msg
            val id = msg.int("id")
            when (msg.string("type")) {
                "auth" -> webSocket.send(
                    if (msg.string("access_token") == "good") """{"type": "auth_ok", "ha_version": "2026.11.0"}"""
                    else """{"type": "auth_invalid", "message": "Invalid access token or password"}"""
                )
                "subscribe_entities" -> {
                    webSocket.send("""{"id": $id, "type": "result", "success": true, "result": null}""")
                    val states = msg["entity_ids"].asStringList().joinToString(",") { entityId ->
                        """"$entityId": {"s": "on", "a": {"friendly_name": "$entityId"}, "c": "c1", "lc": 1700000000}"""
                    }
                    webSocket.send("""{"id": $id, "type": "event", "event": {"a": {$states}}}""")
                }
                "render_template" -> {
                    webSocket.send("""{"id": $id, "type": "result", "success": true, "result": null}""")
                    val template = msg["template"].asString()
                    webSocket.send("""{"id": $id, "type": "event", "event": {"result": "rendered: $template", "listeners": {}}}""")
                }
                else -> webSocket.send("""{"id": $id, "type": "result", "success": true, "result": {"context": {"id": "x"}}}""")
            }
        }

        fun sendEvent(id: Int, event: String) {
            current!!.send("""[{"id": $id, "type": "event", "event": $event}]""")
        }

        fun dropConnection() {
            current!!.close(1001, "restart")
        }
    }
}
