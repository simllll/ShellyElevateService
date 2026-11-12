package com.stretter.shellyelevateservice.dashboard.custom.buttoncard

import com.stretter.shellyelevateservice.dashboard.custom.JsNull
import com.stretter.shellyelevateservice.dashboard.custom.jsParseFloat
import com.stretter.shellyelevateservice.dashboard.custom.jsStrictEquals
import com.stretter.shellyelevateservice.dashboard.custom.jsString
import com.stretter.shellyelevateservice.dashboard.custom.jsTruthy
import com.stretter.shellyelevateservice.dashboard.custom.toJs
import com.stretter.shellyelevateservice.dashboard.custom.toJsObject
import com.stretter.shellyelevateservice.dashboard.hass.EntityState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * A deliberately tiny evaluator for button-card JavaScript templates (`[[[ … ]]]`).
 *
 * Decision: no JS engine. The dashboard's five templates are all
 * ``return `<ha-icon icon="mdi:…" style="…"></ha-icon><span>Text</span>` `` — a constant template
 * literal. Embedding Rhino/QuickJS for that would add megabytes and startup cost on a 2 GB device.
 * This evaluator covers that and the common button-card idioms around it, so small edits keep
 * working: `return` + one expression with string/number/template literals (with `${…}`),
 * `entity`/`states[…]`/`variables`/`user` property paths, `+ - * /`, comparisons, `&& || !`,
 * the ternary operator and a few methods (`toUpperCase`, `toLowerCase`, `trim`, `toFixed`,
 * `includes`, `startsWith`, `endsWith`, `toString`, `parseFloat`, `Number`, `String`).
 * Anything else throws [JsUnsupported]; the card then shows the template as missing.
 */
object ButtonCardJs {
    private val TEMPLATE = Regex("^\\[\\[\\[(.*)]]]$", RegexOption.DOT_MATCHES_ALL)

    /** The JS code of a `[[[ … ]]]` template, or null for a plain value. */
    fun templateCode(value: String?): String? = value?.trim()?.let { TEMPLATE.matchEntire(it)?.groupValues?.get(1) }

    /** Evaluates [code]; throws [JsUnsupported] for constructs outside the supported subset. */
    fun evaluate(code: String, scope: JsScope): Any? = Parser(tokens(code), scope).program()

    private val tokenCache = object : LinkedHashMap<String, List<Tok>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Tok>>?) = size > 64
    }

    /** Tokens of [code], cached: templates are re-evaluated on every state change. */
    private fun tokens(code: String): List<Tok> = synchronized(tokenCache) { tokenCache.getOrPut(code) { tokenize(code) } }

    /** Entity ids referenced as `states['…']` / `states["…"]` / `states.x.y` (to watch them). */
    fun referencedEntities(code: String): Set<String> =
        STATES_REF.findAll(code).mapNotNull { m -> m.groups[1]?.value ?: m.groups[2]?.value }.toSet()

    private val STATES_REF = Regex("""states\[\s*['"]([a-z_]+\.[a-zA-Z0-9_]+)['"]\s*]|states\.([a-z_]+\.[a-zA-Z0-9_]+)""")

    /** Like [evaluate] but returns the result as display text (`""` for undefined/null). */
    fun evaluateToString(code: String, scope: JsScope): String = evaluate(code, scope).let {
        if (it == null || it == JsNull) "" else jsString(it)
    }
}

/** Variables visible to templates (`entity`, `states`, `user`, `variables`, `hass`). */
class JsScope(
    val entity: EntityState?,
    val states: (String) -> EntityState?,
    val variables: JsonObject = JsonObject(emptyMap()),
    val userName: String? = null,
)

class JsUnsupported(message: String) : Exception(message)

private sealed interface Tok {
    data class Num(val v: Double) : Tok
    data class Str(val v: String) : Tok
    /** Template literal: alternating string parts and `${}` expression sources. */
    data class Tpl(val parts: List<String>, val exprs: List<String>) : Tok
    data class Id(val v: String) : Tok
    data class Op(val v: String) : Tok
}

