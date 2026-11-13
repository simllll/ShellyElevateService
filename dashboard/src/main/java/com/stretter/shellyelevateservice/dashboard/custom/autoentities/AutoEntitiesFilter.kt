package com.stretter.shellyelevateservice.dashboard.custom.autoentities

import android.util.Log
import com.stretter.shellyelevateservice.dashboard.custom.JsNull
import com.stretter.shellyelevateservice.dashboard.custom.jsDateMs
import com.stretter.shellyelevateservice.dashboard.custom.jsParseFloat
import com.stretter.shellyelevateservice.dashboard.custom.jsStrictEquals
import com.stretter.shellyelevateservice.dashboard.custom.jsString
import com.stretter.shellyelevateservice.dashboard.custom.jsonStringify
import com.stretter.shellyelevateservice.dashboard.custom.toJs
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import com.stretter.shellyelevateservice.dashboard.hass.RegistryEntry
import com.stretter.shellyelevateservice.dashboard.json.HaJson
import com.stretter.shellyelevateservice.dashboard.json.asArray
import com.stretter.shellyelevateservice.dashboard.json.asObject
import com.stretter.shellyelevateservice.dashboard.json.asString
import com.stretter.shellyelevateservice.dashboard.json.bool
import com.stretter.shellyelevateservice.dashboard.json.int
import com.stretter.shellyelevateservice.dashboard.json.obj
import com.stretter.shellyelevateservice.dashboard.json.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Collator

/**
 * Inputs of one auto-entities evaluation.
 *
 * [registry]: fetched registry entries; an absent key means "not fetched yet" — such ids are
 * collected in [missingRegistry] so the caller can fetch them and evaluate again.
 */
class FilterEnv(
    val states: Map<String, EntityState>,
    val registry: Map<String, RegistryEntry?> = emptyMap(),
    val labelNames: Map<String, String> = emptyMap(),
    val formatState: (EntityState) -> String = { it.state },
    val nowMs: Long = System.currentTimeMillis(),
) {
    val missingRegistry = LinkedHashSet<String>()

    // Translating/formatting a state is the expensive part of a `state:` rule over all states
    private val formatted = HashMap<String, String>()

    fun formattedState(state: EntityState): String = formatted.getOrPut(state.entityId) { formatState(state) }

    fun registryEntry(entityId: String): RegistryEntry? {
        if (entityId !in registry) {
            missingRegistry += entityId
            return null
        }
        return registry[entityId]
    }
}

/**
 * Port of auto-entities' value matcher (`Me` in auto-entities 1.16): a rule value is either a
 * literal (strict equality) or a string with these features, combined with OR:
 * `$$` prefix (match against `JSON.stringify(value)`), `/regex/`, `*` wildcards, `N m|h|d ago`
 * (value is a time, compared as minutes/hours/days since then), numeric comparisons
 * `<=`, `>=`, `==`, `!=`, `<`, `>`, `!`, `=`, and plain string equality.
 */
class ValueMatcher(pattern: JsonElement) {
    private val transforms = ArrayList<(Any?, Long) -> Any?>()
    private val tests = ArrayList<(Any?) -> Boolean>()

    init {
        val p = pattern as? JsonPrimitive
        if (p != null && p.isString) {
            var e = p.content
            if (e.startsWith("$$")) {
                e = e.substring(2)
                transforms += { v, _ -> jsonStringify(v) }
            }
            if ((e.startsWith("/") && e.endsWith("/") && e.length >= 2) || e.contains('*')) {
                if (!e.startsWith("/")) e = "/^" + e.replace("*", ".*") + "$/"
                val regex = runCatching { Regex(e.substring(1, e.length - 1)) }.getOrNull()
                if (regex != null) tests += { v -> v is String && regex.containsMatchIn(v) }
            }
            AGO.find(e)?.let { m ->
                e = e.replace(m.value, "")
                val unit = m.groupValues[1].lowercase()
                transforms += { v, now ->
                    val minutes = jsDateMs(v)?.let { (now - it) / 60_000.0 } ?: Double.NaN
                    when (unit) {
                        "h" -> minutes / 60
                        "d" -> minutes / 60 / 24
                        else -> minutes
                    }
                }
            }
            numericTest(e, "<=") { a, b -> a <= b }
            numericTest(e, ">=") { a, b -> a >= b }
            numericTest(e, "==") { a, b -> a == b }
            numericTest(e, "!=") { a, b -> a != b }
            numericTest(e, "<") { a, b -> a < b }
            numericTest(e, ">") { a, b -> a > b }
            numericTest(e, "!") { a, b -> a != b }
            numericTest(e, "=") { a, b -> a == b }
            val literal = e
            tests += { v -> v == literal }
        } else {
            val literal = pattern.toJs()
            tests += { v -> jsStrictEquals(v, literal) }
        }
    }

