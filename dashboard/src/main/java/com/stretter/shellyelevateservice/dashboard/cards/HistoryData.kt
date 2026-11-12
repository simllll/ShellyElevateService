package com.stretter.shellyelevateservice.dashboard.cards

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.HistoryPoint
import com.stretter.shellyelevateservice.dashboard.json.asStringList
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One bar of a timeline row: [state] from [start] to [end] (epoch ms). */
@Immutable
data class TimelineSegment(val state: String, val start: Long, val end: Long)

/** Tick of a time axis; [midnight] ticks are labelled with the date (bold) instead of the time. */
@Immutable
data class TimeTick(val time: Long, val midnight: Boolean)

/** Pure history logic of hui-history-graph-card / state-history-chart-* (data/history.ts). */
object HistoryData {
    private val NUMERIC_DOMAINS = setOf("counter", "input_number", "number")
    private val LINE_CHART_DOMAINS = setOf("climate", "humidifier", "water_heater", "zone")
    private val NUMERIC_SENSOR_DEVICE_CLASSES = setOf(
        "absolute_humidity", "apparent_power", "aqi", "area", "atmospheric_pressure", "battery",
        "blood_glucose_concentration", "carbon_dioxide", "carbon_monoxide", "conductivity", "current",
        "data_rate", "data_size", "distance", "duration", "energy", "energy_distance", "energy_storage",
        "frequency", "gas", "humidity", "illuminance", "irradiance", "moisture", "monetary",
        "nitrogen_dioxide", "nitrogen_monoxide", "nitrous_oxide", "ozone", "ph", "pm1", "pm10", "pm25",
        "pm4", "power", "power_factor", "precipitation", "precipitation_intensity", "pressure", "radon",
        "reactive_energy", "reactive_power", "signal_strength", "sound_pressure", "speed",
        "sulphur_dioxide", "temperature", "temperature_delta", "volatile_organic_compounds",
        "volatile_organic_compounds_parts", "voltage", "volume", "volume_flow_rate", "volume_storage",
        "water", "weight", "wind_direction", "wind_speed",
    )

    /**
     * `isNumericEntity`: line chart instead of timeline. Numeric domains, entities with a unit or
     * state class, numeric sensor device classes, or (without a current state) history with a unit.
     * Climate-like domains get their own line charts in HA; they are drawn as timelines here.
     */
    fun isNumeric(entityId: String, current: EntityState?, history: List<HistoryPoint> = emptyList()): Boolean {
        val domain = entityId.substringBefore('.')
        if (domain in LINE_CHART_DOMAINS) return false
        if (domain in NUMERIC_DOMAINS) return true
        if (current != null) {
            if ("unit_of_measurement" in current.attributes || "state_class" in current.attributes) return true
            if (domain == "sensor" && current.deviceClass in NUMERIC_SENSOR_DEVICE_CLASSES) return true
            return false
        }
        return history.any { p -> p.attributes?.let { "unit_of_measurement" in it || "state_class" in it } == true }
    }

    /**
     * `generateStateHistoryChartTimelineData`: consecutive equal states merge, points after [endMs]
     * are dropped, the last state lasts until [endMs]. Segments are clipped to the window.
     */
    fun timelineSegments(points: List<HistoryPoint>, startMs: Long, endMs: Long): List<TimelineSegment> {
        val result = ArrayList<TimelineSegment>()
        var prevState: String? = null
        var prevChanged = startMs
        fun push(state: String, from: Long, to: Long) {
            val s = maxOf(from, startMs)
            val e = minOf(to, endMs)
            if (e > s) result += TimelineSegment(state, s, e)
        }
        for (p in points) {
            if (p.lastChanged > endMs) continue
            when {
                prevState == null -> { prevState = p.state; prevChanged = p.lastChanged }
                p.state != prevState -> {
                    push(prevState, prevChanged, p.lastChanged)
                    prevState = p.state
                    prevChanged = p.lastChanged
                }
            }
        }
        if (prevState != null) push(prevState, prevChanged, endMs)
        return result
    }

