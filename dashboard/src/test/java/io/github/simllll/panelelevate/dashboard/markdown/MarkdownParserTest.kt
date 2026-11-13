package io.github.simllll.panelelevate.dashboard.markdown

import io.github.simllll.panelelevate.dashboard.markdown.MarkdownParser.Block
import io.github.simllll.panelelevate.dashboard.markdown.MarkdownParser.Inline
import io.github.simllll.panelelevate.dashboard.markdown.MarkdownParser.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `notification markdown from the wall display`() {
        val md = "**🔌 Gerät antwortet nicht** · _vor 5 Stunden_\n\n- Schlafzimmer Fenster\n- Plant Sensor A1 (Batteriegerät)"
        val blocks = MarkdownParser.parse(md)
        assertEquals(2, blocks.size)
        val paragraph = blocks[0] as Block.Paragraph
        assertEquals(
            listOf(
                Inline.Text("🔌 Gerät antwortet nicht", Style(bold = true)),
                Inline.Text(" · "),
                Inline.Text("vor 5 Stunden", Style(italic = true)),
            ),
            paragraph.inlines,
        )
        val list = blocks[1] as Block.ListBlock
        assertEquals(2, list.items.size)
        assertEquals(listOf(Inline.Text("Plant Sensor A1 (Batteriegerät)")), (list.items[1].single() as Block.Paragraph).inlines)
    }

    @Test
    fun `headings with leading spaces and bold content`() {
        val blocks = MarkdownParser.parse(" # **Wohnung Staubsauger ready machen**\n\nText")
        val heading = blocks[0] as Block.Heading
        assertEquals(1, heading.level)
        assertEquals(listOf(Inline.Text("Wohnung Staubsauger ready machen", Style(bold = true))), heading.inlines)
    }

    @Test
    fun `single newlines are line breaks`() {
        val p = MarkdownParser.parse("a\nb").single() as Block.Paragraph
        assertEquals(listOf(Inline.Text("a"), Inline.LineBreak, Inline.Text("b")), p.inlines)
    }

    @Test
    fun `ha-icon, center and inline html`() {
        val blocks = MarkdownParser.parse("<center><ha-icon icon=\"mdi:moon-full\" style=\"color: red\"></ha-icon> <b>Licht</b> aus</center>")
        val centered = blocks.single() as Block.Centered
        val inlines = (centered.blocks.single() as Block.Paragraph).inlines
        assertEquals(Inline.Icon("mdi:moon-full", "red"), inlines[0])
        assertTrue(inlines.contains(Inline.Text("Licht", Style(bold = true))))
        assertTrue(inlines.contains(Inline.Text(" aus")))
    }

    @Test
    fun `nested and ordered lists`() {
        val list = MarkdownParser.parse("1. eins\n2. zwei\n   - a\n   - b").single() as Block.ListBlock
        assertTrue(list.ordered)
        assertEquals(2, list.items.size)
        val nested = list.items[1].last() as Block.ListBlock
        assertEquals(2, nested.items.size)
    }

    @Test
    fun `unmatched markers stay literal`() {
        val p = MarkdownParser.parse("2 * 3 = 6").single() as Block.Paragraph
        assertEquals("2 * 3 = 6", p.inlines.joinToString("") { (it as Inline.Text).text })
    }

    @Test
    fun `entities, code and links`() {
        val p = MarkdownParser.parse("a &amp; b `x` [HA](https://home-assistant.io)").single() as Block.Paragraph
        assertEquals(Inline.Text("a & b "), p.inlines[0])
        assertEquals(Inline.Text("x", Style(code = true)), p.inlines[1])
        assertEquals(Inline.Text("HA", Style(link = "https://home-assistant.io")), p.inlines.last())
    }

    @Test
    fun `rules and quotes`() {
        val blocks = MarkdownParser.parse("> zitat\n\n---\n\nende")
        assertTrue(blocks[0] is Block.Quote)
        assertEquals(Block.Rule, blocks[1])
        assertTrue(blocks[2] is Block.Paragraph)
    }
}
