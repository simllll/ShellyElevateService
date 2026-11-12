package com.stretter.shellyelevateservice.dashboard.hass

import com.stretter.shellyelevateservice.dashboard.json.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, read-only test against a real Home Assistant (never calls services):
 *
 * ./gradlew :dashboard:testDebugUnitTest --tests '*LiveHassTest*' \
 *     -Dha.live=true -Dha.url=https://ha.example -Dha.token=… [-Dha.dashboard=url_path]
 */
class LiveHassTest {
    @Test
    fun `connects, loads the dashboard, subscribes and renders a template`() {
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
                withTimeout(30_000) {
                    hass.connectionState.first { it is ConnectionState.Connected }
                    val config = hass.fetchLovelaceConfig(dashboard)
                    val views = config.objects("views")
                    assertTrue("dashboard has views", views.isNotEmpty())
                    println("views: ${views.size}, first view type: ${views.first()["type"]}")

                    hass.watchEntities(listOf("sun.sun"))
                    val sun = hass.entity("sun.sun").first { it != null }
                    assertNotNull(sun)
                    println("sun.sun = ${sun!!.state}")

                    val rendered = hass.renderTemplate("{{ 20 + 22 }}").first()
                    assertEquals("42", (rendered as TemplateResult.Value).text)

                    val translations = hass.getTranslations("de", "entity_component")
                    println("translations: ${translations.size}, cover open = ${translations["component.cover.entity_component._.state.open"]}")
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
