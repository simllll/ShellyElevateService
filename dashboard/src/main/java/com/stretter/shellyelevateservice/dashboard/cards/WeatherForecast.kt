package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.runtime.Immutable
import com.stretter.shellyelevateservice.dashboard.format.parseIso
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asBoolean
import com.stretter.shellyelevateservice.dashboard.json.asDouble
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One entry of a `weather/subscribe_forecast` event (`ForecastAttribute`). */
@Immutable
data class ForecastItem(
    val datetime: Long,
    val condition: String? = null,
    val temperature: Double? = null,
    val templow: Double? = null,
    val precipitation: Double? = null,
    val isDaytime: Boolean? = null,
)

/** Pure helpers of data/weather.ts and hui-weather-forecast-card. */
object WeatherForecast {
    /** Parses the event's `forecast` list (or the whole event); entries without a valid datetime are dropped. */
    fun parse(forecast: JsonElement?): List<ForecastItem> {
        val list = forecast.asArray() ?: forecast.asObject()?.get("forecast").asArray() ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e.asObject() ?: return@mapNotNull null
            val datetime = parseIso(o["datetime"].asString()) ?: return@mapNotNull null
            ForecastItem(
                datetime = datetime,
                condition = o["condition"].asString(),
                temperature = o["temperature"].asDouble(),
                templow = o["templow"].asDouble(),
                precipitation = o["precipitation"].asDouble(),
                isDaytime = o["is_daytime"].asBoolean(),
            )
        }
    }

    /**
     * `getForecast` for a subscribed type: the forecast is only used with more than 2 entries.
     * Items without any temperature are skipped when rendering (`_renderForecastItem`).
     */
    fun usable(forecast: List<ForecastItem>?): List<ForecastItem>? = forecast?.takeIf { it.size > 2 }

    /**
     * `getWeatherExtrema`: highest/lowest temperature of the entries on today's date, as
     * (high, low). Mirrors the frontend's truthiness checks, so 0 counts as "unset".
     */
    fun extrema(forecast: List<ForecastItem>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Pair<Double?, Double?>? {
        if (forecast.isEmpty()) return null
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        val today = cal.get(Calendar.DAY_OF_MONTH)
        var high: Double? = null
        var low: Double? = null
        fun unset(v: Double?) = v == null || v == 0.0
        for (fc in forecast) {
            cal.timeInMillis = fc.datetime
            if (cal.get(Calendar.DAY_OF_MONTH) != today) break
            val t = fc.temperature
            if (t != null && (unset(high) || t > high!!)) high = t
            val tl = fc.templow
            if (tl != null && (unset(low) || tl < low!!)) low = tl
            if (unset(tl) && t != null && (unset(low) || t < low!!)) low = t
        }
        if (unset(low) && unset(high)) return null
        return (if (unset(high)) null else high) to (if (unset(low)) null else low)
    }

    /** `formatDateWeekdayShort` (`Intl` weekday "short"): "Do", "Fr", "Thu" (no abbreviation dot). */
    fun weekdayShort(ms: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("EEE", locale).apply { timeZone = zone }.format(Date(ms)).trimEnd('.')

    /** Night artwork only for entries explicitly flagged `is_daytime: false`. */
    fun isNightTime(item: ForecastItem): Boolean = item.isDaytime == false

    /** `getWeatherUnit` for temperatures. */
    fun temperatureUnit(attributes: Map<String, JsonElement>, systemUnit: String = "°C"): String =
        attributes["temperature_unit"].asString() ?: systemUnit
}
