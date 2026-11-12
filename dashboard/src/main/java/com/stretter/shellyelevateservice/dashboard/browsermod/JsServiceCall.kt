package com.stretter.shellyelevateservice.dashboard.browsermod

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * `browser_mod.javascript` cannot run JavaScript natively. The one pattern in use is a heartbeat
 * that calls a service through the frontend's connection:
 *
 * ```js
 * document.querySelector('home-assistant').hass.callService('input_datetime', 'set_datetime',
 *     {entity_id: 'input_datetime.x', timestamp: Math.floor(Date.now()/1000)})
 * ```
 *
 * [parse] recognises exactly a single `hass.callService(domain, service, data?, target?)`
 * statement (also the bare `hass.callService(…)` form browser_mod offers as a parameter) whose
 * arguments are JS literals: strings, numbers, booleans, null, objects with bare or quoted keys,
 * arrays and arithmetic on `Date.now()`, `new Date().getTime()`, `Math.floor/round/ceil(…)`.
 * Anything else returns null and is ignored.
 */
object JsServiceCall {
    data class ServiceCall(val domain: String, val service: String, val data: JsonObject?, val target: JsonObject?)

    private val PREFIX = Regex(
        """^(?:document\s*\.\s*querySelector\(\s*(['"])home-assistant\1\s*\)\s*\.\s*)?hass\s*\.\s*callService\s*\("""
    )

    fun parse(code: String, nowMs: Long = System.currentTimeMillis()): ServiceCall? {
        val trimmed = code.trim().trimEnd(';').trim()
        val match = PREFIX.find(trimmed) ?: return null
        return try {
            val parser = Parser(trimmed, match.range.last + 1, nowMs)
            val args = parser.arguments()
            parser.skipWs()
            if (!parser.atEnd()) return null
            val domain = (args.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val service = (args.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            ServiceCall(domain, service, args.getOrNull(2) as? JsonObject, args.getOrNull(3) as? JsonObject)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Recursive-descent parser for the argument list; values are JSON, numbers stay doubles until emitted. */
    private class Parser(private val s: String, private var pos: Int, private val nowMs: Long) {
        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipWs()
            return s.getOrNull(pos)
        }

        private fun expect(c: Char) {
            require(peek() == c) { "expected $c at $pos" }
            pos++
        }

        private fun consume(word: String): Boolean {
            skipWs()
            if (!s.startsWith(word, pos)) return false
            pos += word.length
            return true
        }

        /** After `callService(`: comma separated values up to the closing parenthesis. */
        fun arguments(): List<JsonElement> {
            val args = mutableListOf<JsonElement>()
            if (peek() == ')') {
                pos++
                return args
            }
            while (true) {
                args += toJson(expression())
                when (peek()) {
                    ',' -> pos++
                    ')' -> { pos++; return args }
                    else -> throw IllegalArgumentException("bad argument list at $pos")
                }
            }
        }

        private fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Double -> if (v == floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
            is JsonElement -> v
            else -> throw IllegalArgumentException("unsupported value")
        }

        // expression := term (('+' | '-') term)*
        private fun expression(): Any? {
            var left = term()
            while (true) {
                left = when (peek()) {
                    '+' -> { pos++; val r = term(); if (left is Double && r is Double) left + r else "${str(left)}${str(r)}" }
                    '-' -> { pos++; num(left) - num(term()) }
                    else -> return left
                }
            }
        }

        // term := factor (('*' | '/' | '%') factor)*
        private fun term(): Any? {
            var left = factor()
            while (true) {
                left = when (peek()) {
                    '*' -> { pos++; num(left) * num(factor()) }
                    '/' -> { pos++; num(left) / num(factor()) }
                    '%' -> { pos++; num(left) % num(factor()) }
                    else -> return left
                }
            }
        }

        private fun factor(): Any? {
            val c = peek() ?: throw IllegalArgumentException("unexpected end")
            return when {
                c == '\'' || c == '"' || c == '`' -> string(c)
                c == '{' -> obj()
                c == '[' -> array()
                c == '(' -> { pos++; val v = expression(); expect(')'); v }
                c == '-' -> { pos++; -num(factor()) }
                c.isDigit() || c == '.' -> number()
                consume("true") -> true
                consume("false") -> false
                consume("null") || consume("undefined") -> null
                consume("Date.now()") -> nowMs.toDouble()
                consume("new Date().getTime()") || consume("new Date().valueOf()") -> nowMs.toDouble()
                consume("Math.floor(") -> floor(num(expression())).also { expect(')') }
                consume("Math.round(") -> num(expression()).roundToLong().toDouble().also { expect(')') }
                consume("Math.ceil(") -> ceil(num(expression())).also { expect(')') }
                else -> throw IllegalArgumentException("unsupported token at $pos")
            }
        }

        private fun num(v: Any?): Double = v as? Double ?: throw IllegalArgumentException("not a number")

        private fun str(v: Any?): String = when (v) {
            is Double -> if (v == floor(v)) v.toLong().toString() else v.toString()
            null -> "null"
            else -> v.toString()
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' ||
                    ((s[pos] == '+' || s[pos] == '-') && pos > start && (s[pos - 1] == 'e' || s[pos - 1] == 'E')))
            ) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun string(quote: Char): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when {
                    c == quote -> return sb.toString()
                    c == '\\' && pos < s.length -> {
                        val e = s[pos++]
                        sb.append(
                            when (e) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                'u' -> s.substring(pos, pos + 4).toInt(16).toChar().also { pos += 4 }
                                else -> e
                            }
                        )
                    }
                    quote == '`' && c == '$' && s.getOrNull(pos) == '{' -> throw IllegalArgumentException("template literal")
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun obj(): JsonObject {
            expect('{')
            val map = LinkedHashMap<String, JsonElement>()
            while (true) {
                val c = peek() ?: throw IllegalArgumentException("unterminated object")
                if (c == '}') { pos++; return JsonObject(map) }
                val key = if (c == '\'' || c == '"') string(c) else identifier()
                expect(':')
                map[key] = toJson(expression())
                when (peek()) {
                    ',' -> pos++
                    '}' -> Unit
                    else -> throw IllegalArgumentException("bad object at $pos")
                }
            }
        }

        private fun array(): JsonArray {
            expect('[')
            val items = mutableListOf<JsonElement>()
            while (true) {
                if (peek() == ']') { pos++; return JsonArray(items) }
                items += toJson(expression())
                when (peek()) {
                    ',' -> pos++
                    ']' -> Unit
                    else -> throw IllegalArgumentException("bad array at $pos")
                }
            }
        }

        private fun identifier(): String {
            skipWs()
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '$')) pos++
            require(pos > start) { "expected key at $start" }
            return s.substring(start, pos)
        }
    }
}
