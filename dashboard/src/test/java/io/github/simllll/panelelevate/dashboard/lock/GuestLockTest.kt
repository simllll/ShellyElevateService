package io.github.simllll.panelelevate.dashboard.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLockTest {
    @Test
    fun `unlocked touches pass and no overlay`() {
        val lock = GuestLock(10_000)
        assertFalse(lock.onTouch(1_000))
        assertFalse(lock.overlay)
        assertNull(lock.dueAt())
        assertFalse(lock.onTimer(100_000))
    }

    @Test
    fun `locked touch is swallowed, shows the overlay and every touch restarts the timer`() {
        val lock = GuestLock(10_000)
        lock.updateLocked(true)
        assertFalse("no overlay before a touch", lock.overlay)
        assertTrue(lock.onTouch(1_000))
        assertTrue(lock.overlay)
        assertEquals(11_000L, lock.dueAt())
        assertTrue(lock.onTouch(5_000))
        assertEquals(15_000L, lock.dueAt())
        assertFalse(lock.onTimer(14_999))
        assertTrue(lock.overlay)
    }

    @Test
    fun `timer hides the overlay and requests sleep once, the lock stays`() {
        val lock = GuestLock(10_000)
        lock.updateLocked(true)
        lock.onTouch(0)
        assertTrue(lock.onTimer(10_000))
        assertFalse(lock.overlay)
        assertTrue(lock.locked)
        assertFalse("only once", lock.onTimer(20_000))
        // After waking up the next touch shows the overlay again.
        assertTrue(lock.onTouch(30_000))
        assertTrue(lock.overlay)
    }

    @Test
    fun `unlocking hides the overlay at once and stops the timer`() {
        val lock = GuestLock(10_000)
        lock.updateLocked(true)
        lock.onTouch(0)
        lock.updateLocked(false)
        assertFalse(lock.overlay)
        assertNull(lock.dueAt())
        assertFalse(lock.onTimer(10_000))
        assertFalse(lock.onTouch(11_000))
    }
}
