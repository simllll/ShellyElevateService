package com.stretter.shellyelevateservice.dashboard.hass

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityStoreTest {
    private fun event(json: String) = HaJson.parseToJsonElement(json) as JsonObject

    private val add = event(
        """{"a": {
            "light.kitchen": {"s": "on", "a": {"brightness": 128, "friendly_name": "Küche"}, "c": "ctx1", "lc": 1700000000.5, "lu": 1700000001.25},
            "sensor.temp": {"s": "21.5", "a": {"unit_of_measurement": "°C"}, "c": {"id": "ctx2", "parent_id": null, "user_id": null}, "lc": 1700000000}
        }}"""
    )

    @Test
    fun `added states are decoded`() {
        val store = EntityStore()
        store.applyEvent(add)
        val light = store["light.kitchen"]!!
        assertEquals("on", light.state)
        assertEquals("Küche", light.friendlyName)
        assertEquals(1_700_000_000_500L, light.lastChanged)
        assertEquals(1_700_000_001_250L, light.lastUpdated)
        assertEquals("ctx1", light.contextId)
        val temp = store["sensor.temp"]!!
        // Without "lu", last_updated equals last_changed.
        assertEquals(temp.lastChanged, temp.lastUpdated)
        assertEquals("ctx2", temp.contextId)
        assertEquals("°C", temp.unit)
    }

    @Test
    fun `changes set state, attributes and timestamps`() {
        val store = EntityStore()
        store.applyEvent(add)
        store.applyEvent(event("""{"c": {"light.kitchen": {"+": {"s": "off", "a": {"brightness": null, "color_mode": "onoff"}, "lc": 1700000100, "c": "ctx3"}}}}"""))
        val light = store["light.kitchen"]!!
        assertEquals("off", light.state)
        assertEquals(1_700_000_100_000L, light.lastChanged)
        assertEquals(light.lastChanged, light.lastUpdated)
        assertEquals("onoff", light.attrString("color_mode"))
        assertEquals("Küche", light.friendlyName)
        assertEquals("ctx3", light.contextId)
    }

    @Test
    fun `lu without lc only moves last_updated`() {
        val store = EntityStore()
        store.applyEvent(add)
        store.applyEvent(event("""{"c": {"sensor.temp": {"+": {"a": {"battery": 80}, "lu": 1700000200}}}}"""))
        val temp = store["sensor.temp"]!!
        assertEquals(1_700_000_000_000L, temp.lastChanged)
        assertEquals(1_700_000_200_000L, temp.lastUpdated)
        assertEquals("21.5", temp.state)
        assertEquals(80.0, temp.attrDouble("battery")!!, 0.0)
    }

    @Test
    fun `removed attributes are dropped`() {
        val store = EntityStore()
        store.applyEvent(add)
        store.applyEvent(event("""{"c": {"light.kitchen": {"-": {"a": ["brightness"]}}}}"""))
        val light = store["light.kitchen"]!!
        assertFalse(light.attributes.containsKey("brightness"))
        assertEquals("on", light.state)
    }

    @Test
    fun `removed entities emit null`() {
        val store = EntityStore()
        val flow = store.flow("sensor.temp")
        store.applyEvent(add)
        assertEquals("21.5", flow.value?.state)
        store.applyEvent(event("""{"r": ["sensor.temp"]}"""))
        assertNull(flow.value)
        assertNull(store["sensor.temp"])
    }

    @Test
    fun `diffs for unknown entities are ignored`() {
        val store = EntityStore()
        store.applyEvent(event("""{"c": {"switch.x": {"+": {"s": "on"}}}}"""))
        assertNull(store["switch.x"])
    }

    @Test
    fun `applying an event twice is harmless`() {
        val store = EntityStore()
        store.applyEvent(add)
        val change = event("""{"c": {"light.kitchen": {"+": {"s": "off", "lc": 1700000100}, "-": {"a": ["brightness"]}}}}""")
        store.applyEvent(change)
        val once = store["light.kitchen"]
        store.applyEvent(change)
        assertEquals(once, store["light.kitchen"])
    }

    @Test
    fun `per-entity flows only change for their entity`() {
        val store = EntityStore()
        store.applyEvent(add)
        val temp = store.flow("sensor.temp")
        val before = temp.value
        store.applyEvent(event("""{"c": {"light.kitchen": {"+": {"s": "off"}}}}"""))
        assertTrue(before === temp.value)
        assertEquals("off", store["light.kitchen"]!!.state)
        assertEquals("Küche", store["light.kitchen"]!!.attributes["friendly_name"]!!.jsonPrimitive.content)
    }
}
