package com.stretter.shellyelevateservice.dashboard.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcon
import com.stretter.shellyelevateservice.dashboard.markdown.MarkdownParser.Block
import com.stretter.shellyelevateservice.dashboard.markdown.MarkdownParser.Inline
import com.stretter.shellyelevateservice.dashboard.theme.CssColors
import com.stretter.shellyelevateservice.dashboard.theme.HaTheme

/**
 * Renders Markdown like `ha-markdown`: 14px body with line-height 1.6, browser default block
 * margins (collapsed, none before the first / after the last block), 40px list indent,
 * `<ha-icon>` inline at 24px.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    MarkdownBlocks(blocks, modifier, textAlign)
}

@Composable
fun MarkdownBlocks(blocks: List<Block>, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    val t = HaTheme.typography
    val base = t.style(t.sizeM, color = HaTheme.colors.primaryText)
    Column(modifier, horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
        var previousMargin: Dp? = null
        blocks.forEach { block ->
            val margin = blockMargin(block, base.fontSize)
            if (previousMargin != null) Spacer(Modifier.height(maxOf(previousMargin!!, margin)))
            RenderBlock(block, base, textAlign)
            previousMargin = margin
        }
    }
}

/** Vertical margin (top = bottom) of a block, as the browser stylesheet sets it. */
private fun blockMargin(block: Block, fontSize: TextUnit): Dp {
    val px = fontSize.value
    return when (block) {
        is Block.Heading -> (headingScale(block.level) * px * HEADING_MARGINS[block.level - 1]).dp
        is Block.Rule -> 16.dp
        is Block.Centered -> block.blocks.firstOrNull()?.let { blockMargin(it, fontSize) } ?: 0.dp
        else -> px.dp // 1em
    }
}

private val HEADING_MARGINS = floatArrayOf(0.67f, 0.83f, 1f, 1.33f, 1.67f, 2.33f)

/** h1 2em, h2 20px (HA override), h3 1.17em, h4 1em, h5 .83em, h6 .67em. */
private fun headingScale(level: Int): Float = when (level) {
    1 -> 2f
    2 -> 20f / 14f
    3 -> 1.17f
    4 -> 1f
    5 -> 0.83f
    else -> 0.67f
}

@Composable
private fun RenderBlock(block: Block, base: TextStyle, textAlign: TextAlign) {
    val c = HaTheme.colors
    when (block) {
        is Block.Paragraph -> InlineText(block.inlines, base, textAlign)
        is Block.Heading -> {
            val size = (base.fontSize.value * headingScale(block.level)).sp
            InlineText(block.inlines, base.copy(fontSize = size, fontWeight = FontWeight.Bold, lineHeight = (size.value * 1.2f).sp), textAlign)
        }
        is Block.ListBlock -> Column {
            block.items.forEachIndexed { index, item ->
                Row {
                    Box(Modifier.width(40.dp).padding(end = 8.dp), contentAlignment = Alignment.TopEnd) {
                        BasicText(if (block.ordered) "${block.start + index}." else "•", style = base.copy(textAlign = TextAlign.End))
                    }
                    MarkdownBlocks(item, Modifier.weight(1f))
                }
            }
        }
        is Block.Quote -> Row {
            Box(Modifier.width(4.dp).height(24.dp).background(c.divider))
            MarkdownBlocks(block.blocks, Modifier.padding(start = 12.dp).weight(1f))
        }
        is Block.Code -> BasicText(
            block.text,
            style = base.copy(fontFamily = FontFamily.Monospace, lineHeight = (base.fontSize.value * 1.2f).sp),
            modifier = Modifier
                .fillMaxWidth()
                .background(c.codeBackground, RoundedCornerShape(4.dp))
                .padding(16.dp),
        )
        Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        is Block.Centered -> MarkdownBlocks(block.blocks, Modifier.fillMaxWidth(), TextAlign.Center)
    }
}

/** Paragraph-like text with inline styles and `<ha-icon>` placeholders. */
@Composable
fun InlineText(inlines: List<Inline>, style: TextStyle, textAlign: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) {
    val c = HaTheme.colors
    val (text, icons) = remember(inlines, style.fontSize) { buildInline(inlines, c.primary, c.codeBackground) }
    val inlineContent = remember(icons) {
        icons.mapIndexed { index, icon ->
            val size = (icon.sizePx ?: 24f)
            "icon$index" to InlineTextContent(Placeholder(size.sp, size.sp, PlaceholderVerticalAlign.TextCenter)) {
                MdiIcon(icon.icon, icon.color?.let { CssColors.computeCssColor(it) } ?: style.color, size = size.dp)
            }
        }.toMap()
    }
    BasicText(text, modifier, style.copy(textAlign = textAlign), inlineContent = inlineContent)
}

private fun buildInline(inlines: List<Inline>, linkColor: Color, codeBackground: Color): Pair<AnnotatedString, List<Inline.Icon>> {
    val icons = mutableListOf<Inline.Icon>()
    val text = buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is Inline.Text -> withStyle(spanStyle(inline.style, linkColor, codeBackground)) { append(inline.text) }
                is Inline.Icon -> {
                    appendInlineContent("icon${icons.size}", "□")
                    icons += inline
                }
                Inline.LineBreak -> append('\n')
            }
        }
    }
    return text to icons
}

private fun spanStyle(s: MarkdownParser.Style, linkColor: Color, codeBackground: Color): SpanStyle {
    val decorations = listOfNotNull(
        TextDecoration.Underline.takeIf { s.underline },
        TextDecoration.LineThrough.takeIf { s.strike },
    )
    return SpanStyle(
        color = s.color?.let { CssColors.computeCssColor(it) } ?: if (s.link != null) linkColor else Color.Unspecified,
        fontWeight = if (s.bold) FontWeight.Bold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        fontFamily = if (s.code) FontFamily.Monospace else null,
        background = if (s.code) codeBackground else Color.Unspecified,
        fontSize = when {
            s.code -> 12.sp
            s.small -> 0.83.em
            s.big -> 1.2.em
            else -> TextUnit.Unspecified
        },
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}
