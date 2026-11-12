package com.stretter.shellyelevateservice.dashboard.custom

import com.stretter.shellyelevateservice.dashboard.custom.autoentities.AutoEntitiesSpec
import com.stretter.shellyelevateservice.dashboard.custom.autoentities.FilterEnv
import com.stretter.shellyelevateservice.dashboard.hass.ConnectionState
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.hass.HassConfig
import com.stretter.shellyelevateservice.dashboard.hass.TemplateResult
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, read-only: resolves every `custom:auto-entities` card of the dashboard against a real
 * instance (template rendering, entity registry `get_entries`, exclude rules). Never calls services.
 *
 * ./gradlew :dashboard:testDebugUnitTest --tests '*LiveAutoEntitiesTest*' -Dha.live=true -Dha.url=… -Dha.token=…
 */
class LiveAutoEntitiesTest {
    private fun collect(e: JsonElement?, out: MutableList<JsonObject>) {
        when (e) {
            is JsonObject -> {
                if (e.string("type") == "custom:auto-entities") out += e
                e.values.forEach { collect(it, out) }
            }
            is JsonArray -> e.forEach { collect(it, out) }
            else -> Unit
        }
    }

    @Test
    fun `resolves the dashboard's auto-entities cards`() {
        assumeTrue(System.getProperty("ha.live") == "true")
        val url = System.getProperty("ha.url").orEmpty()
        val token = System.getProperty("ha.token").orEmpty()
        assumeTrue(url.isNotEmpty() && token.isNotEmpty())
        val dashboard = System.getProperty("ha.dashboard")?.takeIf { it.isNotEmpty() } ?: "wohnbereich-walldisplay"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hass = HassClient(scope)
            hass.configure(HassConfig(url, token))
            runBlocking {
                withTimeout(60_000) {
                    hass.connectionState.first { it is ConnectionState.Connected }
                    val cards = ArrayList<JsonObject>()
                    collect(hass.fetchLovelaceConfig(dashboard), cards)
                    assertTrue("dashboard has auto-entities cards", cards.isNotEmpty())
                    for (card in cards) {
                        val spec = AutoEntitiesSpec(card)
                        val result = spec.template?.let { hass.renderTemplate(it).first() }
                        val entries = (result as? TemplateResult.Value)?.let { AutoEntitiesSpec.parseTemplateResult(it.result) } ?: emptyList()
                        if (result is TemplateResult.Error) println("template error: ${result.message}")
                        val ids = entries.mapNotNull { it.string("entity") }.distinct()
                        hass.watchEntities(ids)
                        val registry = hass.entityRegistry
                        registry.request(ids)
                        withTimeout(10_000) {
                            while (ids.any { it !in hass.store.snapshot() || it !in registry.entries.value }) delay(50)
                        }
                        val env = FilterEnv(hass.store.snapshot(), registry.entries.value)
                        val resolved = spec.resolve(entries, env)
                        assertTrue("registry complete", env.missingRegistry.isEmpty())
                        val title = (card["card"] as? JsonObject)?.string("title")
                        println("$title: template ${entries.size} → ${resolved.size}: ${resolved.map { it.string("entity") + (it.string("type")?.let { t -> " ($t)" } ?: "") }}")
                        println("  shows: ${spec.displayedCard(resolved)?.string("type")}")
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
