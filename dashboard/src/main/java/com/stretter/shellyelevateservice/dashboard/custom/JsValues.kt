package com.stretter.shellyelevateservice.dashboard.custom

import com.stretter.shellyelevateservice.dashboard.format.isoUtc
import com.stretter.shellyelevateservice.dashboard.format.parseIso
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * JavaScript value semantics shared by the ports of custom cards (auto-entities matchers,
 * button-card templates). A JS value is represented as:
 * `null` = undefined, [JsNull] = null, [String], [Double], [Boolean], or a [JsonElement]
 * (object / array).
 */

/** JavaScript `null` (Kotlin `null` stands for `undefined`). */
object JsNull {
    override fun toString() = "null"
}

/** JSON → JS value. */
fun JsonElement?.toJs(): Any? = when (this) {
    null -> null
    is JsonNull -> JsNull
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: doubleOrNull ?: content
    else -> this
}

/** An entity state as the frontend's `HassEntity` object (`entity_id`, `state`, `attributes`, …). */
fun EntityState.toJsObject(): JsonObject = JsonObject(
    mapOf(
        "entity_id" to JsonPrimitive(entityId),
        "state" to JsonPrimitive(state),
        "attributes" to attributes,
        "last_changed" to JsonPrimitive(isoUtc(lastChanged)),
        "last_updated" to JsonPrimitive(isoUtc(lastUpdated)),
    )
)

private val JS_FLOAT = Regex("""^[+-]?(Infinity|\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?)""")

/** `parseFloat(v)`: leading numeric prefix of `String(v)`, NaN otherwise. */
fun jsParseFloat(v: Any?): Double = when (v) {
    is Double -> v
    is Number -> v.toDouble()
    is String -> {
        val m = JS_FLOAT.find(v.trimStart())
        when {
            m == null -> Double.NaN
            m.groupValues[1] == "Infinity" -> if (m.value.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            else -> m.value.toDoubleOrNull() ?: Double.NaN
        }
    }
    is JsonArray -> if (v.size == 1) jsParseFloat(v[0].toJs()) else Double.NaN
    else -> Double.NaN
}

/** `String(v)`. */
fun jsString(v: Any?): String = when (v) {
    null -> "undefined"
    is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e21) v.toLong().toString() else v.toString()
    is JsonArray -> v.joinToString(",") { e -> e.toJs().let { if (it == null || it == JsNull) "" else jsString(it) } }
    is JsonObject -> "[object Object]"
    else -> v.toString()
}

/** `JSON.stringify(v)`. */
fun jsonStringify(v: Any?): String? = when (v) {
    null -> null
    JsNull -> "null"
    is String -> JsonPrimitive(v).toString()
    is Double -> jsString(v)
    is Boolean -> v.toString()
    is JsonElement -> v.toString()
    else -> JsonPrimitive(v.toString()).toString()
}

/** JS truthiness. */
fun jsTruthy(v: Any?): Boolean = when (v) {
    null, JsNull -> false
    is Boolean -> v
    is Double -> v != 0.0 && !v.isNaN()
    is String -> v.isNotEmpty()
    else -> true
}

/** `===`. */
fun jsStrictEquals(a: Any?, b: Any?): Boolean = when {
    a is Double && b is Double -> a == b && !a.isNaN()
    a is JsonElement || b is JsonElement -> a === b
    else -> a == b
}

/** `new Date(v).getTime()` for ISO strings and epoch millis; null if invalid. */
fun jsDateMs(v: Any?): Long? = when (v) {
    is Double -> v.toLong()
    is String -> parseIso(v)
    else -> null
}
