package com.stretter.shellyelevateservice.dashboard.browsermod

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsServiceCallTest {
    private val now = 1_762_960_000_123L

    @Test
    fun `heartbeat of the walldisplay watchdog`() {
        val code = "document.querySelector('home-assistant').hass.callService('input_datetime','set_datetime'," +
            "{entity_id:'input_datetime.walldisplay_wohnbereich_heartbeat',timestamp:Math.floor(Date.now()/1000)})"
        val call = JsServiceCall.parse(code, now)!!
        assertEquals("input_datetime", call.domain)
        assertEquals("set_datetime", call.service)
        assertEquals(
            HaJson.parseToJsonElement("""{"entity_id":"input_datetime.walldisplay_wohnbereich_heartbeat","timestamp":1762960000}"""),
            call.data,
        )
        assertNull(call.target)
    }

    @Test
    fun `bare hass, double quotes, target and trailing semicolon`() {
        val call = JsServiceCall.parse(
            """ hass.callService("light", "turn_on", { "brightness_pct": 50, transition: 1.5, flash: false }, {entity_id: ["light.a", "light.b"]}); """,
            now,
        )!!
        assertEquals("light", call.domain)
        assertEquals("""{"brightness_pct":50,"transition":1.5,"flash":false}""", call.data.toString())
        assertEquals("""{"entity_id":["light.a","light.b"]}""", call.target.toString())
    }

    @Test
    fun `arithmetic and string concatenation`() {
        val call = JsServiceCall.parse(
            "hass.callService('input_number','set_value',{entity_id:'input_number.' + 'x', value: Math.round((Date.now() - 123) / 1000) % 60 * 2})",
            now,
        )!!
        assertEquals("input_number.x", call.data!!["entity_id"].toString().trim('"'))
        assertEquals((((now - 123) / 1000.0).let { Math.round(it) } % 60 * 2).toString(), call.data!!["value"].toString())
    }

    @Test
    fun `anything else is ignored`() {
        assertNull(JsServiceCall.parse("location.reload()", now))
        assertNull(JsServiceCall.parse("hass.callService('a','b',{x: window.innerWidth})", now))
        assertNull(JsServiceCall.parse("hass.callService('a','b'); alert(1)", now))
        assertNull(JsServiceCall.parse("hass.callService('a')", now))
        assertNull(JsServiceCall.parse("hass.callService('a','b',{x: 'unterminated})", now))
        assertNull(JsServiceCall.parse("hass.callService('a','b',{x: `${'$'}{1}`})", now))
    }

    @Test
    fun `service without data`() {
        val call = JsServiceCall.parse("document.querySelector(\"home-assistant\").hass.callService('homeassistant','update_entity')", now)!!
        assertEquals("homeassistant", call.domain)
        assertEquals("update_entity", call.service)
        assertNull(call.data)
    }
}
