package io.github.simllll.panelelevate.dashboard.custom

import io.github.simllll.panelelevate.dashboard.custom.buttoncard.ButtonCardHtml
import io.github.simllll.panelelevate.dashboard.custom.buttoncard.ButtonCardJs
import io.github.simllll.panelelevate.dashboard.custom.buttoncard.HtmlPiece
import io.github.simllll.panelelevate.dashboard.custom.buttoncard.JsScope
import io.github.simllll.panelelevate.dashboard.custom.buttoncard.JsUnsupported
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ButtonCardTemplateTest {
    private val light = EntityState("light.kueche", "on", jsonOf("friendly_name" to "Küche", "brightness" to 128), 0, 0)
    private val temp = EntityState("sensor.temp", "21.5", jsonOf("unit_of_measurement" to "°C"), 0, 0)
    private val scope = JsScope(light, { id -> listOf(light, temp).firstOrNull { it.entityId == id } }, jsonOf("prefix" to "Hallo"))

    private fun eval(code: String) = ButtonCardJs.evaluateToString(code, scope)

    /** The label templates of the dashboard, verbatim. */
    private val dashboardTemplates = listOf(
        "mdi:moon-full" to "Licht aus",
        "mdi:light-flood-up" to "Wohnbereich Licht",
        "mdi:light-flood-up" to "Hell",
        "mdi:upload" to "Alle Rolladen öffnen",
        "mdi:download" to "Alle Rolladen schließen",
    ).map { (icon, text) ->
        Triple(icon, text, "[[[\n  return `<ha-icon\n  icon=\"$icon\"\n  style=\"width: 40px; height: 20px; vertical-align: 5%;\">\n  </ha-icon><span>$text</span>`\n]]]\n")
    }

    @Test
    fun `dashboard label templates evaluate to icon and text`() {
        for ((icon, text, template) in dashboardTemplates) {
            val code = ButtonCardJs.templateCode(template)!!
            val html = eval(code)
            assertTrue(html, html.startsWith("<ha-icon"))
            assertTrue(ButtonCardHtml.isHtml(html))
            val pieces = ButtonCardHtml.parse(html)
            assertEquals(listOf(HtmlPiece.Icon(icon, 40f, 20f, null), HtmlPiece.Text(text)), pieces)
        }
    }

    @Test
    fun `template detection`() {
        assertNull(ButtonCardJs.templateCode("Licht"))
        assertNull(ButtonCardJs.templateCode("[[ x ]]"))
        assertEquals(" return 1 ", ButtonCardJs.templateCode("  [[[ return 1 ]]]\n"))
    }

    @Test
    fun `expressions over entity, states and variables`() {
        assertEquals("on", eval("return entity.state"))
        assertEquals("Küche: an", eval("return entity.attributes.friendly_name + ': ' + (entity.state === 'on' ? 'an' : 'aus')"))
        assertEquals("21.5 °C", eval("return `\${states['sensor.temp'].state} \${states[\"sensor.temp\"].attributes.unit_of_measurement}`"))
        assertEquals("50", eval("return (entity.attributes.brightness / 255 * 100).toFixed(0);"))
        assertEquals("Hallo Welt", eval("return variables.prefix + ' Welt'"))
        assertEquals("HELL", eval("return entity.attributes.brightness > 100 ? 'hell'.toUpperCase() : 'dunkel'"))
        assertEquals("true", eval("return entity.state == 'on' && !(entity.state != 'on')"))
        assertEquals("x", eval("return states['sensor.missing'] ? states['sensor.missing'].state : 'x'"))
        assertEquals("fallback", eval("return entity.attributes.missing || 'fallback'"))
        assertEquals("43", eval("return parseFloat(states['sensor.temp'].state) * 2"))
        assertEquals("", eval("return null"))
    }

    @Test
    fun `untaken branches do not fail`() {
        val noEntity = JsScope(null, { null })
        assertEquals("-", ButtonCardJs.evaluateToString("return entity ? entity.state : '-'", noEntity))
        assertEquals("", ButtonCardJs.evaluateToString("return entity && entity.state", noEntity))
    }

    @Test
    fun `unsupported constructs throw`() {
        for (code in listOf("const x = 1; return x", "if (entity) return 1", "return window.foo", "return entity.state.split(',')")) {
            try {
                eval(code)
                fail("expected JsUnsupported for $code")
            } catch (_: JsUnsupported) {
            }
        }
    }

    @Test
    fun `referenced entities are found`() {
        assertEquals(
            setOf("sensor.temp", "light.x"),
            ButtonCardJs.referencedEntities("return states['sensor.temp'].state + states[\"light.x\"].state"),
        )
    }

    @Test
    fun `html subset`() {
        assertEquals(
            listOf(HtmlPiece.Text("Temp:", bold = true), HtmlPiece.Text(" 21 °C", color = "red")),
            ButtonCardHtml.parse("<b>Temp:</b><span style=\"color: red\"> 21&nbsp;°C</span>").map {
                if (it is HtmlPiece.Text) it.copy(text = it.text.replace(' ', ' ')) else it
            },
        )
        assertEquals(
            listOf(HtmlPiece.Text("a"), HtmlPiece.LineBreak, HtmlPiece.Text("b")),
            ButtonCardHtml.parse("  a <br> b  "),
        )
        assertEquals(
            listOf(HtmlPiece.Text("x"), HtmlPiece.LineBreak, HtmlPiece.Text("y")),
            ButtonCardHtml.parse("<div>x</div><div>y</div>"),
        )
        assertEquals(listOf(HtmlPiece.Icon("mdi:fire", null, null, "orange")), ButtonCardHtml.parse("<span style='color: orange'><ha-icon icon='mdi:fire'></ha-icon></span>"))
    }
}
