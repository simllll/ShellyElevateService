package com.stretter.shellyelevateservice.dashboard.theme

import androidx.compose.ui.graphics.Color
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CssColorsTest {
    private fun entity(id: String, state: String, deviceClass: String? = null) =
        EntityState(id, state, jsonOf("device_class" to deviceClass), 0, 0)

    @Test
    fun `css color syntax`() {
        assertEquals(Color(0xFFFF0000), CssColors.parse("#f00"))
        assertEquals(Color(0xFF44739E), CssColors.parse("#44739e"))
        assertEquals(Color(0x80FF0000), CssColors.parse("#ff000080"))
        assertEquals(Color(255, 152, 0), CssColors.parse("rgb(255, 152, 0)"))
        assertEquals(Color(0xFFFFC107), CssColors.parse("var(--amber-color)"))
        assertEquals(Color(0xFF00FF00), CssColors.parse("var(--unknown, #0f0)"))
        assertEquals(Color(0xFFFFC107), CssColors.computeCssColor("amber"))
        assertNull(CssColors.parse("none"))
    }

    @Test
    fun `state colors follow the frontend variables`() {
        assertEquals(Color(0xFFFFC107), CssColors.stateColor(entity("light.a", "on")))
        assertEquals(Color(0xFF9E9E9E), CssColors.stateColor(entity("light.a", "off")))
        assertEquals(Color(0xFF926BC7), CssColors.stateColor(entity("cover.a", "open", "shutter")))
        assertEquals(Color(0xFFF44336), CssColors.stateColor(entity("binary_sensor.smoke", "on", "smoke")))
        assertEquals(Color(0xFFFFC107), CssColors.stateColor(entity("binary_sensor.door", "on", "door")))
        assertEquals(Color(0xFF4CAF50), CssColors.stateColor(entity("sensor.battery", "80", "battery")))
        assertNull(CssColors.stateColor(entity("sensor.temp", "21")))
        assertEquals(Color(0xFF6F6F6F), CssColors.stateColor(entity("light.a", "unavailable")))
        // state-badge: inactive falls back to the icon color.
        assertEquals(Color.Blue, CssColors.stateColor(entity("switch.a", "off"), inactiveColor = Color.Blue))
    }
}
