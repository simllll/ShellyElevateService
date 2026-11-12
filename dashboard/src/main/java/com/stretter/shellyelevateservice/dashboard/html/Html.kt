package com.stretter.shellyelevateservice.dashboard.html

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme

/**
 * Native rendering of an HTML subset (popup content strings): block elements (`div`, `p`,
 * `center`, `h1`–`h6`, `ul`/`ol`/`li`, `hr`) stack vertically, inline content (`span`, `b`,
 * `strong`, `i`, `em`, `small`, `a`, `br`, `ha-icon`) flows as text with icons inline.
 * Supported inline styles: `color`, `background(-color)`, `border-radius`, `font-size`,
 * `font-weight`, `font-style`, `opacity`, `text-align`, `line-height`, `margin(-*)`,
 * `padding(-*)`, `display: none`, and for `ha-icon` `--mdc-icon-size`/`width`/`height`.
 */
@Composable
fun Html(html: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    val nodes = remember(html) { HtmlParser.parse(html) }
    val base = HtmlStyle(
        fontSize = HaTheme.typography.sizeM.value,
        color = HaTheme.colors.primaryText,
        align = textAlign,
    )
    Blocks(nodes, base, modifier.fillMaxWidth())
}

/** Inherited text properties while walking the tree. Sizes in CSS px. */
@Immutable
internal data class HtmlStyle(
    val fontSize: Float,
    val color: Color,
    val align: TextAlign,
    val weight: Int = 400,
    val italic: Boolean = false,
    /** CSS `line-height: normal` of Roboto. */
    val lineHeight: Float = 1.2f,
    val underline: Boolean = false,
)

private val BLOCK_TAGS = setOf(
    "div", "p", "center", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "hr", "section",
    "header", "footer", "article", "blockquote", "pre", "table", "tr", "form", "figure", "ha-card",
)

private val HEADING_SCALE = mapOf("h1" to 2f, "h2" to 1.5f, "h3" to 1.17f, "h4" to 1f, "h5" to 0.83f, "h6" to 0.67f)

private fun HtmlNode.isBlock() = this is HtmlElement && tag in BLOCK_TAGS && style["display"]?.startsWith("inline") != true

private fun HtmlNode.isHidden() = this is HtmlElement && style["display"] == "none"

/** Applies an element's own (inherited) text properties. */
internal fun HtmlStyle.inherit(el: HtmlElement): HtmlStyle {
    val s = el.style
    val ctx = Css.Context(fontSize = fontSize)
    var r = this
    HEADING_SCALE[el.tag]?.let { r = r.copy(fontSize = fontSize * it, weight = 700) }
    when (el.tag) {
        "b", "strong" -> r = r.copy(weight = 700)
        "i", "em" -> r = r.copy(italic = true)
        "small" -> r = r.copy(fontSize = fontSize * 0.83f)
        "center" -> r = r.copy(align = TextAlign.Center)
        "a" -> r = r.copy(underline = true)
    }
    el.attributes["align"]?.let { textAlign(it) }?.let { r = r.copy(align = it) }
    Css.fontSize(s["font-size"], ctx)?.let { r = r.copy(fontSize = it) }
    Css.fontWeight(s["font-weight"])?.let { r = r.copy(weight = it) }
    s["font-style"]?.let { r = r.copy(italic = it.trim() == "italic" || it.trim() == "oblique") }
    s["text-align"]?.let { textAlign(it) }?.let { r = r.copy(align = it) }
    s["color"]?.let { cssColor(it, r.color) }?.let { r = r.copy(color = it) }
    s["line-height"]?.let { lh ->
        val v = lh.trim()
        (v.toFloatOrNull() ?: Css.length(v, Css.Context(fontSize = r.fontSize))?.div(r.fontSize))?.let { r = r.copy(lineHeight = it) }
    }
    return r
}

private fun textAlign(v: String): TextAlign? = when (v.trim().lowercase()) {
    "center" -> TextAlign.Center
    "right", "end" -> TextAlign.End
    "left", "start" -> TextAlign.Start
    "justify" -> TextAlign.Justify
    else -> null
}

private fun cssColor(value: String, current: Color): Color? {
    val v = value.trim().lowercase()
    if (v == "currentcolor" || v == "inherit") return current
    return CssColors.parse(v)
}