    /**
     * Appends the live state to fetched history when it changed after the last known point
     * (what the history stream subscription does in the frontend). Returns [points] unchanged otherwise.
     */
    fun appendLive(points: List<HistoryPoint>, live: EntityState?): List<HistoryPoint> {
        if (live == null) return points
        val last = points.lastOrNull()
        if (last != null && (live.lastChanged <= last.lastChanged || live.state == last.state)) return points
        return points + HistoryPoint(live.state, live.lastChanged)
    }

    /** Drops points before [startMs], keeping the last one so the first bar starts at the window edge. */
    fun trim(points: List<HistoryPoint>, startMs: Long): List<HistoryPoint> {
        val firstInside = points.indexOfFirst { it.lastChanged >= startMs }
        return when {
            firstInside == -1 -> points.takeLast(1)
            firstInside <= 1 -> points
            else -> points.subList(firstInside - 1, points.size)
        }
    }

    /** Numeric points (state, time); non-numeric states (unavailable, unknown) become gaps (null). */
    fun numericPoints(points: List<HistoryPoint>): List<Pair<Long, Double?>> =
        points.map { it.lastChanged to it.state.toDoubleOrNull()?.takeIf { v -> v.isFinite() } }

    private val TICK_INTERVALS = longArrayOf(
        60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
        3_600_000, 2 * 3_600_000, 3 * 3_600_000, 4 * 3_600_000, 6 * 3_600_000, 12 * 3_600_000,
        86_400_000, 2 * 86_400_000, 7 * 86_400_000,
    )

    /**
     * Ticks of a time axis between [startMs] and [endMs]: the smallest "nice" interval giving at
     * most [maxTicks] ticks, aligned to local time (so 4 h steps land on 0:00, 4:00, 8:00, …).
     */
    fun timeTicks(startMs: Long, endMs: Long, maxTicks: Int = 6, zone: TimeZone = TimeZone.getDefault()): List<TimeTick> {
        if (endMs <= startMs || maxTicks < 1) return emptyList()
        val span = endMs - startMs
        val interval = TICK_INTERVALS.firstOrNull { span / it <= maxTicks } ?: (span / maxTicks)
        val cal = Calendar.getInstance(zone)
        val result = ArrayList<TimeTick>()
        if (interval >= 86_400_000) {
            cal.timeInMillis = startMs
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val days = (interval / 86_400_000).toInt()
            while (cal.timeInMillis < startMs) cal.add(Calendar.DAY_OF_MONTH, days)
            while (cal.timeInMillis <= endMs) {
                result += TimeTick(cal.timeInMillis, true)
                cal.add(Calendar.DAY_OF_MONTH, days)
            }
            return result
        }
        // Align on local wall-clock time: offset the epoch by the zone offset at the start.
        val offset = zone.getOffset(startMs).toLong()
        var t = Math.floorDiv(startMs + offset, interval) * interval - offset
        if (t < startMs) t += interval
        while (t <= endMs) {
            // Midnight uses the offset at the tick itself, so it stays right across DST changes.
            val local = t + zone.getOffset(t)
            val midnight = Math.floorMod(local, 86_400_000L) == 0L
            result += TimeTick(t, midnight)
            t += interval
        }
        return result
    }