    private inline fun numericTest(e: String, prefix: String, crossinline op: (Double, Double) -> Boolean) {
        if (!e.startsWith(prefix)) return
        val ref = jsParseFloat(e.substring(prefix.length))
        tests += { v -> op(jsParseFloat(v), ref) }
    }

    /** True if [value] (a JS value, see `JsValues.kt`) matches. */
    fun matches(value: Any?, nowMs: Long = System.currentTimeMillis()): Boolean {
        var v = value
        for (t in transforms) v = t(v, nowMs)
        return v != null && tests.any { it(v) }
    }

    companion object {
        val AGO = Regex("""([mhd])\s+ago\s*$""", RegexOption.IGNORE_CASE)
    }
}

/** One filter object (`include`/`exclude` entry): all its rules must match. */
class EntityFilter private constructor(private val rules: List<Rule>) {
    /** Whether any rule needs the entity registry. */
    val usesRegistry: Boolean get() = rules.any { it.usesRegistry }
    val usesLabels: Boolean get() = rules.any { it.usesLabels }

    /** `fn(hass, filter)(entry)`: false without rules or without a state for the entity. */
    fun matches(entityId: String?, env: FilterEnv): Boolean {
        if (rules.isEmpty() || entityId.isNullOrEmpty()) return false
        val state = env.states[entityId] ?: return false
        return rules.all { it.test(state, env) }
    }

    fun matches(entry: JsonObject, env: FilterEnv): Boolean = matches(entry.string("entity"), env)

    private abstract class Rule {
        open val usesRegistry = false
        open val usesLabels = false
        /** Evaluation order (all rules must match): cheap rules first, so most states fail early. */
        open val cost = 0
        abstract fun test(state: EntityState, env: FilterEnv): Boolean
    }

    private class Simple(private val value: (EntityState, FilterEnv) -> Any?, pattern: JsonElement) : Rule() {
        private val m = ValueMatcher(pattern)
        override fun test(state: EntityState, env: FilterEnv) = m.matches(value(state, env), env.nowMs)
    }

    private class StateRule(pattern: JsonElement) : Rule() {
        private val m = ValueMatcher(pattern)
        override val cost = 2
        override fun test(state: EntityState, env: FilterEnv) =
            m.matches(state.state, env.nowMs) || m.matches(env.formattedState(state), env.nowMs)
    }

    private class Attributes(spec: JsonObject) : Rule() {
        override val cost = 1
        private val checks = spec.map { (key, pattern) ->
            key.trim().split(" ")[0].split(":") to ValueMatcher(pattern)
        }

        override fun test(state: EntityState, env: FilterEnv) = checks.all { (path, m) ->
            var v: JsonElement? = state.attributes
            for (p in path) v = v.asObject()?.get(p)
            m.matches(v.toJs(), env.nowMs)
        }
    }

    private class Group(private val groupId: String?) : Rule() {
        override val cost = 1
        override fun test(state: EntityState, env: FilterEnv) =
            env.states[groupId]?.attr("entity_id").asArray()?.any { it.asString() == state.entityId } == true
    }

    private class Not(private val filter: EntityFilter) : Rule() {
        override val cost = 3
        override val usesRegistry get() = filter.usesRegistry
        override val usesLabels get() = filter.usesLabels
        override fun test(state: EntityState, env: FilterEnv) = !filter.matches(state.entityId, env)
    }

    private class Combined(private val filters: List<EntityFilter>, private val all: Boolean) : Rule() {
        override val cost = 3
        override val usesRegistry get() = filters.any { it.usesRegistry }
        override val usesLabels get() = filters.any { it.usesLabels }
        override fun test(state: EntityState, env: FilterEnv) =
            if (all) filters.all { it.matches(state.entityId, env) } else filters.any { it.matches(state.entityId, env) }
    }