private val OPERATORS = listOf("===", "!==", "==", "!=", "<=", ">=", "&&", "||", "?.", "(", ")", "[", "]", ".", "?", ":", "+", "-", "*", "/", "%", "<", ">", "!", ",", ";", "{", "}")

private fun tokenize(src: String): List<Tok> {
    val out = ArrayList<Tok>()
    var i = 0
    while (i < src.length) {
        val ch = src[i]
        when {
            ch.isWhitespace() -> i++
            src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
            src.startsWith("/*", i) -> i = (src.indexOf("*/", i + 2).takeIf { it >= 0 } ?: throw JsUnsupported("unterminated comment")) + 2
            ch.isDigit() || (ch == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E')) i++
                out += Tok.Num(src.substring(start, i).toDoubleOrNull() ?: throw JsUnsupported("number"))
            }
            ch == '"' || ch == '\'' -> {
                val sb = StringBuilder()
                i++
                while (i < src.length && src[i] != ch) {
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(escape(src[i + 1]))
                        i += 2
                    } else sb.append(src[i++])
                }
                if (i >= src.length) throw JsUnsupported("unterminated string")
                i++
                out += Tok.Str(sb.toString())
            }
            ch == '`' -> {
                val parts = ArrayList<String>()
                val exprs = ArrayList<String>()
                val sb = StringBuilder()
                i++
                while (true) {
                    if (i >= src.length) throw JsUnsupported("unterminated template literal")
                    val c = src[i]
                    when {
                        c == '`' -> { i++; break }
                        c == '\\' && i + 1 < src.length -> { sb.append(escape(src[i + 1])); i += 2 }
                        c == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
                            parts += sb.toString()
                            sb.clear()
                            val end = matchingBrace(src, i + 1)
                            exprs += src.substring(i + 2, end)
                            i = end + 1
                        }
                        else -> { sb.append(c); i++ }
                    }
                }
                parts += sb.toString()
                out += Tok.Tpl(parts, exprs)
            }
            ch.isLetter() || ch == '_' || ch == '$' -> {
                val start = i
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                out += Tok.Id(src.substring(start, i))
            }
            else -> {
                val op = OPERATORS.firstOrNull { src.startsWith(it, i) } ?: throw JsUnsupported("unexpected '$ch'")
                out += Tok.Op(op)
                i += op.length
            }
        }
    }
    return out
}

private fun escape(c: Char): Char = when (c) {
    'n' -> '\n'
    't' -> '\t'
    'r' -> '\r'
    else -> c
}

/** Index of the `}` closing the `{` at [open], skipping nested braces and string literals. */
private fun matchingBrace(src: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < src.length) {
        when (val c = src[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return i
            '"', '\'', '`' -> {
                i++
                while (i < src.length && src[i] != c) i += if (src[i] == '\\') 2 else 1
            }
        }
        i++
    }
    throw JsUnsupported("unterminated \${")
}

/** Entity state wrapper so `entity.state` etc. work without converting the whole state eagerly. */
private class JsStates(val lookup: (String) -> EntityState?)

private class Parser(private val toks: List<Tok>, private val scope: JsScope) {
    private var pos = 0

    /** > 0 while parsing a branch that is not taken (no evaluation errors there). */
    private var skip = 0

    private inline fun <T> skipped(block: () -> T): T {
        skip++
        try {
            return block()
        } finally {
            skip--
        }
    }

    private fun peek(): Tok? = toks.getOrNull(pos)
    private fun isOp(v: String) = (peek() as? Tok.Op)?.v == v
    private fun accept(v: String): Boolean = if (isOp(v)) { pos++; true } else false
    private fun expect(v: String) {
        if (!accept(v)) throw JsUnsupported("expected '$v'")
    }

    /** `[return] expr [;]` – a single statement. */
    fun program(): Any? {
        if ((peek() as? Tok.Id)?.v == "return") pos++
        val v = expr()
        accept(";")
        if (pos != toks.size) throw JsUnsupported("only a single return statement is supported")
        return v
    }

