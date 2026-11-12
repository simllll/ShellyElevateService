package com.stretter.shellyelevateservice.dashboard.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient accessors for Lovelace configs and HA messages. Lovelace YAML is loosely typed
 * (numbers as strings, `null` for "unset"), so every accessor returns null instead of throwing.
 */

val HaJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

val EmptyJsonObject = JsonObject(emptyMap())

fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

/** String content of a primitive; numbers and booleans are stringified, null/objects give null. */
fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() }
fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toLong() }
fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.let {
    it.booleanOrNull ?: when (it.contentOrNull?.lowercase()) {
        "true", "yes", "on" -> true
        "false", "no", "off" -> false
        else -> null
    }
}

fun JsonObject.string(key: String): String? = this[key].asString()
fun JsonObject.double(key: String): Double? = this[key].asDouble()
fun JsonObject.int(key: String): Int? = this[key].asInt()
fun JsonObject.bool(key: String): Boolean? = this[key].asBoolean()
fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()
fun JsonObject.array(key: String): JsonArray? = this[key].asArray()

/** Objects of an array; non-object entries are skipped. */
fun JsonObject.objects(key: String): List<JsonObject> = array(key)?.mapNotNull { it.asObject() } ?: emptyList()

/** A list that may be written as a single string or as an array of strings (e.g. `entity_id`). */
fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.asString() }
    is JsonPrimitive -> asString()?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
}

fun JsonObject.with(key: String, value: JsonElement?): JsonObject =
    JsonObject(if (value == null) this - key else this + (key to value))

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.mapNotNull { (k, v) -> v?.let { k to it.toJsonElement() } }.toMap())

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