    private class RegistryRule(pattern: JsonElement, private val value: (RegistryEntry, ValueMatcher, FilterEnv) -> Boolean) : Rule() {
        private val m = ValueMatcher(pattern)
        override val usesRegistry = true
        override val cost = 4
        override fun test(state: EntityState, env: FilterEnv): Boolean {
            val entry = env.registryEntry(state.entityId) ?: return false
            return value(entry, m, env)
        }
    }

    private class Never(private val name: String) : Rule() {
        override fun test(state: EntityState, env: FilterEnv) = false
        override fun toString() = name
    }

    companion object {
        private const val TAG = "AutoEntities"

        private val IGNORED = setOf("type", "options", "sort")

        /** Builds a filter from a rule object (unsupported rules never match, like an unknown rule). */
        fun compile(filter: JsonObject): EntityFilter {
            val rules = ArrayList<Rule>()
            for ((rawKey, value) in filter) {
                val key = rawKey.trim().split(" ")[0].trim()
                if (key in IGNORED) continue
                rules += when (key) {
                    "domain" -> Simple({ s, _ -> s.domain }, value)
                    "entity_id" -> Simple({ s, _ -> s.entityId }, value)
                    "state" -> StateRule(value)
                    "name" -> Simple({ s, _ -> s.attr("friendly_name").toJs() }, value)
                    "group" -> Group(value.asString())
                    "attributes" -> value.asObject()?.let { Attributes(it) } ?: Never(key)
                    "last_changed" -> Simple({ s, _ -> s.lastChanged.toDouble() }, withAgo(value))
                    "last_updated" -> Simple({ s, _ -> s.lastUpdated.toDouble() }, withAgo(value))
                    "last_triggered" -> Simple({ s, _ -> s.attr("last_triggered").toJs() }, withAgo(value))
                    "not" -> value.asObject()?.let { Not(compile(it)) } ?: Never(key)
                    "and" -> Combined(value.asArray().orEmpty().mapNotNull { it.asObject() }.map { compile(it) }, all = true)
                    "or" -> Combined(value.asArray().orEmpty().mapNotNull { it.asObject() }.map { compile(it) }, all = false)
                    "hidden_by" -> RegistryRule(value) { e, m, env -> m.matches(e.hiddenBy ?: JsNull, env.nowMs) }
                    "integration" -> RegistryRule(value) { e, m, env ->
                        m.matches(e.platform ?: JsNull, env.nowMs) || m.matches(e.configEntryId ?: JsNull, env.nowMs)
                    }
                    "entity_category" -> RegistryRule(value) { e, m, env -> m.matches(e.entityCategory ?: JsNull, env.nowMs) }
                    "label" -> object : Rule() {
                        private val m = ValueMatcher(value)
                        override val usesRegistry = true
                        override val usesLabels = true
                        override fun test(state: EntityState, env: FilterEnv): Boolean {
                            val entry = env.registryEntry(state.entityId) ?: return false
                            return entry.labels.any { id -> m.matches(id, env.nowMs) || m.matches(env.labelNames[id], env.nowMs) }
                        }
                    }
                    else -> {
                        // device, area, floor, level, device_manufacturer, device_model: need the
                        // device/area registries, which no card on the dashboard uses yet.
                        Log.w(TAG, "unsupported filter rule \"$key\" (never matches)")
                        Never(key)
                    }
                }
            }
            // Registry rules last: `every` short-circuits, so fewer registry lookups are needed.
            return EntityFilter(rules.sortedBy { it.cost })
        }

        /** Time rules without a unit compare minutes (`"< 5"` → `"< 5 m ago"`). */
        private fun withAgo(value: JsonElement): JsonElement {
            val s = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
            return if (ValueMatcher.AGO.containsMatchIn(s)) value else JsonPrimitive(s + "m ago")
        }
    }
}

/** `sort:` options of auto-entities. */
class SortSpec(config: JsonObject) {
    val method: String? = config.string("method")
    val reverse = config.bool("reverse") == true
    val ignoreCase = config.bool("ignore_case") == true
    val numeric = config.bool("numeric") == true || method in setOf("last_changed", "last_updated", "last_triggered")
    val attribute: String? = config.string("attribute")
    val first: Int? = config.int("first")
    val count: Int? = config.int("count")

