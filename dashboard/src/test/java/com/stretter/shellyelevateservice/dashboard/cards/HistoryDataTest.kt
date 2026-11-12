package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.ui.graphics.Color
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.HassClient
import com.stretter.shellyelevateservice.dashboard.hass.HistoryPoint
import com.stretter.shellyelevateservice.dashboard.json.HaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class HistoryDataTest {
    private val vienna = TimeZone.getTimeZone("Europe/Vienna")
    private val hour = 3_600_000L

    private fun entity(id: String, state: String, attrs: Map<String, String> = emptyMap(), changed: Long = 0) =
        EntityState(id, state, JsonObject(attrs.mapValues { JsonPrimitive(it.value) }), changed, changed)

    @Test
    fun `parses minimal history and builds merged timeline segments`() {
        // Compressed `history_during_period` result: lc omitted when equal to lu.
        val result = HaJson.parseToJsonElement(
            """{"sensor.someone_at_home": [
                {"s": "nearby", "lu": 1000.0},
                {"s": "nearby", "lu": 2000.0},
                {"s": "home", "lu": 3000.0},
                {"s": "home", "lc": 3000.0, "lu": 3500.0}
            ]}"""
        )
        val points = HassClient.parseHistory(result).getValue("sensor.someone_at_home")
        assertEquals(4, points.size)
        val segments = HistoryData.timelineSegments(points, startMs = 500_000, endMs = 5_000_000)
        assertEquals(
            listOf(
                TimelineSegment("nearby", 1_000_000, 3_000_000),
                TimelineSegment("home", 3_000_000, 5_000_000),
            ),
            segments,
        )
    }

    @Test
    fun `segments are clipped to the window and later points dropped`() {
        val points = listOf(HistoryPoint("off", 0), HistoryPoint("on", 10_000), HistoryPoint("off", 50_000))
        val segments = HistoryData.timelineSegments(points, startMs = 5_000, endMs = 40_000)
        assertEquals(listOf(TimelineSegment("off", 5_000, 10_000), TimelineSegment("on", 10_000, 40_000)), segments)
    }

    @Test
    fun `live state is appended only when it changed after the last point`() {
        val points = listOf(HistoryPoint("home", 1_000))
        assertSame(points, HistoryData.appendLive(points, entity("sensor.x", "home", changed = 5_000)))
        assertSame(points, HistoryData.appendLive(points, entity("sensor.x", "away", changed = 500)))
        assertEquals(
            listOf(HistoryPoint("home", 1_000), HistoryPoint("away", 5_000)),
            HistoryData.appendLive(points, entity("sensor.x", "away", changed = 5_000)),
        )
    }

    @Test
    fun `trim keeps the last point before the window`() {
        val points = listOf(HistoryPoint("a", 0), HistoryPoint("b", 10), HistoryPoint("c", 20), HistoryPoint("d", 30))
        assertEquals(listOf("b", "c", "d"), HistoryData.trim(points, 15).map { it.state })
        assertEquals(listOf("d"), HistoryData.trim(points, 100).map { it.state })
        assertEquals(points, HistoryData.trim(points, 0))
    }

    @Test
    fun `numeric detection follows isNumericEntity`() {
        assertTrue(HistoryData.isNumeric("sensor.t", entity("sensor.t", "9.2", mapOf("unit_of_measurement" to "°C"))))
        assertTrue(HistoryData.isNumeric("sensor.p", entity("sensor.p", "3", mapOf("device_class" to "power"))))
        assertTrue(HistoryData.isNumeric("input_number.x", entity("input_number.x", "3")))
        assertFalse(HistoryData.isNumeric("sensor.someone_at_home", entity("sensor.someone_at_home", "home")))
        assertFalse(HistoryData.isNumeric("climate.x", entity("climate.x", "heat", mapOf("unit_of_measurement" to "°C"))))
        val history = listOf(HistoryPoint("1", 0, JsonObject(mapOf("unit_of_measurement" to JsonPrimitive("W")))))
        assertTrue(HistoryData.isNumeric("sensor.gone", null, history))
    }

    @Test
    fun `numeric points turn non-numeric states into gaps`() {
        val pts = HistoryData.numericPoints(listOf(HistoryPoint("1.5", 0), HistoryPoint("unavailable", 10), HistoryPoint("2", 20)))
        assertEquals(listOf(0L to 1.5, 10L to null, 20L to 2.0), pts)
    }

    @Test
    fun `24 hour window gets 4 hour ticks on local time with midnight flagged`() {
        val end = 1_762_950_600_000L // 2025-11-12 13:30 in Vienna (UTC+1)
        val start = end - 24 * hour
        val ticks = HistoryData.timeTicks(start, end, zone = vienna)
        val labels = ticks.map { HistoryData.timeLabel(it.time, Locale.GERMAN, vienna) }
        assertEquals(listOf("16:00", "20:00", "0:00", "4:00", "8:00", "12:00"), labels)
        assertEquals(listOf(false, false, true, false, false, false), ticks.map { it.midnight })
        assertEquals("12. Nov.", HistoryData.dateLabel(ticks[2].time, Locale.GERMAN, vienna))
    }

    @Test
    fun `value ticks are nice and cover the data`() {
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), HistoryData.valueTicks(0.3, 9.7))
        assertEquals(listOf(20.0, 20.5, 21.0, 21.5, 22.0), HistoryData.valueTicks(20.1, 21.9))
        val flat = HistoryData.valueTicks(5.0, 5.0)
        assertTrue(flat.first() <= 4.0 && flat.last() >= 6.0)
    }

    @Test
    fun `generic timeline colors follow the graph palette in order of appearance`() {
        val e = entity("sensor.test_presence", "x")
        val noStateColor: (EntityState, String) -> Color? = { _, _ -> null }
        val first = TimelineColors.color("test-state-alpha", e, noStateColor)
        val second = TimelineColors.color("test-state-beta", e, noStateColor)
        assertEquals(first, TimelineColors.color("test-state-alpha", e, noStateColor))
        assertEquals(TimelineColors.GRAPH.indexOf(first) + 1, TimelineColors.GRAPH.indexOf(second))
        assertNull(TimelineColors.color("unavailable", e, noStateColor))
        assertEquals(TimelineColors.UNKNOWN, TimelineColors.color("unknown", e, noStateColor))
    }

    @Test
    fun `enum sensors use their option index`() {
        val e = EntityState(
            "sensor.mode", "b",
            HaJson.parseToJsonElement("""{"device_class": "enum", "options": ["a", "b", "c"]}""") as JsonObject, 0, 0,
        )
        assertEquals(TimelineColors.GRAPH[1], TimelineColors.color("b", e) { _, _ -> null })
    }

    @Test
    fun `bar text is black on yellow and white on blue`() {
        assertEquals(Color.Black, TimelineColors.textColor(TimelineColors.GRAPH[1])) // #f4bd4a "home"
        assertEquals(Color.White, TimelineColors.textColor(TimelineColors.GRAPH[0])) // #4269d0 "nearby"
    }
}
