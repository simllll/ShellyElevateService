package com.stretter.shellyelevateservice.dashboard.markdown

/**
 * A Markdown subset as rendered by `ha-markdown` (marked with `breaks: true`) plus the inline HTML
 * dashboards commonly use. Pure Kotlin so it can be unit tested.
 *
 * Blocks: ATX headings, paragraphs (single newlines are line breaks), bullet/ordered lists (nested
 * by indentation), block quotes, fenced code, horizontal rules, `<center>` regions.
 * Inline: `**bold**`, `*italic*`/`_italic_`, `~~strike~~`, `` `code` ``, links, `<b> <strong> <i>
 * <em> <u> <s> <small> <big> <code> <a> <font color> <span style="color:…"> <br> <ha-icon icon=…>`,
 * HTML entities. Images are reduced to their alt text; other tags are dropped.
 */
object MarkdownParser {

    sealed interface Block {
        data class Heading(val level: Int, val inlines: List<Inline>) : Block
        data class Paragraph(val inlines: List<Inline>) : Block
        data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block
        data class Quote(val blocks: List<Block>) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
        data class Centered(val blocks: List<Block>) : Block
    }

    data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val small: Boolean = false,
        val big: Boolean = false,
        val color: String? = null,
        val link: String? = null,
    )

    sealed interface Inline {
        data class Text(val text: String, val style: Style = Style()) : Inline
        data class Icon(val icon: String, val color: String? = null, val sizePx: Float? = null) : Inline
        data object LineBreak : Inline
    }

    private const val CENTER_OPEN = "\u0001center"
    private const val CENTER_CLOSE = "\u0001/center"

    fun parse(markdown: String): List<Block> {
        val normalized = markdown.replace("\r\n", "\n")
            .replace(Regex("<center>", RegexOption.IGNORE_CASE), "\n$CENTER_OPEN\n")
            .replace(Regex("</center>", RegexOption.IGNORE_CASE), "\n$CENTER_CLOSE\n")
        return parseCentered(normalized.split('\n'))
    }

    /** Splits on center markers, then parses each region. */
    private fun parseCentered(lines: List<String>): List<Block> {
        val result = mutableListOf<Block>()
        val buffer = mutableListOf<String>()
        var depth = 0
        val centerBuffer = mutableListOf<String>()
        for (line in lines) {
            when (line.trim()) {
                CENTER_OPEN -> {
                    if (depth == 0) {
                        result += parseBlocks(buffer)
                        buffer.clear()
                    } else centerBuffer += line
                    depth++
                }
                CENTER_CLOSE -> {
                    if (depth == 0) continue
                    depth--
                    if (depth == 0) {
                        result += Block.Centered(parseCentered(centerBuffer.toList()))
                        centerBuffer.clear()
                    } else centerBuffer += line
                }
                else -> if (depth > 0) centerBuffer += line else buffer += line
            }
        }
        if (centerBuffer.isNotEmpty()) result += Block.Centered(parseCentered(centerBuffer.toList()))
        result += parseBlocks(buffer)
        return result
    }

    private val HEADING = Regex("""^ {0,3}(#{1,6})(?:\s+(.*?))?\s*#*\s*$""")
    private val RULE = Regex("""^ {0,3}([-*_])(?:\s*\1){2,}\s*$""")
    private val LIST_ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val FENCE = Regex("""^ {0,3}(```|~~~)""")

    fun parseBlocks(lines: List<String>): List<Block> {
        val blocks = mutableListOf<Block>()
        var i = 0
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(parseInline(paragraph.joinToString("\n") { it.trim() }))
                paragraph.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }
            val fence = FENCE.find(line)
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[1]
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                i++ // closing fence
                blocks += Block.Code(code.joinToString("\n"))
                continue
            }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flushParagraph()
                blocks += Block.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
                i++
                continue
            }
            if (RULE.matches(line)) {
                flushParagraph()
                blocks += Block.Rule
                i++
                continue
            }
            if (line.trimStart().startsWith(">")) {
                flushParagraph()
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                blocks += Block.Quote(parseBlocks(quoted))
                continue
            }
            val item = LIST_ITEM.matchEntire(line)
            if (item != null) { // GFM: lists may interrupt a paragraph
                flushParagraph()
                i = parseList(lines, i, blocks)
                continue
            }
            paragraph += line
            i++
        }
        flushParagraph()
        return blocks
    }

    /** Parses a list starting at [start]; returns the index after it. */
    private fun parseList(lines: List<String>, start: Int, out: MutableList<Block>): Int {
        val first = LIST_ITEM.matchEntire(lines[start])!!
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2].first().isDigit()
        val startNumber = if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1
        val items = mutableListOf<MutableList<String>>()
        var contentIndent = baseIndent + first.groupValues[2].length + 1
        var i = start
        var blankPending = false
        while (i < lines.size) {
            val line = lines[i]
            val m = LIST_ITEM.matchEntire(line)
            val indent = line.length - line.trimStart().length
            if (line.isBlank()) {
                blankPending = true
                i++
                continue
            }
            if (m != null && m.groupValues[1].length <= baseIndent + 1 && m.groupValues[2].first().isDigit() == ordered) {
                items += mutableListOf(m.groupValues[3])
                contentIndent = m.groupValues[1].length + m.groupValues[2].length + 1
                blankPending = false
            } else if (items.isNotEmpty() && (indent > baseIndent || (!blankPending && m == null))) {
                // Continuation or nested content: strip up to the content indent.
                val strip = minOf(indent, contentIndent)
                items.last() += if (indent > baseIndent) line.substring(strip) else line.trim()
                blankPending = false
            } else break
            i++
        }
        out += Block.ListBlock(ordered, startNumber, items.map { itemLines -> parseItem(itemLines) })
        return i
    }

    /** List items: a single text line stays one paragraph; nested lists are parsed recursively. */
    private fun parseItem(lines: List<String>): List<Block> = parseBlocks(lines)

    // ------------------------------------------------------------------ inline

    private val ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&nbsp;" to " ", "&deg;" to "°", "&euro;" to "€", "&middot;" to "·", "&ndash;" to "–", "&mdash;" to "—",
    )

    private val TAG = Regex("""^<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^>]*?)?)\s*(/?)>""")
    private val ATTR = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")

    fun parseInline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val sb = StringBuilder()
        val stack = ArrayDeque<Pair<String, Style>>() // tag/marker -> style before it
        var style = Style()

        fun flush() {
            if (sb.isNotEmpty()) {
                out += Inline.Text(sb.toString(), style)
                sb.clear()
            }
        }
        fun push(marker: String, next: Style) {
            flush()
            stack.addLast(marker to style)
            style = next
        }
        fun pop(marker: String): Boolean {
            val index = stack.indexOfLast { it.first == marker }
            if (index < 0) return false
            flush()
            style = stack[index].second
            while (stack.size > index) stack.removeLast()
            return true
        }
        fun toggle(marker: String, open: (Style) -> Style, rest: String): Boolean {
            if (stack.any { it.first == marker }) return pop(marker)
            // Only open if the delimiter closes later on, like Markdown does.
            if (!rest.contains(marker)) return false
            push(marker, open(style))
            return true
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val rest = text.substring(i)
            when {
                ch == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!~<>|" -> {
                    sb.append(text[i + 1])
                    i += 2
                }
                ch == '\n' -> {
                    flush()
                    out += Inline.LineBreak
                    i++
                }
                ch == '&' -> {
                    val entity = ENTITIES.entries.firstOrNull { rest.startsWith(it.key) }
                    val numeric = Regex("""^&#(\d+);""").find(rest)
                    when {
                        entity != null -> { sb.append(entity.value); i += entity.key.length }
                        numeric != null -> { sb.appendCodePoint(numeric.groupValues[1].toInt()); i += numeric.value.length }
                        else -> { sb.append(ch); i++ }
                    }
                }
                ch == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += Inline.Text(text.substring(i + 1, end), style.copy(code = true))
                        i = end + 1
                    } else { sb.append(ch); i++ }
                }
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val marker = rest.substring(0, 2)
                    if (toggle(marker, { it.copy(bold = true) }, text.substring(i + 2))) i += 2 else { sb.append(marker); i += 2 }
                }
                rest.startsWith("~~") -> {
                    if (toggle("~~", { it.copy(strike = true) }, text.substring(i + 2))) i += 2 else { sb.append("~~"); i += 2 }
                }
                ch == '*' || (ch == '_' && (i == 0 || !text[i - 1].isLetterOrDigit() || stack.any { it.first == "_" })) -> {
                    val marker = ch.toString()
                    if (toggle(marker, { it.copy(italic = true) }, text.substring(i + 1))) i++ else { sb.append(ch); i++ }
                }
                ch == '!' && rest.startsWith("![") -> {
                    val link = LINK.find(rest.substring(1))
                    if (link != null && link.range.first == 0) {
                        sb.append(link.groupValues[1])
                        i += 1 + link.value.length
                    } else { sb.append(ch); i++ }
                }
                ch == '[' -> {
                    val link = LINK.find(rest)
                    if (link != null && link.range.first == 0) {
                        flush()
                        parseInline(link.groupValues[1]).forEach { inline ->
                            out += if (inline is Inline.Text) inline.copy(style = mergeStyle(style, inline.style).copy(link = link.groupValues[2])) else inline
                        }
                        i += link.value.length
                    } else { sb.append(ch); i++ }
                }
                ch == '<' -> {
                    val tag = TAG.find(rest)
                    if (tag == null) { sb.append(ch); i++ } else {
                        val closing = tag.groupValues[1] == "/"
                        val name = tag.groupValues[2].lowercase()
                        val attrs = parseAttributes(tag.groupValues[3])
                        i += tag.value.length
                        if (closing) pop(name) else handleOpenTag(name, attrs, tag.groupValues[4] == "/",
                            onIcon = { flush(); out += it },
                            onBreak = { flush(); out += Inline.LineBreak },
                            onPush = { next -> push(name, next(style)) },
                        )
                    }
                }
                else -> { sb.append(ch); i++ }
            }
        }
        flush()
        return out
    }

    private val LINK = Regex("""\[([^\]]*)]\(([^)\s]*)(?:\s+"[^"]*")?\)""")

    private fun mergeStyle(outer: Style, inner: Style) = inner.copy(
        bold = outer.bold || inner.bold,
        italic = outer.italic || inner.italic,
        underline = outer.underline || inner.underline,
        strike = outer.strike || inner.strike,
        color = inner.color ?: outer.color,
    )

    private fun handleOpenTag(
        name: String,
        attrs: Map<String, String>,
        selfClosing: Boolean,
        onIcon: (Inline.Icon) -> Unit,
        onBreak: () -> Unit,
        onPush: ((Style) -> Style) -> Unit,
    ) {
        when (name) {
            "br" -> onBreak()
            "ha-icon", "ha-state-icon" -> attrs["icon"]?.let { icon ->
                val css = parseCss(attrs["style"])
                onIcon(Inline.Icon(icon, css["color"] ?: attrs["color"], css["--mdc-icon-size"]?.removeSuffix("px")?.toFloatOrNull()))
            }
            "b", "strong" -> if (!selfClosing) onPush { it.copy(bold = true) }
            "i", "em" -> if (!selfClosing) onPush { it.copy(italic = true) }
            "u", "ins" -> if (!selfClosing) onPush { it.copy(underline = true) }
            "s", "del", "strike" -> if (!selfClosing) onPush { it.copy(strike = true) }
            "small" -> if (!selfClosing) onPush { it.copy(small = true) }
            "big" -> if (!selfClosing) onPush { it.copy(big = true) }
            "code" -> if (!selfClosing) onPush { it.copy(code = true) }
            "a" -> if (!selfClosing) onPush { it.copy(link = attrs["href"] ?: "") }
            "font" -> if (!selfClosing) onPush { s -> s.copy(color = attrs["color"] ?: s.color) }
            "span", "div", "p" -> if (!selfClosing) {
                val css = parseCss(attrs["style"])
                onPush { s ->
                    s.copy(
                        color = css["color"] ?: s.color,
                        bold = s.bold || css["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 0) >= 600 } == true,
                        italic = s.italic || css["font-style"] == "italic",
                    )
                }
            }
        }
    }

    private fun parseAttributes(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { m ->
            m.groupValues[1].lowercase() to (m.groups[3]?.value ?: m.groups[4]?.value ?: m.groups[5]?.value ?: "")
        }

    fun parseCss(style: String?): Map<String, String> = style.orEmpty().split(';').mapNotNull { decl ->
        val idx = decl.indexOf(':')
        if (idx <= 0) null else decl.substring(0, idx).trim().lowercase() to decl.substring(idx + 1).trim()
    }.toMap()
}