    private fun value(state: EntityState?): Any? {
        if (state == null) return if (method == "none") 0.0 else null
        return when (method) {
            "none" -> 0.0
            "domain" -> state.domain
            "entity_id" -> state.entityId
            "friendly_name", "name" -> state.attr("friendly_name").asString()?.takeIf { it.isNotEmpty() } ?: state.objectId
            "state" -> state.state
            "attribute" -> {
                var v: JsonElement? = state.attributes
                attribute?.split(":")?.forEach { v = v.asObject()?.get(it) } ?: return null
                v.toJs()
            }
            "last_changed" -> state.lastChanged.toDouble()
            "last_updated" -> state.lastUpdated.toDouble()
            "last_triggered" -> jsDateMs(state.attr("last_triggered").toJs())?.toDouble()
            else -> null
        }
    }

    /** True for methods auto-entities knows (others leave the order unchanged). */
    val supported: Boolean get() = method in setOf("none", "domain", "entity_id", "friendly_name", "name", "state", "attribute", "last_changed", "last_updated", "last_triggered")

    fun sort(entries: List<JsonObject>, states: Map<String, EntityState>): List<JsonObject> {
        if (!supported) return entries
        return entries
            .map { it to value(it.string("entity")?.let { id -> states[id] }) }
            .sortedWith { a, b -> compare(a.second, b.second) }
            .map { it.first }
    }

    fun slice(entries: List<JsonObject>): List<JsonObject> {
        if (first == null && count == null) return entries
        val from = (first ?: 0).coerceIn(0, entries.size)
        val to = if (count == null) entries.size else (from + count).coerceIn(from, entries.size)
        return entries.subList(from, to)
    }

    /** Port of `pn` (compare): undefined sorts last, numeric or locale string comparison. */
    fun compare(a0: Any?, b0: Any?): Int {
        val lt = if (reverse) 1 else -1
        val gt = -lt
        var a = a0
        var b = b0
        if (ignoreCase) {
            if (a is String) a = a.lowercase()
            if (b is String) b = b.lowercase()
        }
        if (numeric) {
            val fa = jsParseFloat(a)
            val fb = jsParseFloat(b)
            if (!(fa.isNaN() && fb.isNaN())) {
                a = if (fa.isNaN()) null else fa
                b = if (fb.isNaN()) null else fb
            }
        }
        if (a == null && b == null) return 0
        if (a == null) return gt
        if (b == null) return lt
        if (numeric && a is Double && b is Double) return if (a == b) 0 else if (a < b) lt else gt
        return (if (reverse) -1 else 1) * collator.compare(jsString(a), jsString(b)).coerceIn(-1, 1)
    }

    private val collator: Collator by lazy { Collator.getInstance() }
}

/** Parsed auto-entities config (everything except the live inputs). */
class AutoEntitiesSpec(config: JsonObject) {
    val staticEntries: List<JsonObject> = config["entities"].asArray().orEmpty().mapNotNull { normalizeEntry(it) }
    val template: String? = config.obj("filter")?.string("template")
    private val includeRaw: List<JsonObject> = config.obj("filter")?.get("include").asArray().orEmpty().mapNotNull { it.asObject() }
    private val includes: List<Pair<JsonObject, EntityFilter?>> = includeRaw.map { r -> r to if (r.containsKey("type")) null else EntityFilter.compile(r) }
    private val excludes: List<EntityFilter> = config.obj("filter")?.get("exclude").asArray().orEmpty()
        .mapNotNull { it.asObject() }.map { EntityFilter.compile(it) }
    private val sort: SortSpec? = config.obj("sort")?.let { SortSpec(it) }?.takeIf { it.method != null || it.first != null || it.count != null }
    private val unique: String? = config["unique"]?.let { u -> u.asString()?.takeIf { it != "false" } }
    val showEmpty: Boolean? = config.bool("show_empty")
    val elseCard: JsonObject? = config.obj("else")
    private val card: JsonObject? = config.obj("card")
    private val cardParam: String = config.string("card_param") ?: "entities"

    init {
        require(config.containsKey("filter") || config.containsKey("entities")) { "No filters specified." }
    }

    /** Include rules are evaluated against every state: the card must watch all entities. */
    val needsAllStates: Boolean get() = includes.any { it.second != null }
    val usesRegistry: Boolean get() = (includes.mapNotNull { it.second } + excludes).any { it.usesRegistry }
    val usesLabels: Boolean get() = (includes.mapNotNull { it.second } + excludes).any { it.usesLabels }

