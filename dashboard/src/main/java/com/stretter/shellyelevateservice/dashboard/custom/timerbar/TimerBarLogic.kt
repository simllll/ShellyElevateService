package com.stretter.shellyelevateservice.dashboard.custom.timerbar

import com.stretter.shellyelevateservice.dashboard.custom.jsParseFloat
import com.stretter.shellyelevateservice.dashboard.format.parseIso
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.jsonOf
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Display mode of a timer bar (`Gt` in timer-bar-card 1.31). */
enum class TimerMode { ACTIVE, PAUSE, WAITING, IDLE }

/**
 * Port of timer-bar-card's time logic (rianadon/timer-bar-card 1.31): remaining time, duration,
 * progress, mode, `modifications` and the time `format`. Times are seconds; invalid inputs give
 * null where the card would throw.
 */
class TimerBarLogic(rawConfig: JsonObject, private val states: (String) -> EntityState?) {
    /** Config with the card's defaults (`ye`). */
    val config: JsonObject = JsonObject(DEFAULTS + rawConfig)

    val entityId: String? = config.string("entity")
    private val entity: EntityState? get() = entityId?.let(states)

    /** `Zt`: raw value of a time property (`fixed`, `entity` (+`attribute`), `state` or `attribute`). */
    private fun timeValue(prop: JsonObject?): String? {
        prop ?: return null
        if (prop.containsKey("fixed")) return prop["fixed"].asString()
        if (prop.containsKey("script")) return null
        if (prop.containsKey("entity")) {
            val other = prop.string("entity")?.let(states) ?: return null
            return if (prop.containsKey("attribute")) other.attr(prop.string("attribute") ?: "").asString()
            else other.state.takeIf { it != EntityState.UNAVAILABLE }
        }
        val e = entity ?: return null
        return if (prop.containsKey("state")) e.state.takeIf { it != EntityState.UNAVAILABLE }
        else e.attr(prop.string("attribute") ?: "").asString()
    }

    /** `Jt`: a duration property in seconds (`units` hours/minutes/seconds, else `H:MM:SS`). */
    private fun durationValue(prop: JsonObject?): Double? {
        val v = timeValue(prop)?.takeIf { it.isNotEmpty() } ?: return null
        val factor = when (prop?.string("units")) {
            "hours" -> 3600.0
            "minutes" -> 60.0
            "seconds" -> 1.0
            else -> return parseHms(v)
        }
        return jsParseFloat(v).takeIf { !it.isNaN() }?.times(factor)
    }

    private fun prop(key: String): JsonObject? = config.obj(key)

    /** `start_time` as epoch millis (shown while waiting). */
    fun startTimeMs(): Long? = timeValue(prop("start_time"))?.let(::parseIso)

    /** `qt`: total duration in seconds. */
    fun duration(): Double? {
        durationValue(prop("duration"))?.takeIf { it != 0.0 }?.let { return it }
        val start = timeValue(prop("start_time"))?.let(::parseIso)
        val end = timeValue(prop("end_time"))?.let(::parseIso)
        if (start != null && end != null) return (end - start) / 1000.0
        val e = entity ?: return null
        return end?.let { (it - e.lastChanged) / 1000.0 }
    }

    /** `Wt`: remaining seconds at [nowMs]. */
    fun remaining(nowMs: Long): Double? {
        val e = entity
        if (e != null) e.attr("remaining").asString()?.let { r ->
            var t = parseHms(r) ?: return null
            if (isState(e, config["active_state"])) t = max(t - (nowMs - e.lastChanged) / 1000.0, 0.0)
            return t
        }
        timeValue(prop("end_time"))?.let(::parseIso)?.let { return (it - nowMs) / 1000.0 }
        val start = timeValue(prop("start_time"))?.let(::parseIso)
        val dur = durationValue(prop("duration"))
        if (start != null && dur != null && dur != 0.0) return (start - nowMs) / 1000.0 + dur
        durationValue(prop("remain_time"))?.let { return it }
        e ?: return null
        return if (dur != null && dur != 0.0) (e.lastChanged - nowMs) / 1000.0 + dur else null
    }

