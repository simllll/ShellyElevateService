package com.stretter.shellyelevateservice.dashboard.browsermod

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserModProtocolTest {
    private fun parse(json: String) = BrowserModProtocol.parseEvent(HaJson.parseToJsonElement(json))

    @Test
    fun `config event lists registered browsers`() {
        val e = parse(
            """{"result":{"browsers":{"walldisplay Wohnbereich":{"registered":true,"last_seen":"x"}},
               "global_settings":{"autoRegister":null},"user_settings":{}}}"""
        ) as BrowserModProtocol.Event.Config
        assertTrue(e.isRegistered("walldisplay Wohnbereich"))
        assertFalse(e.isRegistered("walldisplay Eingang"))
        assertFalse(e.autoRegister)
    }

    @Test
    fun `popup command keeps service data without the command key`() {
        // What `BrowserModBrowser.send("popup", **data)` emits for the "Gute Nacht" automation.
        val e = parse(
            """{"command":"popup","title":"Gute Nacht","timeout":4000,"dismissable":true,
               "content":"<div style=\"text-align:center\">Gute Nacht</div>"}"""
        ) as BrowserModProtocol.Event.Command
        assertEquals("popup", e.name)
        assertEquals("Gute Nacht", e.data.string("title"))
        assertFalse(e.data.containsKey("command"))
        assertEquals("4000", e.data["timeout"].toString())
    }

    @Test
    fun `card content stays an object`() {
        val e = parse("""{"command":"popup","title":"TV","content":{"type":"grid","columns":2,"cards":[]}}""")
            as BrowserModProtocol.Event.Command
        assertEquals("grid", e.data.obj("content")?.string("type"))
    }

    @Test
    fun `browser entities and unknown events`() {
        assertTrue(parse("""{"browserEntities":{"path":{"entity_id":"sensor.x","enabled":true}}}""") is BrowserModProtocol.Event.Entities)
        assertNull(parse("""{"something":1}"""))
        assertNull(parse("[1,2]"))
    }

    @Test
    fun `messages to HA`() {
        assertEquals(
            HaJson.parseToJsonElement("""{"type":"browser_mod/connect","browserID":"walldisplay Wohnbereich"}"""),
            BrowserModProtocol.connect("walldisplay Wohnbereich"),
        )
        assertEquals(
            HaJson.parseToJsonElement("""{"type":"browser_mod/register","browserID":"b","data":{}}"""),
            BrowserModProtocol.register("b"),
        )
        val update = BrowserModProtocol.update("b", BrowserModProtocol.activity(true))
        assertEquals("browser_mod/update", update.string("type"))
        assertEquals("""{"activity":true,"activityType":"userInteraction"}""", update["data"].toString())
    }

    @Test
    fun `browser state matches the frontend sensors`() {
        val user = jsonOf("id" to "c90b9e925a6d4eac944f40f442ca3b40", "name" to "Walldisplay")
        val data = BrowserModProtocol.BrowserState(
            path = "/wohnbereich-walldisplay/home",
            visible = false,
            userAgent = "UA",
            width = 1280,
            height = 800,
            user = user,
        ).toUpdateData()
        val browser = data["browser"]!!.jsonObject
        assertEquals("/wohnbereich-walldisplay/home", browser.string("path"))
        assertEquals("hidden", browser.string("visibility"))
        assertEquals("Walldisplay", browser.string("currentUser"))
        assertEquals("1280", browser["width"].toString())
        assertEquals("false", browser["fullyKiosk"].toString())
        assertEquals(user, browser["userData"] as JsonObject)
    }

    @Test
    fun `popup action service resolution`() {
        assertEquals("browser_mod.close_popup", BrowserModProtocol.serviceOf(jsonOf("action" to "browser_mod.close_popup")))
        assertEquals("light.toggle", BrowserModProtocol.serviceOf(jsonOf("action" to "call-service", "service" to "light.toggle")))
        assertEquals("script.x", BrowserModProtocol.serviceOf(jsonOf("service" to "script.x")))
        assertNull(BrowserModProtocol.serviceOf(jsonOf("data" to jsonOf())))
    }
}