    /**
     * Port of `update_entities`: static entries + template entries + include matches, minus
     * excludes, sorted, made unique and sliced.
     */
    // The sorted entity ids only change when entities are added or removed, not on state changes
    @Volatile private var sortedIdsCache: Pair<Set<String>, List<String>>? = null

    private fun sortedIds(states: Map<String, *>): List<String> {
        val cached = sortedIdsCache
        if (cached != null && cached.first.size == states.size && cached.first == states.keys) return cached.second
        val keys = states.keys.toHashSet()
        return keys.sorted().also { sortedIdsCache = keys to it }
    }

    fun resolve(templateEntries: List<JsonObject>, env: FilterEnv): List<JsonObject> {
        var list = staticEntries + templateEntries
        if (includes.isNotEmpty()) {
            val all by lazy(LazyThreadSafetyMode.NONE) { sortedIds(env.states) }
            for ((rule, filter) in includes) {
                if (filter == null) {
                    list = list + rule
                    continue
                }
                var matched = all.filter { filter.matches(it, env) }.map { JsonObject(mapOf("entity" to JsonPrimitive(it))) }
                rule.obj("sort")?.let { SortSpec(it) }?.let { s -> matched = s.slice(s.sort(matched, env.states)) }
                val options = rule.obj("options")
                list = list + matched.map { e -> if (options == null) e else applyOptions(e, options) }
            }
        }
        if (excludes.isNotEmpty()) list = list.filter { e -> excludes.none { it.matches(e, env) } }
        sort?.let { s -> list = s.sort(list, env.states) }
        when (unique) {
            null -> Unit
            "entity" -> list = list.distinctBy { it["entity"] ?: it }
            else -> list = list.distinct()
        }
        sort?.let { list = it.slice(list) }
        return list
    }

    /** Whether auto-entities considers the result empty (only `section`/`divider` rows). */
    fun isEmpty(entries: List<JsonObject>): Boolean = entries.all { it.string("type") in EMPTY_TYPES }

    /** Config of the wrapped card: `{type: entities, entities: [...], ...card}`. */
    fun cardConfig(entries: List<JsonObject>): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("entities"), cardParam to JsonArray(entries)) + (card ?: emptyMap()))

    /** The card to show: the else card when empty (and `show_empty: false` or an `else`), else the wrapped card. */
    fun displayedCard(entries: List<JsonObject>): JsonObject? =
        if (isEmpty(entries) && (showEmpty == false || elseCard != null)) elseCard else cardConfig(entries)

    companion object {
        private val EMPTY_TYPES = setOf("section", "divider")

        /** `"light.x"` → `{entity: "light.x"}`; objects pass; anything else is dropped. */
        fun normalizeEntry(e: JsonElement?): JsonObject? = e.asObject() ?: e.asString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            JsonObject(mapOf("entity" to JsonPrimitive(it)))
        }

        /**
         * Entries of a `filter.template` result: a list (of ids or entry objects), or a string of
         * ids separated by whitespace/commas (`_renderer`).
         */
        fun parseTemplateResult(result: JsonElement?): List<JsonObject> = when {
            result == null -> emptyList()
            result is JsonPrimitive && result.isString -> {
                val text = result.content.trim()
                // A string that is itself a JSON list (HA returns non-native results as text).
                if (text.startsWith("[")) runCatching { parseTemplateResult(HaJson.parseToJsonElement(text).asArray()) }.getOrNull()
                    ?: text.split(Regex("[\\s,]+")).mapNotNull { normalizeEntry(JsonPrimitive(it)) }
                else text.split(Regex("[\\s,]+")).mapNotNull { normalizeEntry(JsonPrimitive(it)) }
            }
            else -> result.asArray()?.mapNotNull { normalizeEntry(it) } ?: emptyList()
        }

        /** Include `options` merged into the entry, with `this.entity_id` replaced (`Wn`). */
        fun applyOptions(entry: JsonObject, options: JsonObject): JsonObject {
            val entityId = entry.string("entity") ?: return JsonObject(entry + options)
            val merged = JsonObject(entry + options)
            val text = merged.toString().replace(THIS_ENTITY_ID, entityId)
            return runCatching { HaJson.parseToJsonElement(text).asObject() }.getOrNull() ?: merged
        }

        private val THIS_ENTITY_ID = Regex("this.entity_id")
    }
}