    /** `Bt`: elapsed percentage (may exceed 100; the bar clamps it). */
    fun progress(nowMs: Long): Double? {
        val n = remaining(nowMs) ?: return null
        val s = duration() ?: return null
        if (s == 0.0 || n == 0.0) return null
        return (s - floor(n)) / s * 100
    }

    /** `Ft`: the entity (or `state_attribute`) is in one of [states]. */
    private fun isState(e: EntityState?, states: JsonElement?): Boolean {
        e ?: return false
        val value = config.string("state_attribute")?.let { e.attr(it).asString() } ?: e.state
        return when (states) {
            is JsonArray -> states.any { it.asString() == value }
            null -> false
            else -> states.asString() == value
        }
    }

    /** `Gt`: active (with time left), pause, waiting or idle. */
    fun mode(nowMs: Long): TimerMode {
        config.obj("state")?.string("fixed")?.let { fixed ->
            return when (fixed.replace("paused", "pause")) {
                "active" -> TimerMode.ACTIVE
                "pause" -> TimerMode.PAUSE
                "waiting" -> TimerMode.WAITING
                else -> TimerMode.IDLE
            }
        }
        val e = entity
        return when {
            isState(e, config["active_state"]) && (remaining(nowMs) ?: 0.0) > 0 -> TimerMode.ACTIVE
            isState(e, config["pause_state"]) -> TimerMode.PAUSE
            isState(e, config["waiting_state"]) -> TimerMode.WAITING
            else -> TimerMode.IDLE
        }
    }

    /** `modConfig`: [config] with every matching `modifications` entry merged in. */
    fun modConfig(nowMs: Long): JsonObject {
        val mods = config["modifications"].asArray()?.mapNotNull { it.asObject() } ?: return config
        val mode = mode(nowMs)
        val remaining = (if (mode != TimerMode.IDLE) remaining(nowMs) else null) ?: Double.POSITIVE_INFINITY
        val elapsed = (duration() ?: 0.0) - remaining
        val progress = progress(nowMs) ?: 0.0
        val remainingPercent = 100 - progress
        var result = config
        for (m in mods) {
            val rem = m.string("remaining")
            val ela = m.string("elapsed")
            val applies = when {
                rem != null && rem.endsWith("%") -> remainingPercent <= jsParseFloat(rem)
                rem != null -> parseHms(rem)?.let { remaining <= it } == true
                ela != null && ela.endsWith("%") -> progress >= jsParseFloat(ela)
                ela != null -> parseHms(ela)?.let { elapsed >= it } == true
                else -> false
            }
            if (applies) result = JsonObject(result + m)
        }
        return result
    }

