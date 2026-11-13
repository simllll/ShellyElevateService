package io.github.simllll.panelelevate.dashboard.html

/** A parsed HTML node: an element or a text run (entities decoded, whitespace not yet collapsed). */
sealed interface HtmlNode

data class HtmlText(val text: String) : HtmlNode

data class HtmlElement(
    /** Lower-case tag name, e.g. `div`, `ha-icon`. */
    val tag: String,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<HtmlNode> = emptyList(),
) : HtmlNode {
    /** Declarations of the `style` attribute, property names lower-case. */
    val style: Map<String, String> by lazy { HtmlParser.parseStyle(attributes["style"].orEmpty()) }
}

/**
 * Tolerant parser for the HTML snippets HA automations put into `browser_mod.popup` (`content`
 * as a string is rendered with lit's `unsafeHTML`). Handles quoted/unquoted/bare attributes,
 * void and self-closing tags, comments, entities and unbalanced markup (unclosed tags close at
 * the end, a stray end tag closes up to its opening tag or is ignored). No scripts, no CSS
 * selectors: only what the native renderer ([Html]) can draw.
 */
object HtmlParser {
    private val VOID = setOf("br", "hr", "img", "input", "meta", "link", "source", "wbr", "area", "col", "embed", "param", "track")
    private val RAW_TEXT = setOf("script", "style")

    fun parse(html: String): List<HtmlNode> {
        val root = Builder("#root", emptyMap())
        val stack = ArrayDeque<Builder>().apply { addLast(root) }
        var i = 0
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                stack.last().children += HtmlText(decodeEntities(text.toString()))
                text.clear()
            }
        }

        while (i < html.length) {
            val c = html[i]
            if (c != '<') {
                text.append(c)
                i++
                continue
            }
            when {
                html.startsWith("<!--", i) -> {
                    flushText()
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) html.length else end + 3
                }
                html.startsWith("</", i) -> {
                    val end = html.indexOf('>', i)
                    if (end < 0) { text.append(html, i, html.length); i = html.length; continue }
                    flushText()
                    val name = html.substring(i + 2, end).trim().lowercase()
                    // Close up to the matching open tag; ignore end tags that were never opened.
                    if (stack.drop(1).any { it.tag == name }) {
                        while (stack.size > 1) {
                            val b = stack.removeLast()
                            stack.last().children += b.build()
                            if (b.tag == name) break
                        }
                    }
                    i = end + 1
                }
                i + 1 < html.length && (html[i + 1].isLetter() || html[i + 1] == '!') -> {
                    if (html[i + 1] == '!') { // <!doctype …>
                        flushText()
                        val end = html.indexOf('>', i)
                        i = if (end < 0) html.length else end + 1
                        continue
                    }
                    val tag = readTag(html, i)
                    if (tag == null) { text.append(c); i++; continue }
                    flushText()
                    i = tag.end
                    when {
                        tag.name in RAW_TEXT -> {
                            val close = html.indexOf("</${tag.name}", i, ignoreCase = true)
                            val end = if (close < 0) html.length else html.indexOf('>', close).let { if (it < 0) html.length else it + 1 }
                            i = end // dropped
                        }
                        tag.selfClosing || tag.name in VOID -> stack.last().children += HtmlElement(tag.name, tag.attributes)
                        else -> stack.addLast(Builder(tag.name, tag.attributes))
                    }
                }
                else -> { text.append(c); i++ }
            }
        }
        flushText()
        while (stack.size > 1) {
            val b = stack.removeLast()
            stack.last().children += b.build()
        }
        return root.children
    }

    private class Builder(val tag: String, val attributes: Map<String, String>) {
        val children = mutableListOf<HtmlNode>()
        fun build() = HtmlElement(tag, attributes, children.toList())
    }

    private class Tag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean, val end: Int)

    /** Reads `<name attr=…>` starting at [start] (the `<`); null if it is not a tag. */
    private fun readTag(s: String, start: Int): Tag? {
        var i = start + 1
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_' || s[i] == ':')) i++
        if (i == nameStart) return null
        val name = s.substring(nameStart, i).lowercase()
        val attrs = LinkedHashMap<String, String>()
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return null
            when (s[i]) {
                '>' -> return Tag(name, attrs, false, i + 1)
                '/' -> {
                    if (i + 1 < s.length && s[i + 1] == '>') return Tag(name, attrs, true, i + 2)
                    i++
                }
                else -> {
                    val aStart = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
                    val attrName = s.substring(aStart, i).lowercase()
                    while (i < s.length && s[i].isWhitespace()) i++
                    var value = ""
                    if (i < s.length && s[i] == '=') {
                        i++
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                            val q = s[i]
                            val close = s.indexOf(q, i + 1)
                            if (close < 0) return null
                            value = s.substring(i + 1, close)
                            i = close + 1
                        } else {
                            val vStart = i
                            while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                            value = s.substring(vStart, i)
                        }
                    }
                    if (attrName.isNotEmpty()) attrs[attrName] = decodeEntities(value)
                }
            }
        }
        return null
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "deg" to "°", "euro" to "€", "hellip" to "…", "ndash" to "–", "mdash" to "—", "middot" to "·",
        "bull" to "•", "copy" to "©", "times" to "×", "rarr" to "→", "larr" to "←",
    )
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED_ENTITIES[e]
            } ?: m.value
        }
    }

    /** `a: b; c: d` → map (lower-case names, values trimmed, `!important` dropped). */
    fun parseStyle(style: String): Map<String, String> {
        if (style.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        // Split on ';' outside parentheses (values like `var(--x, rgb(1,2,3))`).
        var depth = 0
        var start = 0
        fun add(decl: String) {
            val colon = decl.indexOf(':')
            if (colon <= 0) return
            val name = decl.substring(0, colon).trim().lowercase()
            val value = decl.substring(colon + 1).trim().removeSuffix("!important").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        for (i in style.indices) {
            when (style[i]) {
                '(' -> depth++
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                ';' -> if (depth == 0) { add(style.substring(start, i)); start = i + 1 }
            }
        }
        add(style.substring(start))
        return out
    }
}