    private fun expr(): Any? = ternary()

    private fun ternary(): Any? {
        val cond = or()
        if (!accept("?")) return cond
        return if (jsTruthy(cond)) {
            expr().also { expect(":"); skipped { expr() } }
        } else {
            skipped { expr() }
            expect(":")
            expr()
        }
    }

    private fun or(): Any? {
        var l = and()
        while (accept("||")) {
            l = if (jsTruthy(l)) l.also { skipped { and() } } else and()
        }
        return l
    }

    private fun and(): Any? {
        var l = equality()
        while (accept("&&")) {
            l = if (jsTruthy(l)) equality() else l.also { skipped { equality() } }
        }
        return l
    }

    private fun equality(): Any? {
        var l = relational()
        while (true) {
            l = when {
                accept("===") -> jsStrictEquals(l, relational())
                accept("!==") -> !jsStrictEquals(l, relational())
                accept("==") -> looseEquals(l, relational())
                accept("!=") -> !looseEquals(l, relational())
                else -> return l
            }
        }
    }

    private fun relational(): Any? {
        var l = additive()
        while (true) {
            val op = listOf("<=", ">=", "<", ">").firstOrNull { isOp(it) } ?: return l
            pos++
            val r = additive()
            l = if (l is String && r is String) {
                val c = l.compareTo(r)
                when (op) { "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
            } else {
                val a = toNumber(l)
                val b = toNumber(r)
                when (op) { "<" -> a < b; ">" -> a > b; "<=" -> a <= b; else -> a >= b }
            }
        }
    }

    private fun additive(): Any? {
        var l = multiplicative()
        while (true) {
            l = when {
                accept("+") -> plus(l, multiplicative())
                accept("-") -> toNumber(l) - toNumber(multiplicative())
                else -> return l
            }
        }
    }

    private fun multiplicative(): Any? {
        var l = unary()
        while (true) {
            l = when {
                accept("*") -> toNumber(l) * toNumber(unary())
                accept("/") -> toNumber(l) / toNumber(unary())
                accept("%") -> toNumber(l) % toNumber(unary())
                else -> return l
            }
        }
    }

    private fun unary(): Any? = when {
        accept("!") -> !jsTruthy(unary())
        accept("-") -> -toNumber(unary())
        accept("+") -> toNumber(unary())
        else -> postfix()
    }

    private fun postfix(): Any? {
        var receiver: Any? = null
        var v = primary()
        while (true) {
            when {
                accept(".") || accept("?.") -> {
                    val name = (toks.getOrNull(pos++) as? Tok.Id)?.v ?: throw JsUnsupported("property name")
                    if (isOp("(")) {
                        receiver = v
                        v = MethodRef(receiver, name)
                    } else {
                        v = member(v, name)
                    }
                }
                accept("[") -> {
                    val key = expr()
                    expect("]")
                    v = member(v, if (key is Double) jsString(key) else key?.toString() ?: "undefined")
                }
                accept("(") -> {
                    val args = ArrayList<Any?>()
                    if (!isOp(")")) {
                        do args += expr() while (accept(","))
                    }
                    expect(")")
                    v = call(v, args)
                }
                else -> return v
            }
        }
    }

    private class MethodRef(val receiver: Any?, val name: String)
    private class GlobalFn(val name: String)

    private fun primary(): Any? = when (val t = toks.getOrNull(pos++) ?: throw JsUnsupported("unexpected end")) {
        is Tok.Num -> t.v
        is Tok.Str -> t.v
        is Tok.Tpl -> buildString {
            t.parts.forEachIndexed { i, part ->
                append(part)
                t.exprs.getOrNull(i)?.let { src ->
                    val v = Parser(tokenize(src), scope).let { p ->
                        p.skip = skip
                        p.expr().also { if (p.pos != p.toks.size) throw JsUnsupported("\${…}") }
                    }
                    append(jsString(v))
                }
            }
        }
        is Tok.Id -> when (t.v) {
            "true" -> true
            "false" -> false
            "null" -> JsNull
            "undefined" -> null
            "entity" -> scope.entity?.toJsObject()
            "states" -> JsStates(scope.states)
            "variables" -> scope.variables
            "user" -> JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(scope.userName ?: "")))
            "hass" -> HassRef
            "parseFloat", "Number", "String", "parseInt" -> GlobalFn(t.v)
            else -> throw JsUnsupported("unknown identifier '${t.v}'")
        }
        is Tok.Op -> if (t.v == "(") expr().also { expect(")") } else throw JsUnsupported("unexpected '${t.v}'")
    }

    private object HassRef

    private fun member(v: Any?, name: String): Any? = if (skip > 0 && (v == null || v == JsNull)) null else when (v) {
        null, JsNull -> throw JsUnsupported("cannot read '$name' of ${jsString(v)}")
        is JsStates -> v.lookup(name)?.toJsObject()
        HassRef -> if (name == "states") JsStates(scope.states) else null
        is JsonObject -> v[name].toJs()
        is JsonArray -> if (name == "length") v.size.toDouble() else name.toIntOrNull()?.let { v.getOrNull(it).toJs() }
        is String -> if (name == "length") v.length.toDouble() else name.toIntOrNull()?.let { v.getOrNull(it)?.toString() }
        else -> null
    }

    private fun call(fn: Any?, args: List<Any?>): Any? {
        if (skip > 0 && fn !is GlobalFn && fn !is MethodRef) return null
        if (fn is GlobalFn) {
            val a = args.firstOrNull()
            return when (fn.name) {
                "parseFloat" -> jsParseFloat(a)
                "parseInt" -> jsParseFloat(a).let { if (it.isNaN()) it else it.toLong().toDouble() }
                "Number" -> toNumber(a)
                else -> if (a == null && args.isEmpty()) "" else jsString(a)
            }
        }
        val m = fn as? MethodRef ?: throw JsUnsupported("not a function")
        val r = m.receiver
        if (skip > 0) return null
        return when (m.name) {
            "toString" -> jsString(r)
            "toUpperCase" -> (r as? String)?.uppercase() ?: throw JsUnsupported("toUpperCase")
            "toLowerCase" -> (r as? String)?.lowercase() ?: throw JsUnsupported("toLowerCase")
            "trim" -> (r as? String)?.trim() ?: throw JsUnsupported("trim")
            "toFixed" -> String.format(Locale.US, "%.${(args.firstOrNull() as? Double)?.toInt() ?: 0}f", toNumber(r))
            "includes" -> when (r) {
                is String -> r.contains(jsString(args.firstOrNull()))
                is JsonArray -> r.any { jsStrictEquals(it.toJs(), args.firstOrNull()) }
                else -> throw JsUnsupported("includes")
            }
            "startsWith" -> (r as? String)?.startsWith(jsString(args.firstOrNull())) ?: throw JsUnsupported("startsWith")
            "endsWith" -> (r as? String)?.endsWith(jsString(args.firstOrNull())) ?: throw JsUnsupported("endsWith")
            else -> throw JsUnsupported("method ${m.name}")
        }
    }

    private fun toNumber(v: Any?): Double = when (v) {
        null -> Double.NaN
        JsNull -> 0.0
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is String -> if (v.isBlank()) 0.0 else v.trim().toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    private fun plus(a: Any?, b: Any?): Any? =
        if (a is String || b is String || a is JsonElement || b is JsonElement) jsString(a) + jsString(b) else toNumber(a) + toNumber(b)

    private fun looseEquals(a: Any?, b: Any?): Boolean = when {
        (a == null || a == JsNull) && (b == null || b == JsNull) -> true
        a == null || a == JsNull || b == null || b == JsNull -> false
        a is String && b is String -> a == b
        a is Double || b is Double || a is Boolean || b is Boolean -> toNumber(a) == toNumber(b)
        else -> jsStrictEquals(a, b)
    }
}
