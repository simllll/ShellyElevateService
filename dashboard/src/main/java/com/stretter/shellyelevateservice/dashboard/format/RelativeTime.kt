package com.stretter.shellyelevateservice.dashboard.format

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

// java.util instead of java.time: minSdk is 24 and the app does not enable desugaring.

/** Units of [selectUnit]. */
enum class TimeUnitKind { SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

/**
 * Port of the frontend's `selectUnit` (common/util/select-unit.ts) with its default thresholds
 * (59 s, 59 min, 22 h, 5 days, 4 weeks, 11 months). Negative values are in the past.
 */
fun selectUnit(
    fromMs: Long,
    toMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
    firstDayOfWeek: Int = Calendar.MONDAY,
): Pair<Long, TimeUnitKind> {
    val secs = (fromMs - toMs) / 1000.0
    if (abs(secs) < 59) return secs.roundToLong() to TimeUnitKind.SECOND
    val mins = secs / 60
    if (abs(mins) < 59) return mins.roundToLong() to TimeUnitKind.MINUTE
    val hours = secs / 3600
    if (abs(hours) < 22) return hours.roundToLong() to TimeUnitKind.HOUR

    val from = startOfDay(fromMs, zone)
    val to = startOfDay(toMs, zone)
    val days = dayNumber(from) - dayNumber(to)
    if (days == 0L) return hours.roundToLong() to TimeUnitKind.HOUR
    if (abs(days) < 5) return days to TimeUnitKind.DAY

    val weeks = (dayNumber(startOfWeek(from, firstDayOfWeek)) - dayNumber(startOfWeek(to, firstDayOfWeek))) / 7
    if (weeks == 0L) return days to TimeUnitKind.DAY
    if (abs(weeks) < 4) return weeks to TimeUnitKind.WEEK

    val years = (from.get(Calendar.YEAR) - to.get(Calendar.YEAR)).toLong()
    val months = years * 12 + from.get(Calendar.MONTH) - to.get(Calendar.MONTH)
    if (months == 0L) return weeks to TimeUnitKind.WEEK
    if (abs(months) < 11 || years == 0L) return months to TimeUnitKind.MONTH
    return years to TimeUnitKind.YEAR
}

private fun startOfDay(ms: Long, zone: TimeZone): Calendar = Calendar.getInstance(zone).apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun startOfWeek(day: Calendar, firstDayOfWeek: Int): Calendar = (day.clone() as Calendar).apply {
    val diff = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
    add(Calendar.DAY_OF_MONTH, -diff)
}

/** Calendar day index, independent of DST (local date as if it were UTC). */
private fun dayNumber(c: Calendar): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.clear()
    utc.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    return utc.timeInMillis / 86_400_000L
}

/** ISO 8601 in UTC, as HA expects for `start_time` and friends. */
fun isoUtc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date(ms))

/** Parses ISO 8601 timestamps as HA writes them (`2026-11-12T10:15:00+00:00`, fractional seconds optional). */
fun parseIso(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val m = ISO.matchEntire(value.trim()) ?: return null
    val (date, time, frac, zone) = m.destructured
    val cal = Calendar.getInstance(
        when {
            zone.isEmpty() -> TimeZone.getDefault()
            zone == "Z" -> TimeZone.getTimeZone("UTC")
            else -> TimeZone.getTimeZone("GMT$zone")
        }
    )
    cal.clear()
    val (y, mo, d) = date.split('-').map { it.toInt() }
    cal.set(y, mo - 1, d)
    if (time.isNotEmpty()) {
        val parts = time.split(':').map { it.toInt() }
        cal.set(Calendar.HOUR_OF_DAY, parts[0])
        cal.set(Calendar.MINUTE, parts[1])
        cal.set(Calendar.SECOND, parts.getOrElse(2) { 0 })
    }
    val millis = frac.take(3).padEnd(3, '0').takeIf { frac.isNotEmpty() }?.toInt() ?: 0
    return cal.timeInMillis + millis
}

private val ISO = Regex("""(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2}(?::\d{2})?)(?:\.(\d+))?)?(Z|[+-]\d{2}:?\d{2})?""")
