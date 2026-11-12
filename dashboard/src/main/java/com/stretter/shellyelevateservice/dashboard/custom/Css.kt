package com.stretter.shellyelevateservice.dashboard.custom

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.stretter.shellyelevateservice.dashboard.theme.CssColors

/** Small CSS value helpers for custom cards that take inline CSS in their config. */
object Css {
    /** Root font size of the HA frontend (`html { font-size: 14px }`): 1rem = 14px. */
    const val REM_PX = 14f

    /**
     * Evaluates a CSS length in px: `12px`, `1.5em`, `2rem`, `70%`, `calc(70% - 10em)`, bare numbers.
     * [percentBase] is the width percentages refer to, [emPx] the element's font size.
     * Returns null for `auto` and anything unsupported.
     */
    fun length(value: String?, percentBase: Float, emPx: Float = REM_PX): Float? {
        val v = value?.trim()?.removeSuffix(";")?.removeSuffix("!important")?.trim()?.lowercase() ?: return null
        if (v.isEmpty() || v == "auto") return null
        if (v.startsWith("calc(") && v.endsWith(")")) return calc(v.substring(5, v.length - 1), percentBase, emPx)
        return term(v, percentBase, emPx)
    }

    private fun term(v: String, percentBase: Float, emPx: Float): Float? {
        val t = v.trim()
        fun num(suffix: String) = t.removeSuffix(suffix).trim().toFloatOrNull()
        return when {
            t.endsWith("px") -> num("px")
            t.endsWith("rem") -> num("rem")?.times(REM_PX)
            t.endsWith("em") -> num("em")?.times(emPx)
            t.endsWith("%") -> num("%")?.times(percentBase / 100f)
            t.endsWith("vw") || t.endsWith("vh") -> null
            else -> t.toFloatOrNull()
        }
    }

    /** `a + b - c` (operators need surrounding spaces, as in CSS). */
    private fun calc(expr: String, percentBase: Float, emPx: Float): Float? {
        val tokens = expr.trim().split(Regex("\\s+"))
        var total = term(tokens.firstOrNull() ?: return null, percentBase, emPx) ?: return null
        var i = 1
        while (i + 1 < tokens.size) {
            val operand = term(tokens[i + 1], percentBase, emPx) ?: return null
            total = when (tokens[i]) {
                "+" -> total + operand
                "-" -> total - operand
                "*" -> total * operand
                "/" -> if (operand != 0f) total / operand else return null
                else -> return null
            }
            i += 2
        }
        return total
    }

    /** CSS color incl. `var(--mdc-theme-primary)` (= primary color) and theme color names. */
    fun color(value: String?): Color? {
        val v = value?.trim()?.removeSuffix(";")?.trim() ?: return null
        return CssColors.parse(v.replace("--mdc-theme-primary", "--primary-color")) ?: CssColors.computeCssColor(v)
    }

    fun fontWeight(value: String?): FontWeight? = when (value?.trim()?.lowercase()) {
        null -> null
        "bold", "bolder" -> FontWeight.Bold
        "normal" -> FontWeight.Normal
        "lighter" -> FontWeight.Light
        else -> value.trim().toIntOrNull()?.coerceIn(1, 1000)?.let { FontWeight(it) }
    }

    /** Parses an inline `style="a: b; c: d"` attribute into a map (lowercase property names). */
    fun parseInline(style: String?): Map<String, String> {
        if (style.isNullOrBlank()) return emptyMap()
        return style.split(';').mapNotNull { decl ->
            val idx = decl.indexOf(':')
            if (idx <= 0) null else decl.substring(0, idx).trim().lowercase() to decl.substring(idx + 1).trim()
        }.toMap()
    }
}
