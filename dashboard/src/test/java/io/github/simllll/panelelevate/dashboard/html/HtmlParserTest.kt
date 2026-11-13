package io.github.simllll.panelelevate.dashboard.html

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlParserTest {
    /** Content of the "Befehl gesendet" popup (automation "Staubsauger-Rückmeldung am Walldisplay"). */
    private val vacuumPopup =
        "<div style=\"text-align:center;padding:24px 8px 32px\"><ha-icon icon=\"mdi:robot-vacuum\" " +
            "style=\"--mdc-icon-size:120px;color:var(--primary-color)\"></ha-icon><div style=\"font-size:34px;" +
            "font-weight:600;margin-top:16px\">Befehl gesendet</div><div style=\"font-size:22px;opacity:.8;" +
            "margin-top:8px\">Der Roboter startet gleich …</div></div>"

    @Test
    fun `popup html from the automations`() {
        val root = HtmlParser.parse(vacuumPopup).single() as HtmlElement
        assertEquals("div", root.tag)
        assertEquals(mapOf("text-align" to "center", "padding" to "24px 8px 32px"), root.style)
        assertEquals(3, root.children.size)
        val icon = root.children[0] as HtmlElement
        assertEquals("ha-icon", icon.tag)
        assertEquals("mdi:robot-vacuum", icon.attributes["icon"])
        assertEquals("120px", icon.style["--mdc-icon-size"])
        assertEquals("var(--primary-color)", icon.style["color"])
        val title = root.children[1] as HtmlElement
        assertEquals(listOf(HtmlText("Befehl gesendet")), title.children)
        assertEquals("600", title.style["font-weight"])
        assertEquals(".8", (root.children[2] as HtmlElement).style["opacity"])
    }

    @Test
    fun `line breaks inside attributes from yaml folding`() {
        val html = "<div style=\"text-align:center\"><ha-icon\n icon=\"mdi:weather-night\" style=\"--mdc-icon-size:120px\"></ha-icon><div\n style=\"font-size:34px\">Gute Nacht 💤</div></div>"
        val root = HtmlParser.parse(html).single() as HtmlElement
        assertEquals("mdi:weather-night", (root.children[0] as HtmlElement).attributes["icon"])
        assertEquals(listOf(HtmlText("Gute Nacht 💤")), (root.children[1] as HtmlElement).children)
    }

    @Test
    fun `void, self-closing, bare and unquoted attributes`() {
        val nodes = HtmlParser.parse("a<br>b<br/><img src=x.png alt='Bild' hidden><span class=big>c</span>")
        assertEquals(HtmlText("a"), nodes[0])
        assertEquals("br", (nodes[1] as HtmlElement).tag)
        assertEquals(HtmlText("b"), nodes[2])
        assertEquals("br", (nodes[3] as HtmlElement).tag)
        val img = nodes[4] as HtmlElement
        assertEquals(mapOf("src" to "x.png", "alt" to "Bild", "hidden" to ""), img.attributes)
        assertEquals("big", (nodes[5] as HtmlElement).attributes["class"])
    }

    @Test
    fun `unbalanced markup is repaired`() {
        // Unclosed <b> closes at the end of its parent, stray </i> is ignored.
        val nodes = HtmlParser.parse("<div><b>fett</div></i>danach")
        val div = nodes[0] as HtmlElement
        val b = div.children.single() as HtmlElement
        assertEquals("b", b.tag)
        assertEquals(listOf(HtmlText("fett")), b.children)
        assertEquals(HtmlText("danach"), nodes[1])
    }

    @Test
    fun `comments, scripts and entities`() {
        val nodes = HtmlParser.parse("<!-- x --><script>alert('<b>')</script>Fr&uuml;h &amp; sp&#228;t &#x2713; &nbsp;&unknown;")
        assertEquals(listOf(HtmlText("Früh & spät ✓  &unknown;")), nodes)
    }

    @Test
    fun `a lone less-than is text`() {
        assertEquals(listOf(HtmlText("1 < 2")), HtmlParser.parse("1 < 2"))
    }

    @Test
    fun `style declarations keep parentheses together`() {
        assertEquals(
            mapOf("color" to "var(--success-color, #43a047)", "background" to "rgb(1, 2, 3)", "font-size" to "12px"),
            HtmlParser.parseStyle("color: var(--success-color, #43a047); background:rgb(1, 2, 3) !important ;font-size:12px;"),
        )
        assertEquals(emptyMap<String, String>(), HtmlParser.parseStyle(" ; nonsense ;"))
    }

    @Test
    fun `css lengths`() {
        val ctx = Css.Context(fontSize = 20f, viewportWidth = 1280f, viewportHeight = 800f, percentBase = 1280f)
        assertEquals(12f, Css.length("12px")!!, 0f)
        assertEquals(0f, Css.length("0")!!, 0f)
        assertEquals(30f, Css.length("1.5em", ctx)!!, 0.001f)
        assertEquals(32f, Css.length("2rem", ctx)!!, 0f)
        assertEquals(896f, Css.length("70%", ctx)!!, 0.001f)
        assertEquals(400f, Css.length("calc(100vh - 400px)", ctx)!!, 0.001f)
        assertEquals(456f, Css.length("calc(56px + calc(100vh - 400px))", ctx)!!, 0.001f)
        assertEquals(64f, Css.length("calc( 2 * (16px + 16px) )", ctx)!!, 0.001f)
        assertNull(Css.length("auto"))
        assertNull(Css.length("50%"))
        assertNull(Css.length("12furlongs"))
    }

    @Test
    fun `box shorthand and font helpers`() {
        assertArrayEquals(floatArrayOf(24f, 8f, 32f, 8f), Css.box("24px 8px 32px")!!, 0f)
        assertArrayEquals(floatArrayOf(4f, 4f, 4f, 4f), Css.box("4px")!!, 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 1f, 2f), Css.box("1px 2px")!!, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), Css.box("0 auto")!!, 0f)
        assertEquals(600, Css.fontWeight("600"))
        assertEquals(700, Css.fontWeight("bold"))
        assertEquals(24f, Css.fontSize("x-large", Css.Context())!!, 0f)
        assertEquals(10f, Css.fontSize("50%", Css.Context(fontSize = 20f))!!, 0f)
    }
}
