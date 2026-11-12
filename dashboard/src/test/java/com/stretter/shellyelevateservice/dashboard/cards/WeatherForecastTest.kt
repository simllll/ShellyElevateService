package com.stretter.shellyelevateservice.dashboard.cards

import com.stretter.shellyelevateservice.dashboard.format.parseIso
import com.stretter.shellyelevateservice.dashboard.json.HaJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class WeatherForecastTest {
    private val vienna = TimeZone.getTimeZone("Europe/Vienna")

    // A `weather/subscribe_forecast` event as met.no sends it (daily).
    private val event = HaJson.parseToJsonElement(
        """{"type": "daily", "forecast": [
            {"condition": "cloudy", "datetime": "2026-11-12T11:00:00+00:00", "wind_bearing": 300.1,
             "temperature": 10.1, "templow": 9.7, "wind_speed": 11.2, "precipitation": 0.0, "humidity": 85},
            {"condition": "cloudy", "datetime": "2026-11-13T11:00:00+00:00", "temperature": 9.6, "templow": 5.0, "precipitation": 0.1},
            {"condition": "sunny", "datetime": "2026-11-14T11:00:00+00:00", "temperature": 9.2, "templow": 3.3},
            {"condition": "sunny", "datetime": "2026-11-15T11:00:00+00:00", "temperature": 8.2, "templow": 2.9},
            {"condition": "rainy", "datetime": "2026-11-16T11:00:00+00:00", "temperature": 13.9, "templow": 2.8, "is_daytime": true},
            {"condition": "rainy", "datetime": "not a date", "temperature": 1.0}
        ]}"""
    )

    @Test
    fun `parses forecast entries and drops invalid ones`() {
        val items = WeatherForecast.parse(event)
        assertEquals(5, items.size)
        assertEquals(ForecastItem(parseIso("2026-11-12T11:00:00+00:00")!!, "cloudy", 10.1, 9.7, 0.0, null), items[0])
        assertEquals(true, items[4].isDaytime)
        assertEquals(items, WeatherForecast.parse((event as JsonObject)["forecast"]))
    }

    @Test
    fun `forecast needs more than two entries`() {
        val items = WeatherForecast.parse(event)
        assertNull(WeatherForecast.usable(items.take(2)))
        assertEquals(items, WeatherForecast.usable(items))
    }

    @Test
    fun `extrema use today's entries only`() {
        val items = WeatherForecast.parse(event)
        val now = parseIso("2026-11-12T09:00:00+01:00")!!
        assertEquals(10.1 to 9.7, WeatherForecast.extrema(items, now, vienna))
        // Tomorrow's first entry is the only one on that date.
        val tomorrow = parseIso("2026-11-13T08:00:00+01:00")!!
        assertNull(WeatherForecast.extrema(items, tomorrow, vienna))
        assertEquals(9.6 to 5.0, WeatherForecast.extrema(items.drop(1), tomorrow, vienna))
    }

    @Test
    fun `hourly extrema without templow use the temperatures`() {
        val hourly = WeatherForecast.parse(
            HaJson.parseToJsonElement(
                """[{"datetime": "2026-11-12T10:00:00+00:00", "temperature": 7.0},
                    {"datetime": "2026-11-12T11:00:00+00:00", "temperature": 9.5},
                    {"datetime": "2026-11-12T12:00:00+00:00", "temperature": 8.0},
                    {"datetime": "2026-11-13T00:00:00+00:00", "temperature": 2.0}]"""
            )
        )
        assertEquals(9.5 to 7.0, WeatherForecast.extrema(hourly, parseIso("2026-11-12T10:30:00+00:00")!!, vienna))
    }

    @Test
    fun `weekday names are short without dots`() {
        val thursday = parseIso("2026-11-12T11:00:00+00:00")!!
        assertEquals("Do", WeatherForecast.weekdayShort(thursday, Locale.GERMAN, vienna))
        assertEquals("Thu", WeatherForecast.weekdayShort(thursday, Locale.ENGLISH, vienna))
    }
}
