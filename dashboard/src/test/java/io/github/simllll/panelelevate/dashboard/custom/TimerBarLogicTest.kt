package io.github.simllll.panelelevate.dashboard.custom

import io.github.simllll.panelelevate.dashboard.custom.timerbar.TimerBarLogic
import io.github.simllll.panelelevate.dashboard.custom.timerbar.TimerMode
import io.github.simllll.panelelevate.dashboard.format.isoUtc
import io.github.simllll.panelelevate.dashboard.hass.EntityState
import io.github.simllll.panelelevate.dashboard.json.HaJson
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerBarLogicTest {
    private fun json(s: String): JsonObject = HaJson.parseToJsonElement(s).jsonObject
    private fun state(id: String, state: String, lastChanged: Long = 0, vararg attrs: Pair<String, Any?>) =
        EntityState(id, state, jsonOf(*attrs), lastChanged, lastChanged)

    private val now = 1_800_000_000_000L

    @Test
    fun `time formats`() {
        assertEquals("1:02:03", TimerBarLogic.formatTime(3723.0, "hms"))
        assertEquals("2:03", TimerBarLogic.formatTime(123.0, "hms"))
        assertEquals("5", TimerBarLogic.formatTime(5.4, "hms"))
        assertEquals("1:03", TimerBarLogic.formatTime(3723.0, "hm"))
        assertEquals("23", TimerBarLogic.formatTime(1361.0, "hm"))
        assertEquals("1:00", TimerBarLogic.formatTime(3599.5, "hm"))
        assertEquals("1:03h", TimerBarLogic.formatTime(3723.0, "%hmh"))
        assertEquals("23min", TimerBarLogic.formatTime(1361.0, "%mmin"))
        assertEquals("01:02", TimerBarLogic.formatTime(3723.0, "%HH:%MM"))
        assertEquals("2", TimerBarLogic.formatTime(3723.0, "h"))
        assertEquals("hm", TimerBarLogic.formatForResolution(10.0, "minutes"))
        assertEquals("hms", TimerBarLogic.formatForResolution(10.0, "seconds"))
        assertEquals("hm", TimerBarLogic.formatForResolution(4000.0, "automatic"))
        assertEquals(3723.0, TimerBarLogic.parseHms("1:02:03")!!, 0.0)
        assertNull(TimerBarLogic.parseHms("1:02"))
    }

    /** Dishwasher of the dashboard: end time from a timestamp sensor, `%hmh` with a modification below 1 h. */
    @Test
    fun `end_time entity with modifications`() {
        val config = json(
            """{"type": "custom:timer-bar-card", "entity": "switch.dw",
            "end_time": {"entity": "sensor.dw_remaining"}, "resolution": "minutes", "format": "%hmh",
            "modifications": [{"remaining": "01:00:00", "format": "%mmin"}]}"""
        )
        val started = now - 30 * 60_000L
        val states = mapOf(
            "switch.dw" to state("switch.dw", "on", started),
            "sensor.dw_remaining" to state("sensor.dw_remaining", isoUtc(now + 90 * 60_000L)),
        )
        val logic = TimerBarLogic(config, states::get)
        assertEquals(TimerMode.ACTIVE, logic.mode(now))
        assertEquals(5400.0, logic.remaining(now)!!, 0.5)
        assertEquals(7200.0, logic.duration()!!, 0.5)
        assertEquals(25.0, logic.progress(now)!!, 0.1)
        assertEquals("%hmh", logic.modConfig(now).string("format"))
        // 40 minutes before the end the modification applies.
        val later = now + 50 * 60_000L
        assertEquals("%mmin", logic.modConfig(later).string("format"))
        assertEquals("40min", TimerBarLogic.formatTime(logic.remaining(later)!!, logic.modConfig(later).string("format")!!))
    }

    /** Washing machine: remain_time + duration entities in minutes, custom active_state. */
    @Test
    fun `remain_time and duration in minutes`() {
        val config = json(
            """{"entity": "sensor.wm_state", "remain_time": {"entity": "sensor.wm_left", "units": "minutes"},
            "duration": {"entity": "input_number.wm_total", "units": "minutes"}, "active_state": ["Running"]}"""
        )
        val states = mapOf(
            "sensor.wm_state" to state("sensor.wm_state", "Running"),
            "sensor.wm_left" to state("sensor.wm_left", "30"),
            "input_number.wm_total" to state("input_number.wm_total", "120.0"),
        )
        val logic = TimerBarLogic(config, states::get)
        assertEquals(TimerMode.ACTIVE, logic.mode(now))
        assertEquals(1800.0, logic.remaining(now)!!, 0.0)
        assertEquals(75.0, logic.progress(now)!!, 0.0)

        val idle = TimerBarLogic(config, (states + ("sensor.wm_state" to state("sensor.wm_state", "Off")))::get)
        assertEquals(TimerMode.IDLE, idle.mode(now))
    }

    /** Vacuum: an `on` binary sensor without any time information stays idle (shows its state). */
    @Test
    fun `no time information is idle`() {
        val config = json("""{"entity": "binary_sensor.vacuum", "min": 0, "max": 100}""")
        val logic = TimerBarLogic(config, mapOf("binary_sensor.vacuum" to state("binary_sensor.vacuum", "on"))::get)
        assertEquals(TimerMode.IDLE, logic.mode(now))
        assertNull(logic.progress(now))
    }

    @Test
    fun `timer entity with remaining attribute and paused state`() {
        val config = json("""{"entity": "timer.t"}""")
        val active = TimerBarLogic(config, mapOf("timer.t" to state("timer.t", "active", now - 10_000, "remaining" to "0:01:00", "duration" to "0:02:00"))::get)
        assertEquals(TimerMode.ACTIVE, active.mode(now))
        assertEquals(50.0, active.remaining(now)!!, 0.01)
        assertEquals(120.0, active.duration()!!, 0.0)
        val paused = TimerBarLogic(config, mapOf("timer.t" to state("timer.t", "paused", now - 10_000, "remaining" to "0:01:00"))::get)
        assertEquals(TimerMode.PAUSE, paused.mode(now))
        assertEquals(60.0, paused.remaining(now)!!, 0.0)
    }

    @Test
    fun `string actions are normalized`() {
        assertEquals("""{"action":"none"}""", TimerBarLogic.normalizeAction(kotlinx.serialization.json.JsonPrimitive("none")).toString())
    }
}
