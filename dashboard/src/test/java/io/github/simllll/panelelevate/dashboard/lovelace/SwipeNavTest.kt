package io.github.simllll.panelelevate.dashboard.lovelace

import io.github.simllll.panelelevate.dashboard.actions.Navigator
import io.github.simllll.panelelevate.dashboard.json.HaJson
import io.github.simllll.panelelevate.dashboard.json.objects
import io.github.simllll.panelelevate.dashboard.popup.PopupHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeNavTest {
    private fun dashboard(json: String): JsonObject = HaJson.parseToJsonElement(json).jsonObject

    // The entrance dashboard: home without path, five views with paths.
    private val eingang = dashboard(
        """{"swipe_nav":{"wrap":true,"animate":"swipe","prevent_default":true,"swipe_amount":30},
           "views":[{"title":"Home"},{"path":"devices"},{"path":"cleaning","type":"masonry"},
                    {"path":"climate"},{"path":"weather"},{"path":"energy"}]}"""
    )

    @Test
    fun `parses the entrance config`() {
        val c = SwipeNavConfig.parse(eingang)!!
        assertTrue(c.wrap)
        assertEquals(SwipeNavConfig.Animate.SWIPE, c.animate)
        assertEquals(30f, c.swipeAmount)
    }

    @Test
    fun `defaults and off`() {
        assertNull(SwipeNavConfig.parse(dashboard("""{"views":[]}""")))
        assertNull(SwipeNavConfig.parse(dashboard("""{"swipe_nav":false}""")))
        val c = SwipeNavConfig.parse(dashboard("""{"swipe_nav":{"animate":"flip"}}"""))!!
        assertEquals(15f, c.swipeAmount)
        assertEquals(SwipeNavConfig.Animate.FADE, c.animate)
        assertTrue(c.wrap)
    }

    @Test
    fun `swipe targets wrap around`() {
        val views = eingang.objects("views")
        val c = SwipeNavConfig.parse(eingang)!!
        assertEquals(1, c.target(views, 0, 1))
        assertEquals(5, c.target(views, 0, -1))
        assertEquals(0, c.target(views, 5, 1))
        val noWrap = c.copy(wrap = false)
        assertNull(noWrap.target(views, 0, -1))
        assertNull(noWrap.target(views, 5, 1))
        assertEquals(4, noWrap.target(views, 5, -1))
    }

    @Test
    fun `subviews and hidden views are skipped`() {
        val views = dashboard(
            """{"views":[{"path":"a"},{"path":"sub","subview":true},{"path":"hidden","visible":false},{"path":"b"}]}"""
        ).objects("views")
        val c = SwipeNavConfig()
        assertEquals(3, c.target(views, 0, 1))
        assertEquals(0, c.target(views, 3, 1))
        assertNull(c.target(views, 1, 1))
        assertEquals(2, c.copy(skipHidden = false).target(views, 0, 1))
    }

    @Test
    fun `view index by path or index`() {
        val views = eingang.objects("views")
        assertEquals(0, SwipeNavConfig.viewIndexOrNull(views, null))
        assertEquals(0, SwipeNavConfig.viewIndexOrNull(views, "0"))
        assertEquals(3, SwipeNavConfig.viewIndexOrNull(views, "climate"))
        assertNull(SwipeNavConfig.viewIndexOrNull(views, "nope"))
        assertEquals("0", SwipeNavConfig.pathOf(views, 0))
        assertEquals("devices", SwipeNavConfig.pathOf(views, 1))
    }

    @Test
    fun `back walks the view history, then goes home`() {
        val nav = Navigator(PopupHost())
        nav.navigate("/dashboard-eingang/devices")
        nav.swipeTo("cleaning", 1)
        assertEquals(1, nav.transition)
        nav.navigate("/dashboard-eingang/climate", replace = true)
        assertEquals(0, nav.transition)
        assertTrue(nav.back())
        assertEquals("devices", nav.viewPath)
        assertTrue(nav.back())
        assertNull(nav.viewPath)
        assertFalse(nav.back())
    }

    @Test
    fun `home clears the history and hash-only navigation keeps the view`() {
        val nav = Navigator(PopupHost())
        nav.navigate("/dashboard-eingang/weather")
        nav.navigate("#popup")
        assertEquals("weather", nav.viewPath)
        assertTrue(nav.home())
        assertNull(nav.viewPath)
        assertFalse(nav.back())
    }
}
