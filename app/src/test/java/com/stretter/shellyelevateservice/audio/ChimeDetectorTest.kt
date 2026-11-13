package com.stretter.shellyelevateservice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The resources are excerpts of a recording on the NSPanel Pro (source MIC, 16 kHz) next to the
 * BTicino indoor unit: `chime.wav` = the floor-call ringtone (18.8–21.3 s), `noise.wav` = the
 * rest of the same ring situation without the chime (steps, door, voices; 8–18.8 s and 21.5–28 s).
 */
class ChimeDetectorTest {
    private fun wav(name: String): ShortArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("doorbell/$name")!!.readBytes()
        return pcm(bytes)
    }

    private fun pcm(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buf.remaining()).also { buf.get(it) }
    }

    private fun run(samples: ShortArray, detector: ChimeDetector = ChimeDetector()): List<ChimeDetector.Hit> {
        val hits = ArrayList<ChimeDetector.Hit>()
        val block = ShortArray(ChimeDetector.BLOCK)
        var pos = 0
        while (pos + block.size <= samples.size) {
            System.arraycopy(samples, pos, block, 0, block.size)
            pos += block.size
            detector.process(block, timeMs = pos * 1000L / 16_000)?.let { hits += it }
        }
        return hits
    }

    /** Sine tones: (frequency, ms) pairs, amplitude [amp], plus a little noise. */
    private fun tones(vararg parts: Pair<Double, Int>, amp: Double = 4000.0): ShortArray {
        val out = ArrayList<Short>()
        val rnd = java.util.Random(1)
        for ((f, ms) in parts) {
            val n = 16 * ms
            for (i in 0 until n) {
                val v = (if (f > 0) amp * sin(2 * PI * f * i / 16_000) else 0.0) + rnd.nextGaussian() * 20
                out += v.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out.toShortArray()
    }

    @Test
    fun `recorded chime is detected exactly once`() {
        val hits = run(wav("chime.wav"))
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertTrue("levels ${hit.levels}", hit.levels.all { it > 1000 })
        assertTrue("ratios ${hit.ratios}", hit.ratios.all { it > 20 })
    }

    @Test
    fun `steps, door and voices of the same recording give no hit`() {
        assertEquals(0, run(wav("noise.wav")).size)
    }

    @Test
    fun `chime within noise once, and a second ring after the cooldown again`() {
        val noise = wav("noise.wav")
        val chime = wav("chime.wav")
        val silence = ShortArray(16_000 * 6)
        assertEquals(2, run(noise + chime + silence + chime + noise).size)
        // Within the 5 s cooldown the second ring is not counted again.
        assertEquals(1, run(chime + chime).size)
    }

    @Test
    fun `synthetic chime matches, also detuned by 2 percent`() {
        assertEquals(1, run(tones(0.0 to 500, 1760.0 to 350, 2090.0 to 350, 1395.0 to 700, 0.0 to 500)).size)
        assertEquals(1, run(tones(0.0 to 500, 1795.0 to 350, 2132.0 to 350, 1423.0 to 700, 0.0 to 500)).size)
    }

    @Test
    fun `wrong order, single notes, a long sine and quiet tones do not match`() {
        assertEquals(0, run(tones(1395.0 to 700, 2090.0 to 350, 1760.0 to 350)).size)
        assertEquals(0, run(tones(1760.0 to 350, 1395.0 to 700)).size)
        assertEquals(0, run(tones(2090.0 to 350, 1395.0 to 700)).size)
        assertEquals(0, run(tones(1760.0 to 3000)).size)
        assertEquals(0, run(tones(1000.0 to 3000)).size)
        assertEquals(0, run(tones(1760.0 to 350, 2090.0 to 350, 1395.0 to 700, amp = 100.0)).size)
        // Notes too short (one block each)
        assertEquals(0, run(tones(1760.0 to 70, 2090.0 to 70, 1395.0 to 70)).size)
        // A pause longer than 0.6 s between the notes
        assertEquals(0, run(tones(1760.0 to 350, 0.0 to 900, 2090.0 to 350, 1395.0 to 700)).size)
    }

    /** Optional: the whole 60 s recording (environment variable `BELL_WAV=/path/bell_mic.wav`). */
    @Test
    fun `full recording has exactly one hit`() {
        val path = System.getenv("BELL_WAV")?.takeIf { it.isNotEmpty() } ?: return
        assertEquals(1, run(pcm(File(path).readBytes())).size)
    }
}
