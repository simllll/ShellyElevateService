package com.stretter.shellyelevateservice.dashboard.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RelativeTimeTest {
    private val zone = TimeZone.getTimeZone("Europe/Vienna")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = Calendar.getInstance(zone).apply {
        clear()
        set(y, m - 1, d, h, min)
    }.timeInMillis

    private val now = at(2026, 11, 12, 16, 0)

    @Test
    fun `units follow the frontend thresholds`() {
        assertEquals(-30L to TimeUnitKind.SECOND, selectUnit(now - 30_000, now, zone))
        assertEquals(-17L to TimeUnitKind.MINUTE, selectUnit(now - 17 * 60_000, now, zone))
        assertEquals(-4L to TimeUnitKind.HOUR, selectUnit(now - 4 * 3_600_000, now, zone))
        // 23 h ago but the previous calendar day: "yesterday".
        assertEquals(-1L to TimeUnitKind.DAY, selectUnit(at(2026, 11, 11, 17), now, zone))
        assertEquals(-3L to TimeUnitKind.DAY, selectUnit(at(2026, 11, 9, 10), now, zone))
        assertEquals(-2L to TimeUnitKind.WEEK, selectUnit(at(2026, 10, 28), now, zone))
        assertEquals(-3L to TimeUnitKind.MONTH, selectUnit(at(2026, 8, 1), now, zone))
        assertEquals(-2L to TimeUnitKind.YEAR, selectUnit(at(2024, 6, 1), now, zone))
        assertEquals(5L to TimeUnitKind.MINUTE, selectUnit(now + 5 * 60_000, now, zone))
    }

    @Test
    fun `iso timestamps from HA are parsed`() {
        assertEquals(1_700_000_000_000L, parseIso("2023-11-14T22:13:20+00:00"))
        assertEquals(1_700_000_000_123L, parseIso("2023-11-14T22:13:20.123456Z"))
        assertEquals(1_700_000_000_000L, parseIso("2023-11-14T23:13:20+01:00"))
        assertEquals("2023-11-14T22:13:20.000Z", isoUtc(1_700_000_000_000L))
    }
}