    /**
     * Y axis of a value axis with `scale: true`: "nice" step (1, 2, 5 × 10ⁿ) for about [splits]
     * intervals and bounds rounded outwards to it. Returns the tick values, lowest first.
     */
    fun valueTicks(min: Double, max: Double, splits: Int = 5): List<Double> {
        var lo = min
        var hi = max
        if (!lo.isFinite() || !hi.isFinite()) return emptyList()
        if (hi - lo < 1e-9) { lo -= 1; hi += 1 }
        val raw = (hi - lo) / splits
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw - 1e-12 }
        val start = Math.floor(lo / step + 1e-9) * step
        val end = Math.ceil(hi / step - 1e-9) * step
        val count = Math.round((end - start) / step).toInt()
        return (0..count).map { start + it * step }.map { Math.round(it / step) * step }
    }

    /** `formatTime` (Intl `hour: "numeric"`): "4:00" rather than "04:00" in 24 h locales. */
    fun timeLabel(ms: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String {
        val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern() ?: "H:mm"
        return SimpleDateFormat(pattern.replace("HH", "H").replace("hh", "h"), locale).apply { timeZone = zone }.format(Date(ms))
    }

    /** `formatDateVeryShort` (day + short month): "13. Nov." / "Nov 13". */
    fun dateLabel(ms: Long, locale: Locale, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(if (locale.language == "de") "d. MMM" else "MMM d", locale).apply { timeZone = zone }.format(Date(ms))

    /** Pixel x of [time] within [width] for the window [startMs]..[endMs]. */
    fun x(time: Long, startMs: Long, endMs: Long, width: Float): Float =
        if (endMs <= startMs) 0f else (time - startMs).toFloat() / (endMs - startMs) * width
}

/**
 * `computeTimelineColor`: state color (only for state-colored domains), enum position color
 * (sensor enum / select options), else a color per distinct state from the graph palette.
 * Like the frontend's module-level map, generic colors are shared by all charts in the process.
 */
object TimelineColors {
    /** `--color-1` … `--color-54`, used for graph series and generic timeline states. */
    val GRAPH = listOf(
        0x4269d0, 0xf4bd4a, 0xff725c, 0x6cc5b0, 0xa463f2, 0xff8ab7, 0x9c6b4e, 0x97bbf5, 0x01ab63,
        0x094bad, 0xc99000, 0xd84f3e, 0x49a28f, 0x048732, 0xd96895, 0x8043ce, 0x7599d1, 0x7a4c31,
        0x6989f4, 0xffd444, 0xff957c, 0x8fe9d3, 0x62cc71, 0xffadda, 0xc884ff, 0xbadeff, 0xbf8b6d,
        0x927acc, 0x97ee3f, 0xbf3947, 0x9f5b00, 0xf48758, 0x8caed6, 0xf2b94f, 0xeff26e, 0xe43872,
        0xd9b100, 0x9d7a00, 0x698cff, 0x00d27e, 0xd06800, 0x009f82, 0xc49200, 0xcbe8ff, 0xfecddf,
        0xc27eb6, 0x8cd2ce, 0xc4b8d9, 0xf883b0, 0xa49100, 0xf48800, 0x27d0df, 0xa04a9b, 0x4269d0,
    ).map { Color(0xFF000000.toInt() or it) }

    /** `--history-unknown-color` (dark grey). Unavailable is transparent (null). */
    val UNKNOWN = Color(0xFF606060)

    private val generic = LinkedHashMap<String, Color>()

    fun graphColor(index: Int): Color = GRAPH[Math.floorMod(index, GRAPH.size)]

    /** Null = transparent (unavailable or no entity). */
    @Synchronized
    fun color(state: String, entity: EntityState?, stateColor: (EntityState, String) -> Color? = { e, s -> CssColors.stateColor(e, s) }): Color? {
        if (entity == null || state == EntityState.UNAVAILABLE) return null
        if (state == EntityState.UNKNOWN) return UNKNOWN
        val domain = entity.domain
        // Zones of person/device_tracker have no color variables; only home/not_home do.
        val zoneState = (domain == "person" || domain == "device_tracker") && state != "home" && state != "not_home"
        if (!zoneState) stateColor(entity, state)?.let { return it }
        val options = if ((domain == "sensor" && entity.deviceClass == "enum") || domain == "select" || domain == "input_select") {
            entity.attr("options").asStringList()
        } else emptyList()
        val idx = options.indexOf(state)
        if (idx >= 0) return graphColor(idx)
        return generic.getOrPut(state) { graphColor(generic.size) }
    }

    /** Text on a bar: black on light colors (`luminosity > 0.5`), white otherwise. */
    fun textColor(background: Color): Color {
        fun channel(c: Float): Double = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val l = 0.2126 * channel(background.red) + 0.7152 * channel(background.green) + 0.0722 * channel(background.blue)
        return if (l > 0.5) Color.Black else Color.White
    }
}
