package io.github.simllll.panelelevate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSampleTest {
    @Test
    fun `wav header for 16 kHz mono 16 bit`() {
        val h = Wav.header(32000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("data", String(h, 36, 4))
        val bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 32000, bb.getInt(4))
        assertEquals(1, bb.getShort(20).toInt())      // PCM
        assertEquals(1, bb.getShort(22).toInt())      // mono
        assertEquals(16000, bb.getInt(24))            // sample rate
        assertEquals(32000, bb.getInt(28))            // byte rate
        assertEquals(16, bb.getShort(34).toInt())     // bits
        assertEquals(32000, bb.getInt(40))
    }

    @Test
    fun `pcm is little endian and levels are computed`() {
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0xFF.toByte()), Wav.pcmBytes(shortArrayOf(0x0201, -1)))
        val l = Levels.of(shortArrayOf(1000, -1000, 1000, -1000))
        assertEquals(1000.0, l.rms, 0.001)
        assertEquals(1000, l.peak)
        assertEquals(-30.3, l.dbfs, 0.1)
        assertEquals(-120.0, Levels.of(ShortArray(4)).dbfs, 0.0)
    }
}
