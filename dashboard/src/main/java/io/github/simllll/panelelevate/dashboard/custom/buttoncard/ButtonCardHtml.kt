package io.github.simllll.panelelevate.dashboard.custom.buttoncard

import io.github.simllll.panelelevate.dashboard.custom.Css

/** One inline piece of button-card label HTML. */
sealed interface HtmlPiece {
    /** Text run with the inline styles of its enclosing elements. */
    data class Text(
        val text: String,
        val color: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val fontSize: String? = null,
    ) : HtmlPiece

    /** `<ha-icon icon="…" style="width/height/color">`: icon of min(width, height), centered in its box. */
    data class Icon(val icon: String, val width: Float? = null, val height: Float? = null, val color: String? = null) : HtmlPiece

    data object LineBreak : HtmlPiece
}

/**
 * Parses the small HTML subset button-card names/labels use into inline pieces: text, `span`,
 * `b`/`strong`, `i`/`em`, `font`, `div`/`p`/`br` (line breaks), `ha-icon`/`ha-state-icon`, with
 * inline `style` colors, weights and sizes. Whitespace collapses like HTML; content inside icon
 * elements is ignored (it is not slotted in the browser either). Unknown tags keep their text.
 */
object ButtonCardHtml {
    private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>""")
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""")

    fun isHtml(text: String): Boolean = text.contains('<') && TAG.containsMatchIn(text)

    fun parse(html: String): List<HtmlPiece> {
        val out = ArrayList<HtmlPiece>()
        val stack = ArrayList<Pair<String, Map<String, String>>>() // tag, style
        var iconDepth = 0
        var last = 0

        fun currentText(raw: String) {
            if (iconDepth > 0 || raw.isEmpty()) return
            var color: String? = null
            var bold = false
            var italic = false
            var size: String? = null
            for ((tag, style) in stack) {
                when (tag) {
                    "b", "strong" -> bold = true
                    "i", "em" -> italic = true
                }
                style["color"]?.let { color = it }
                style["font-weight"]?.let { w -> bold = Css.fontWeight(w)?.let { it.weight >= 600 } ?: bold }
                style["font-style"]?.let { italic = it == "italic" }
                style["font-size"]?.let { size = it }
            }
            out += HtmlPiece.Text(decode(raw), color, bold, italic, size)
        }

        for (m in TAG.findAll(html)) {
            currentText(html.substring(last, m.range.first))
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrs = ATTR.findAll(m.groupValues[3]).associate { a ->
                a.groupValues[1].lowercase() to (a.groups[3]?.value ?: a.groups[4]?.value ?: a.groups[5]?.value ?: "")
            }
            val selfClosing = m.groupValues[3].trimEnd().endsWith("/") || tag in VOID
            when {
                tag == "br" -> if (!closing) out += HtmlPiece.LineBreak
                tag == "ha-icon" || tag == "ha-state-icon" || tag == "iron-icon" -> {
                    if (closing) {
                        iconDepth = (iconDepth - 1).coerceAtLeast(0)
                    } else {
                        val style = Css.parseInline(attrs["style"])
                        val icon = attrs["icon"]
                        if (icon != null && iconDepth == 0) {
                            out += HtmlPiece.Icon(
                                icon,
                                width = Css.length(style["width"], 0f),
                                height = Css.length(style["height"], 0f),
                                color = style["color"] ?: inheritedColor(stack),
                            )
                        }
                        if (!selfClosing) iconDepth++
                    }
                }
                closing -> {
                    val idx = stack.indexOfLast { it.first == tag }
                    if (idx >= 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                    if (tag in BLOCK) out += HtmlPiece.LineBreak
                }
                selfClosing -> Unit
                else -> {
                    if (tag in BLOCK && out.isNotEmpty() && out.last() != HtmlPiece.LineBreak) out += HtmlPiece.LineBreak
                    val style = Css.parseInline(attrs["style"]).let { s ->
                        if (tag == "font" && attrs["color"] != null) s + ("color" to attrs.getValue("color")) else s
                    }
                    stack += tag to style
                }
            }
        }
        currentText(html.substring(last))
        return collapseWhitespace(out)
    }

    private fun inheritedColor(stack: List<Pair<String, Map<String, String>>>): String? =
        stack.lastOrNull { it.second.containsKey("color") }?.second?.get("color")

    /** HTML whitespace collapsing across runs; trims at line starts/ends. */
    private fun collapseWhitespace(pieces: List<HtmlPiece>): List<HtmlPiece> {
        val out = ArrayList<HtmlPiece>()
        var lastWasSpace = true // start of a line
        for (p in pieces) {
            when (p) {
                is HtmlPiece.Text -> {
                    val sb = StringBuilder()
                    for (ch in p.text) {
                        if (ch == ' ') {
                            sb.append(ch)
                            lastWasSpace = false
                        } else if (ch.isWhitespace()) {
                            if (!lastWasSpace) sb.append(' ')
                            lastWasSpace = true
                        } else {
                            sb.append(ch)
                            lastWasSpace = false
                        }
                    }
                    if (sb.isNotEmpty()) out += p.copy(text = sb.toString())
                }
                HtmlPiece.LineBreak -> {
                    trimTrailingSpace(out)
                    out += p
                    lastWasSpace = true
                }
                is HtmlPiece.Icon -> {
                    out += p
                    lastWasSpace = false
                }
            }
        }
        trimTrailingSpace(out)
        while (out.lastOrNull() == HtmlPiece.LineBreak) out.removeAt(out.size - 1)
        while (out.firstOrNull() == HtmlPiece.LineBreak) out.removeAt(0)
        return out
    }

    private fun trimTrailingSpace(out: MutableList<HtmlPiece>) {
        val lastText = out.lastOrNull() as? HtmlPiece.Text ?: return
        val trimmed = lastText.text.trimEnd(' ')
        if (trimmed.isEmpty()) out.removeAt(out.size - 1) else out[out.size - 1] = lastText.copy(text = trimmed)
    }

    private fun decode(s: String): String = ENTITY.replace(s) { m ->
        when (val name = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            "nbsp" -> " "
            else -> when {
                name.startsWith("#x") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> m.value
            }
        }
    }

    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val VOID = setOf("br", "img", "hr", "input", "meta", "link")
    private val BLOCK = setOf("div", "p")
}