private fun HtmlStyle.textStyle(): TextStyle = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * lineHeight).sp,
    fontWeight = FontWeight(weight.coerceIn(1, 1000)),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    color = color,
    textAlign = align,
    textDecoration = if (underline) TextDecoration.Underline else null,
)

private fun TextAlign.toHorizontal(): Alignment.Horizontal = when (this) {
    TextAlign.Center -> Alignment.CenterHorizontally
    TextAlign.End, TextAlign.Right -> Alignment.End
    else -> Alignment.Start
}

/** Block children: runs of inline nodes become one text flow, block elements nest. */
@Composable
private fun Blocks(nodes: List<HtmlNode>, style: HtmlStyle, modifier: Modifier) {
    val segments = remember(nodes) { segment(nodes) }
    Column(modifier, horizontalAlignment = style.align.toHorizontal()) {
        segments.forEach { seg ->
            if (seg.size == 1 && seg[0].isBlock()) BlockElement(seg[0] as HtmlElement, style)
            else InlineRun(seg, style)
        }
    }
}

private fun segment(nodes: List<HtmlNode>): List<List<HtmlNode>> {
    val out = mutableListOf<List<HtmlNode>>()
    var run = mutableListOf<HtmlNode>()
    for (n in nodes) {
        if (n.isHidden()) continue
        if (n.isBlock()) {
            if (run.isNotEmpty()) out += run
            run = mutableListOf()
            out += listOf(n)
        } else run += n
    }
    if (run.isNotEmpty()) out += run
    // Drop runs that are only whitespace between blocks.
    return out.filterNot { seg -> seg.all { it is HtmlText && it.text.isBlank() } }
}

@Composable
private fun BlockElement(el: HtmlElement, parent: HtmlStyle) {
    val style = parent.inherit(el)
    val s = el.style
    val ctx = Css.Context(fontSize = style.fontSize)
    if (el.tag == "hr") {
        Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(HaTheme.colors.divider))
        return
    }
    val margin = edges(s, "margin", ctx) ?: defaultMargin(el.tag, style.fontSize)
    val padding = edges(s, "padding", ctx) ?: if (el.tag == "ul" || el.tag == "ol") floatArrayOf(0f, 0f, 0f, 24f) else null
    val background = (s["background-color"] ?: s["background"])?.let { CssColors.parse(it) }
    val radius = Css.length(s["border-radius"], ctx) ?: 0f
    val opacity = s["opacity"]?.trim()?.let { it.removeSuffix("%").toFloatOrNull()?.let { v -> if (it.endsWith("%")) v / 100f else v } }
    var modifier: Modifier = Modifier.fillMaxWidth()
    if (margin != null) modifier = modifier.padding(margin[3].dp.coerceAtLeast(0.dp), margin[0].dp.coerceAtLeast(0.dp), margin[1].dp.coerceAtLeast(0.dp), margin[2].dp.coerceAtLeast(0.dp))
    if (opacity != null) modifier = modifier.alpha(opacity.coerceIn(0f, 1f))
    if (background != null) modifier = modifier.background(background, RoundedCornerShape(radius.dp))
    if (padding != null) modifier = modifier.padding(padding[3].dp, padding[0].dp, padding[1].dp, padding[2].dp)
    if (el.tag == "li") {
        Row(modifier) {
            BasicText("•  ", style = style.textStyle().copy(textAlign = TextAlign.Start))
            Blocks(el.children, style, Modifier.weight(1f))
        }
        return
    }
    Blocks(el.children, style, modifier)
}

/** `margin`/`padding` shorthand plus `-top/-right/-bottom/-left` overrides; null if none set. */
private fun edges(s: Map<String, String>, prop: String, ctx: Css.Context): FloatArray? {
    var e = Css.box(s[prop], ctx)
    for ((i, side) in listOf("top", "right", "bottom", "left").withIndex()) {
        val v = Css.length(s["$prop-$side"], ctx) ?: continue
        if (e == null) e = FloatArray(4)
        e[i] = v
    }
    return e
}

