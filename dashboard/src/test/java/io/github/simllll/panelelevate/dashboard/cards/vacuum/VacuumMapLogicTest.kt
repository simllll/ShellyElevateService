package io.github.simllll.panelelevate.dashboard.cards.vacuum

import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.json.HaJson
import io.github.simllll.panelelevate.dashboard.json.objects
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VacuumMapLogicTest {
    private fun json(text: String): JsonObject = HaJson.parseToJsonElement(text).jsonObject

    private fun entity(id: String, state: String, attributes: String = "{}") = EntityState(id, state, json(attributes), 0, 0)

    /** Calibration points of image.roborock_s8_pro_ultra_map_0_custom (live instance). */
    private val roborockCalibration = parseCalibrationPoints(
        HaJson.parseToJsonElement(
            """[{"vacuum":{"x":25500,"y":25500},"map":{"x":1144.0,"y":1444.0}},
                {"vacuum":{"x":35500,"y":25500},"map":{"x":1944.0,"y":1444.0}},
                {"vacuum":{"x":25500,"y":35500},"map":{"x":1144.0,"y":644.0}}]"""
        )
    )

    private fun assertPoint(expected: MapPoint, actual: MapPoint, delta: Double = 1e-6) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }

    @Test
    fun affineCalibrationMapsRoomIconsOntoTheImage() {
        val converter = CoordinatesConverter.create(roborockCalibration)!!
        // Balkon icon: 1144 + 0.08 · (21475 − 25500), 1444 − 0.08 · (23750 − 25500)
        assertPoint(MapPoint(822.0, 1584.0), converter.vacuumToMap(21475.0, 23750.0))
        assertPoint(MapPoint(1144.0, 1444.0), converter.vacuumToMap(25500.0, 25500.0))
        assertPoint(MapPoint(21475.0, 23750.0), converter.mapToVacuum(822.0, 1584.0))
    }

    @Test
    fun perspectiveCalibrationFromFourPoints() {
        val points = parseCalibrationPoints(
            HaJson.parseToJsonElement(
                """[{"vacuum":{"x":0,"y":0},"map":{"x":10,"y":20}},
                    {"vacuum":{"x":100,"y":0},"map":{"x":210,"y":30}},
                    {"vacuum":{"x":100,"y":100},"map":{"x":190,"y":240}},
                    {"vacuum":{"x":0,"y":100},"map":{"x":0,"y":200}}]"""
            )
        )
        val converter = CoordinatesConverter.create(points)!!
        points!!.forEach { assertPoint(it.map, converter.vacuumToMap(it.vacuum.x, it.vacuum.y), 1e-6) }
        val mid = converter.vacuumToMap(37.0, 61.0)
        assertPoint(MapPoint(37.0, 61.0), converter.mapToVacuum(mid.x, mid.y), 1e-6)
    }

    @Test
    fun invalidCalibrations() {
        assertNull(parseCalibrationPoints(HaJson.parseToJsonElement("""[{"vacuum":{"x":0,"y":0},"map":{"x":0,"y":0}}]""")))
        assertNull(parseCalibrationPoints(null))
        val collinear = parseCalibrationPoints(
            HaJson.parseToJsonElement(
                """[{"vacuum":{"x":0,"y":0},"map":{"x":0,"y":0}},
                    {"vacuum":{"x":1,"y":1},"map":{"x":1,"y":1}},
                    {"vacuum":{"x":2,"y":2},"map":{"x":2,"y":2}}]"""
            )
        )
        assertNull(CoordinatesConverter.create(collinear))
    }

    @Test
    fun roborockSegmentCleanServiceCall() {
        val call = RoborockTemplates.segmentClean("vacuum.roborock_s8_pro_ultra", listOf(JsonPrimitive(16), JsonPrimitive("27")), 2)
        assertEquals("vacuum", call.domain)
        assertEquals("send_command", call.service)
        assertEquals(
            """{"command":"app_segment_clean","params":[{"segments":[16,27],"repeat":2}],"entity_id":"vacuum.roborock_s8_pro_ultra"}""",
            call.data.toString(),
        )
    }

    @Test
    fun repeatsCycle() {
        assertEquals(listOf(2, 3, 1), listOf(1, 2, 3).map { RoborockTemplates.nextRepeats(it) })
    }

    @Test
    fun parsesPredefinedRooms() {
        val rooms = parseRooms(
            json(
                """{"template":"vacuum_clean_segment","predefined_selections":[
                    {"id":16,"icon":{"name":"mdi:walk","x":23100,"y":34200},"outline":[[22300,32900],[23900,32900],[23900,35500]]},
                    {"id":"x"}]}"""
            )
        )
        assertEquals(2, rooms.size)
        assertEquals("16", rooms[0].key)
        assertEquals("mdi:walk", rooms[0].icon)
        assertEquals(MapPoint(23100.0, 34200.0), rooms[0].iconPosition)
        assertEquals(3, rooms[0].outline.size)
        assertTrue(rooms[1].outline.isEmpty())
    }

    @Test
    fun pointInPolygonHitTest() {
        val l = listOf(MapPoint(0.0, 0.0), MapPoint(10.0, 0.0), MapPoint(10.0, 4.0), MapPoint(4.0, 4.0), MapPoint(4.0, 10.0), MapPoint(0.0, 10.0))
        assertTrue(pointInPolygon(2.0, 8.0, l))
        assertTrue(pointInPolygon(8.0, 2.0, l))
        assertFalse(pointInPolygon(8.0, 8.0, l))
        assertFalse(pointInPolygon(1.0, 1.0, l.take(2)))
    }

    private val icons = json(
        """{"icons":[
          {"icon":"mdi:pause","conditions":[{"entity":"vacuum.r","value_not":"docked"},{"entity":"vacuum.r","value_not":"idle"}]},
          {"icon":"mdi:map-marker"},
          {"menu_id":"mop","icon":"mdi:water-alert","conditions":[{"entity":"select.w","value":"off"}]},
          {"menu_id":"mop","icon":"mdi:water","conditions":[{"entity":"select.w","value":"custom"}]},
          {"menu_id":"fan","icon":"mdi:fan-alert","label":"Leise","conditions":[{"entity":"vacuum.r","attribute":"fan_speed","value":"quiet"}]},
          {"menu_id":"fan","icon":"mdi:fan-alert","label":"Balanced","conditions":[{"entity":"vacuum.r","attribute":"fan_speed","value":"balanced"}]},
          {"icon":"mdi:fan-alert","conditions":[{"entity":"vacuum.r","attribute":"fan_speed","value_not":"quiet"},{"entity":"vacuum.r","attribute":"fan_speed","value_not":"balanced"}]}
        ]}"""
    ).objects("icons")

    @Test
    fun iconsLikeTheScreenshotWhileDocked() {
        val states = mapOf(
            "vacuum.r" to entity("vacuum.r", "docked", """{"fan_speed":"balanced"}"""),
            "select.w" to entity("select.w", "standard"),
        )
        val items = preprocessIcons(icons) { states[it] }
        assertEquals(2, items.size)
        assertEquals("mdi:map-marker", (items[0] as IconItem.Single).config["icon"].let { (it as JsonPrimitive).content })
        val menu = items[1] as IconItem.Menu
        assertEquals("fan", menu.menuId)
        assertEquals(2, menu.entries.size)
        assertEquals("Balanced", (menu.selected.config["label"] as JsonPrimitive).content)
    }

    @Test
    fun iconsWhileCleaning() {
        val states = mapOf(
            "vacuum.r" to entity("vacuum.r", "cleaning", """{"fan_speed":"turbo"}"""),
            "select.w" to entity("select.w", "custom"),
        )
        val items = preprocessIcons(icons) { states[it] }
        assertEquals(listOf("pause", "single", "mop", "single"), items.map {
            when (it) {
                is IconItem.Menu -> it.menuId
                is IconItem.Single -> if ((it.config["icon"] as JsonPrimitive).content == "mdi:pause") "pause" else "single"
            }
        })
        assertTrue(conditionsMet(emptyList()) { null })
        assertFalse(conditionsMet(listOf(json("""{"entity":"vacuum.missing","value":"x"}"""))) { null })
    }

    @Test
    fun tileValues() {
        val battery = entity("sensor.b", "100", """{"unit_of_measurement":"%"}""")
        assertEquals("100 %", tileValue(json("""{"entity":"sensor.b","unit":"%"}"""), battery, "100 %", percentWithBlank = true))
        assertEquals("100%", tileValue(json("""{"entity":"sensor.b","unit":"%"}"""), battery, "100%", percentWithBlank = false))

        val time = entity("sensor.t", "8", """{"unit_of_measurement":"s"}""")
        assertEquals("0 min", tileValue(json("""{"entity":"sensor.t","unit":"min","multiplier":0.016667,"precision":0}"""), time, "8 s", true))
        val longer = entity("sensor.t", "3600", """{"unit_of_measurement":"s"}""")
        assertEquals("60 min", tileValue(json("""{"entity":"sensor.t","unit":"min","multiplier":0.016667,"precision":0}"""), longer, null, true))

        val area = entity("sensor.a", "0.0", """{"unit_of_measurement":"m²"}""")
        assertEquals("0.00 m²", tileValue(json("""{"entity":"sensor.a","unit":"m²","precision":2,"translations":{}}"""), area, "0,0 m²", true))

        val vacuum = entity("vacuum.r", "docked", """{"fan_speed":"balanced"}""")
        val status = json("""{"entity":"vacuum.r","translations":{"idle":"Idle","angedockt":"Zuhause"}}""")
        assertEquals("Zuhause", tileValue(status, vacuum, "Angedockt", true))
        assertEquals("Ausgewogen", tileValue(json("""{"entity":"vacuum.r","attribute":"fan_speed"}"""), vacuum, "Ausgewogen", true))
        assertEquals("", tileValue(json("""{"entity":"vacuum.gone"}"""), null, null, true))
    }

    @Test
    fun tileIcons() {
        val states = mapOf(
            "vacuum.r" to entity("vacuum.r", "docked", """{"battery_icon":"mdi:battery-80"}"""),
            "sensor.x" to entity("sensor.x", "1", """{"icon":"mdi:flash"}"""),
        )
        assertEquals("mdi:battery-80", tileIcon(json("""{"entity":"sensor.b","icon_source":"vacuum.r.attributes.battery_icon"}""")) { states[it] })
        assertNull(tileIcon(json("""{"entity":"sensor.b","icon_source":"vacuum.r.attributes.missing"}""")) { states[it] })
        assertEquals("docked", tileIcon(json("""{"icon_source":"vacuum.r"}""")) { states[it] })
        assertEquals("mdi:fan", tileIcon(json("""{"entity":"sensor.x","icon":"mdi:fan"}""")) { states[it] })
        assertEquals("mdi:flash", tileIcon(json("""{"entity":"sensor.x"}""")) { states[it] })
        assertNull(tileIcon(json("""{"entity":"vacuum.r"}""")) { states[it] })
        assertEquals(setOf("sensor.b", "vacuum.r"), tileEntities(listOf(json("""{"entity":"sensor.b","icon_source":"vacuum.r.attributes.battery_icon"}"""))))
    }

    @Test
    fun flexLineBreaking() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5)), flexLines(listOf(110, 116, 110, 170, 170, 400), 352, 5))
        assertEquals(listOf(listOf(0), listOf(1)), flexLines(listOf(500, 10), 352, 5))
        assertTrue(flexLines(emptyList(), 100, 5).isEmpty())
    }
}
