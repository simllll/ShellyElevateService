package com.stretter.shellyelevateservice.dashboard.badges

import com.stretter.shellyelevateservice.dashboard.json.HaJson
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeSpecTest {
    private fun view(badges: String): JsonObject = HaJson.parseToJsonElement("""{"badges":$badges}""").jsonObject

    @Test
    fun `entity badge defaults show state and icon but not the name`() {
        val badge = BadgeSpec.parseAll(view("""[{"type":"entity","entity":"sensor.time","show_name":false,"show_state":true,"show_icon":true,"color":""}]""")).single()
        badge as BadgeSpec.Entity
        assertEquals("sensor.time", badge.entityId)
        assertFalse(badge.showName)
        assertTrue(badge.showState)
        assertTrue(badge.showIcon)
        assertNull("empty color means state color", badge.color)

        val defaults = BadgeSpec.parse(HaJson.parseToJsonElement("""{"type":"entity","entity":"light.x"}""")) as BadgeSpec.Entity
        assertFalse(defaults.showName)
        assertTrue(defaults.showState)
        assertFalse(defaults.showEntityPicture)
    }

    @Test
    fun `strings and objects without type are entity badges`() {
        val badges = BadgeSpec.parseAll(view("""["sun.sun", {"entity":"person.simon"}, null, 3]"""))
        assertEquals(2, badges.size)
        val fromString = badges[0] as BadgeSpec.Entity
        assertEquals("sun.sun", fromString.entityId)
        assertTrue("legacy string badges show the name", fromString.showName)
        val fromObject = badges[1] as BadgeSpec.Entity
        assertEquals("person.simon", fromObject.entityId)
        assertEquals("entity", fromObject.config.string("type"))
        assertFalse(fromObject.showName)
    }

    @Test
    fun `state-label and display_type are migrated`() {
        val label = BadgeSpec.parse(HaJson.parseToJsonElement("""{"type":"state-label","entity":"sensor.t","color":"red"}""")) as BadgeSpec.Entity
        assertTrue(label.showName)
        assertEquals("red", label.color)
        val complete = BadgeSpec.parse(HaJson.parseToJsonElement("""{"type":"entity","entity":"a.b","display_type":"complete"}""")) as BadgeSpec.Entity
        assertTrue(complete.showName)
        assertTrue(complete.showState)
        val minimal = BadgeSpec.parse(HaJson.parseToJsonElement("""{"type":"entity","entity":"a.b","display_type":"minimal"}""")) as BadgeSpec.Entity
        assertFalse(minimal.showState)
    }

    @Test
    fun `disabled badges are dropped, unknown types kept as unsupported`() {
        val badges = BadgeSpec.parseAll(view("""[{"type":"entity","entity":"a.b","disabled":true},{"type":"custom:fancy-badge"}]"""))
        assertEquals(listOf("custom:fancy-badge"), badges.map { (it as BadgeSpec.Unsupported).type })
    }

    @Test
    fun `mushroom template badge keeps its templates and visibility`() {
        val badge = BadgeSpec.parseAll(
            view(
                """[{"type":"custom:mushroom-template-badge","label":"Roborock Staubsauger",
                "content":"{{ states('vacuum.x') }}","icon":"{% if true %}\nmdi:vacuum\n{% endif %}","color":"",
                "entity":"weather.home","picture":"/local/weather/day.svg",
                "visibility":[{"condition":"state","entity":"vacuum.x","state_not":"cleaning"}]}]"""
            )
        ).single() as BadgeSpec.MushroomTemplate
        assertEquals("Roborock Staubsauger", badge.label)
        assertEquals("{{ states('vacuum.x') }}", badge.content)
        assertEquals("/local/weather/day.svg", badge.picture)
        assertEquals("weather.home", badge.variables.string("entity"))
        assertTrue(badge.config.containsKey("visibility"))
    }

    @Test
    fun `template output whitespace collapses like HTML text`() {
        assertEquals("Angedockt - Wird geladen", BadgeSpec.collapseWhitespace("\n Angedockt\n -\n Wird geladen\n"))
        assertEquals("mdi:vacuum", BadgeSpec.collapseWhitespace("\nmdi:vacuum\n"))
        assertNull(BadgeSpec.collapseWhitespace(" \n "))
        assertNull(BadgeSpec.collapseWhitespace(null))
    }
}