private fun defaultMargin(tag: String, fontSize: Float): FloatArray? = when (tag) {
    "p", "ul", "ol", "blockquote" -> floatArrayOf(fontSize, 0f, fontSize, 0f)
    "h1" -> floatArrayOf(fontSize * 0.67f, 0f, fontSize * 0.67f, 0f)
    "h2", "h3", "h4", "h5", "h6" -> floatArrayOf(fontSize * 0.83f, 0f, fontSize * 0.83f, 0f)
    else -> null
}

private const val ICON_PREFIX = "icon:"

/** One inline flow: text with spans; icons become inline placeholders (or plain icons if alone). */
@Composable
private fun InlineRun(nodes: List<HtmlNode>, style: HtmlStyle) {
    val (text, icons) = remember(nodes, style) {
        val collected = mutableListOf<InlineIcon>()
        buildAnnotatedString { appendInline(nodes, style, collected) }.trimmed() to collected.toList()
    }
    // Only icons (and whitespace): draw them directly, exact size, aligned like the text.
    if (icons.isNotEmpty() && text.text.all { it == '�' || it.isWhitespace() }) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = when (style.align) {
                TextAlign.Center -> Arrangement.Center
                TextAlign.End, TextAlign.Right -> Arrangement.End
                else -> Arrangement.Start
            },
        ) {
            icons.forEach { MdiIcon(it.icon, it.color, size = it.size.dp) }
        }
        return
    }
    if (text.text.isBlank()) return
    val density = LocalDensity.current
    val inline = remember(icons, density) {
        icons.mapIndexed { i, icon ->
            val size = with(density) { icon.size.dp.toSp() }
            "$ICON_PREFIX$i" to InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.TextCenter)) {
                MdiIcon(icon.icon, icon.color, size = icon.size.dp)
            }
        }.toMap()
    }
    BasicText(text, Modifier.fillMaxWidth(), style = style.textStyle(), inlineContent = inline)
}

private data class InlineIcon(val icon: String, val color: Color, val size: Float)

private fun AnnotatedString.Builder.appendInline(nodes: List<HtmlNode>, style: HtmlStyle, icons: MutableList<InlineIcon>) {
    for (node in nodes) {
        if (node.isHidden()) continue
        when (node) {
            is HtmlText -> append(collapseWhitespace(node.text))
            is HtmlElement -> when (node.tag) {
                "br" -> append('\n')
                "ha-icon", "ha-svg-icon", "ha-state-icon" -> {
                    val s = node.style
                    val iconStyle = style.inherit(node)
                    val size = Css.length(s["--mdc-icon-size"]) ?: listOfNotNull(Css.length(s["width"]), Css.length(s["height"])).minOrNull() ?: 24f
                    val icon = node.attributes["icon"] ?: continue
                    icons += InlineIcon(icon, iconStyle.color, size)
                    appendInlineContent("$ICON_PREFIX${icons.size - 1}", "�")
                }
                "img" -> node.attributes["alt"]?.let { append(it) }
                else -> {
                    val inner = style.inherit(node)
                    val opacity = node.style["opacity"]?.toFloatOrNull()
                    val color = if (opacity != null) inner.color.copy(alpha = inner.color.alpha * opacity.coerceIn(0f, 1f)) else inner.color
                    val span = SpanStyle(
                        color = color,
                        fontSize = inner.fontSize.sp,
                        fontWeight = FontWeight(inner.weight.coerceIn(1, 1000)),
                        fontStyle = if (inner.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (inner.underline) TextDecoration.Underline else null,
                        background = (node.style["background-color"] ?: node.style["background"])?.let { CssColors.parse(it) } ?: Color.Unspecified,
                    )
                    pushStyle(span)
                    appendInline(node.children, inner.copy(color = color), icons)
                    pop()
                }
            }
        }
    }
}

private val WS = Regex("[ \\t\\r\\n\\f]+")

internal fun collapseWhitespace(s: String): String = WS.replace(s, " ")

/** Trims leading/trailing spaces of the flow and spaces around line breaks (CSS white-space: normal). */
private fun AnnotatedString.trimmed(): AnnotatedString {
    var start = 0
    var end = length
    while (start < end && text[start] == ' ') start++
    while (end > start && text[end - 1] == ' ') end--
    return if (start == 0 && end == length) this else subSequence(start, end)
}
