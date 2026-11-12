package com.stretter.shellyelevateservice.dashboard.html

/**
 * CSS value helpers for the HTML renderer and card options (`width_desktop: 70%`,
 * `margin_top_desktop: calc(100vh - 400px)`). Lengths resolve to CSS px (= dp on the dashboard).
 */
object Css {
    /** What relative units refer to. [percentBase] is the reference for `%` (null: `%` unsupported). */
    data class Context(
        val fontSize: Float = 16f,
        val rootFontSize: Float = 16f,
        val viewportWidth: Float = 0f,
        val viewportHeight: Float = 0f,
        val percentBase: Float? = null,
    )

    /**
     * A length in px: `12px`, `1.5em`, `2rem`, `50%`, `10vh`, `0`, unitless numbers (as px) and
     * `calc()` with `+ - * /`. Null if unparsable (e.g. `auto`).
     */
    fun length(value: String?, ctx: Context = Context()): Float? {
        val v = value?.trim()?.lowercase()?.removeSuffix("!important")?.trim() ?: return null
        if (v.isEmpty()) return null
        return try {
            val p = CalcParser(v, ctx)
            val result = p.expression()
            if (!p.atEnd()) null else result
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Box shorthand (`padding`/`margin`) with 1–4 values → top, right, bottom, left. */
    fun box(value: String?, ctx: Context = Context()): FloatArray? {
        val parts = splitValues(value ?: return null)
        val n = parts.map { length(it, ctx) ?: if (it == "auto") 0f else return null }
        return when (n.size) {
            1 -> floatArrayOf(n[0], n[0], n[0], n[0])
            2 -> floatArrayOf(n[0], n[1], n[0], n[1])
            3 -> floatArrayOf(n[0], n[1], n[2], n[1])
            4 -> floatArrayOf(n[0], n[1], n[2], n[3])
            else -> null
        }
    }

    /** `bold`/`normal`/`600`… → numeric weight. */
    fun fontWeight(value: String?): Int? = when (val v = value?.trim()?.lowercase()) {
        null, "" -> null
        "normal" -> 400
        "bold" -> 700
        "bolder" -> 700
        "lighter" -> 300
        else -> v.toIntOrNull()
    }

    /** `font-size` keywords and lengths relative to the parent [ctx] font size. */
    fun fontSize(value: String?, ctx: Context): Float? = when (value?.trim()?.lowercase()) {
        null, "" -> null
        "xx-small" -> 9f
        "x-small" -> 10f
        "small" -> 13f
        "medium" -> 16f
        "large" -> 18f
        "x-large" -> 24f
        "xx-large" -> 32f
        "smaller" -> ctx.fontSize * 0.83f
        "larger" -> ctx.fontSize * 1.2f
        else -> length(value, ctx.copy(percentBase = ctx.fontSize))
    }

    /** Splits a value on top-level whitespace (keeps `calc(a + b)` together). */
    fun splitValues(value: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in value.trim()) {
            when {
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                c.isWhitespace() && depth == 0 -> if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private class CalcParser(private val s: String, private val ctx: Context) {
        private var pos = 0

        fun atEnd(): Boolean {
            skipWs()
            return pos >= s.length
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun expression(): Float {
            var left = term()
            while (true) {
                skipWs()
                if (pos >= s.length) return left
                left = when (s[pos]) {
                    '+' -> { pos++; left + term() }
                    '-' -> { pos++; left - term() }
                    else -> return left
                }
            }
        }

        private fun term(): Float {
            var left = factor()
            while (true) {
                skipWs()
                if (pos >= s.length) return left
                left = when (s[pos]) {
                    '*' -> { pos++; left * factor() }
                    '/' -> { pos++; left / factor() }
                    else -> return left
                }
            }
        }

        private fun factor(): Float {
            skipWs()
            require(pos < s.length) { "unexpected end" }
            if (s.startsWith("calc(", pos)) {
                pos += 5
                return expression().also { expectClose() }
            }
            if (s[pos] == '(') {
                pos++
                return expression().also { expectClose() }
            }
            val start = pos
            if (s[pos] == '-' || s[pos] == '+') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            val number = s.substring(start, pos).toFloatOrNull() ?: throw IllegalArgumentException("number expected")
            val unitStart = pos
            while (pos < s.length && (s[pos].isLetter() || s[pos] == '%')) pos++
            return when (s.substring(unitStart, pos)) {
                "", "px" -> number
                "em" -> number * ctx.fontSize
                "rem" -> number * ctx.rootFontSize
                "vh" -> number * ctx.viewportHeight / 100f
                "vw" -> number * ctx.viewportWidth / 100f
                "pt" -> number * 4f / 3f
                "%" -> number * (ctx.percentBase ?: throw IllegalArgumentException("% without base")) / 100f
                else -> throw IllegalArgumentException("unit")
            }
        }

        private fun expectClose() {
            skipWs()
            require(pos < s.length && s[pos] == ')') { ") expected" }
            pos++
        }
    }
}