    companion object {
        val DEFAULTS: JsonObject = jsonOf(
            "active_state" to listOf("active", "on", "manual", "program", "once_program"),
            "pause_state" to "paused",
            "waiting_state" to "waiting",
            "end_time" to mapOf("attribute" to "end_time"),
            "start_time" to mapOf("attribute" to "start_time"),
            "duration" to mapOf("attribute" to "duration"),
            "remain_time" to mapOf("attribute" to "remain_time"),
            "bar_width" to "calc(70% - 7em)",
            "bar_height" to "8px",
            "text_width" to "3.5em",
            "bar_background" to "#eee",
            "bar_foreground" to "var(--mdc-theme-primary, #6200ee)",
            "bar_radius" to "0",
            "layout" to "normal",
            "resolution" to "seconds",
        )

        /** `Lt`: `H:MM:SS` → seconds; null unless exactly three numeric parts (like `Number()`). */
        fun parseHms(value: String): Double? {
            val parts = value.split(":")
            if (parts.size < 3) return null
            val nums = parts.take(3).map { p -> if (p.isBlank()) 0.0 else p.trim().toDoubleOrNull() ?: return null }
            return 3600 * nums[0] + 60 * nums[1] + nums[2]
        }

        private fun pad(n: Long): String = abs(n).let { if (it < 10) "0$it" else "$it" }

        /** `fe`: `h:mm:ss`, `m:ss` or `s`. */
        private fun hms(h: Long, m: Long, s: Long): String = when {
            h != 0L -> "$h:${pad(m)}:${pad(s)}"
            m != 0L -> "$m:${pad(s)}"
            else -> "$s"
        }

        /** `pe`: seconds → `h:mm:ss`. */
        fun formatHms(t: Double): String = hms((t / 3600).toLong(), (t % 3600 / 60).toLong(), (t % 3600 % 60).toLong())

        /** `ve`: seconds → `h:mm` with minutes rounded up. */
        fun formatHm(t: Double): String {
            var h = (t / 3600).toLong()
            var m = ceil(t % 3600 / 60).toLong()
            if (m == 60L) {
                h += 1
                m = 0
            }
            return hms(0, h, m)
        }

        /** `be`: truncation that keeps the sign of small negatives (`-0`). */
        private fun signedTrunc(t: Double): String {
            val e = t.toLong()
            return if (t < 0 && e == 0L) "-0" else e.toString()
        }

        /** Format name for `resolution` (`seconds` → hms, `minutes` → hm, `automatic`). */
        fun formatForResolution(remaining: Double, resolution: String?): String = when (
            if (resolution == "automatic") (if (remaining >= 3600) "minutes" else "seconds") else resolution
        ) {
            "minutes" -> "hm"
            else -> "hms"
        }

        /** The time text for [t] seconds in [format] (`hms`, `hm`, `d`, `h`, `m`, `s` or `%`-patterns). */
        fun formatTime(t: Double, format: String): String = when (format) {
            "hms" -> formatHms(t)
            "hm" -> formatHm(t)
            "d" -> ceil(t / 24 / 3600).toLong().toString()
            "h" -> ceil(t / 3600).toLong().toString()
            "m" -> ceil(t / 60).toLong().toString()
            "s" -> ceil(t).toLong().toString()
            else -> FORMAT_TOKEN.replace(format) { match ->
                val r = match.groupValues[1]
                val lower = r.lowercase()
                when {
                    lower.startsWith("hms") -> formatHms(t) + r.substring(3)
                    lower.startsWith("hm") -> formatHm(t) + r.substring(2)
                    r.startsWith("d") -> ceil(t / 24 / 3600).toLong().toString() + r.substring(1)
                    r.startsWith("h") -> ceil(t / 3600).toLong().toString() + r.substring(1)
                    r.startsWith("m") -> ceil(t / 60).toLong().toString() + r.substring(1)
                    r.startsWith("s") -> ceil(t).toLong().toString() + r.substring(1)
                    r.startsWith("HH") -> pad((t % 86400 / 3600).toLong()) + r.substring(2)
                    r.startsWith("MM") -> pad((t % 3600 / 60).toLong()) + r.substring(2)
                    r.startsWith("SS") -> pad((t % 60).toLong()) + r.substring(2)
                    r.startsWith("D") -> signedTrunc(t / 24 / 3600) + r.substring(1)
                    r.startsWith("H") -> signedTrunc(t % 86400 / 3600) + r.substring(1)
                    r.startsWith("M") -> signedTrunc(t % 3600 / 60) + r.substring(1)
                    r.startsWith("S") -> signedTrunc(t % 60) + r.substring(1)
                    else -> match.value
                }
            }
        }

        private val FORMAT_TOKEN = Regex("%(\\w+)")

        /** Normalizes `tap_action: none` (a plain string) to an action object. */
        fun normalizeAction(value: JsonElement?): JsonElement? = (value as? JsonPrimitive)?.takeIf { it.isString }
            ?.let { jsonOf("action" to it.content) } ?: value
    }
}
