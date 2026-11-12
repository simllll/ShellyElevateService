package com.stretter.shellyelevateservice.dashboard.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ButtonFeedbackTest {
    @Test
    fun labelsAndIcons() {
        assertEquals("Taste 2", ButtonFeedback.label(2, "single"))
        assertEquals("Taste 3 · 2×", ButtonFeedback.label(3, "double"))
        assertEquals("Taste 1 · lang", ButtonFeedback.label(1, "long"))
        assertEquals("Taste 4", ButtonFeedback.label(4, "unknown"))
        assertEquals("Taste 4", ButtonFeedback.label(4, ButtonFeedbackContract.EVENT_DOWN))
        assertEquals("mdi:gesture-tap", ButtonFeedback.icon("single"))
        assertEquals("mdi:gesture-double-tap", ButtonFeedback.icon("double"))
        assertEquals("mdi:gesture-tap-hold", ButtonFeedback.icon("long"))
    }

    @Test
    fun ignoresUnknownButtonsAndTracksPopupGeneration() {
        val popups = PopupHost()
        val feedback = ButtonFeedback(popups)
        feedback.show(0, "single")
        assertNull(feedback.current)
        feedback.show(5, "single")
        assertNull(feedback.current)
        feedback.show(2, "long")
        assertEquals(2, feedback.current?.button)
        assertEquals(popups.browserModPopupGeneration, feedback.current?.popupGeneration)
        feedback.dismiss()
        assertNull(feedback.current)
    }

    @Test
    fun downShowsAtOnceAndTheActionRelabelsIt() {
        val popups = PopupHost()
        val feedback = ButtonFeedback(popups)
        feedback.show(2, ButtonFeedbackContract.EVENT_DOWN)
        val down = feedback.current!!
        assertEquals("Taste 2", down.label)
        feedback.show(2, "long")
        assertEquals("Taste 2 · lang", feedback.current?.label)
        assertEquals(down.popupGeneration, feedback.current?.popupGeneration)
        assertNotEquals(down.id, feedback.current?.id)
        // Another button replaces the pill.
        feedback.show(3, ButtonFeedbackContract.EVENT_DOWN)
        assertEquals(3, feedback.current?.button)
    }

    @Test
    fun aPopupBetweenDownAndActionKeepsThePillHidden() {
        val popups = PopupHost()
        val feedback = ButtonFeedback(popups)
        feedback.show(1, ButtonFeedbackContract.EVENT_DOWN)
        val generation = feedback.current!!.popupGeneration
        popups.showBrowserModPopup(kotlinx.serialization.json.JsonObject(mapOf("title" to kotlinx.serialization.json.JsonPrimitive("x"))))
        feedback.show(1, "single")
        assertEquals(generation, feedback.current?.popupGeneration)
        assertNotEquals(popups.browserModPopupGeneration, feedback.current?.popupGeneration)
    }
}
