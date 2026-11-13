package io.github.simllll.panelelevate.dashboard.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IframeCardTest {
    @Test
    fun `aspect ratios parse like parseAspectRatio`() {
        assertEquals(1f, IframeCard.heightRatio("100%")!!, 1e-6f)
        assertEquals(0.5625f, IframeCard.heightRatio("56.25%")!!, 1e-6f)
        assertEquals(0.5625f, IframeCard.heightRatio("16:9")!!, 1e-6f)
        assertEquals(0.5625f, IframeCard.heightRatio("16x9")!!, 1e-6f)
        assertEquals(1f / 1.78f, IframeCard.heightRatio("1.78")!!, 1e-6f)
        assertNull(IframeCard.heightRatio("wide"))
        assertNull(IframeCard.heightRatio(""))
    }

    @Test
    fun `video urls are detected by extension`() {
        assertTrue(IframeCard.isVideoUrl("/local/screensaver.mp4"))
        assertTrue(IframeCard.isVideoUrl("http://ha:8123/local/a.WEBM?v=2"))
        assertFalse(IframeCard.isVideoUrl("https://www.example.com/"))
        assertFalse(IframeCard.isVideoUrl("/local/page.html"))
    }
}
