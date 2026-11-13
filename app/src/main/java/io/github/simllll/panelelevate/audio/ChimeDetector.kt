package io.github.simllll.panelelevate.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Detects a three-note chime (default: the BTicino indoor unit's floor-call ringtone,
 * 1760 Hz → 2090 Hz → 1395 Hz) in 16 kHz mono audio, block by block (~64 ms).
 *
 * Per block, Goertzel filters measure each note at its frequency ±[Config.tolerance] (bins every
 * 1 %, the strongest counts) and a few reference frequencies between the notes. A note is "on" when
 * it is above [Config.minLevel], at least [Config.minRatio] times the strongest reference bin and
 * the clearly strongest note. The chime matches when note A, B and C follow each other, each for
 * at least [Config.minBlocks] blocks, with at most [Config.maxGapMs] between them; then the
 * detector is quiet for [Config.cooldownMs]. Pure computation, unit-testable.
 */
class ChimeDetector(private val config: Config = Config()) {
    data class Config(
        val sampleRate: Int = 16_000,
        val notes: List<Double> = listOf(1760.0, 2090.0, 1395.0),
        val references: List<Double> = listOf(1200.0, 1600.0, 1900.0, 2400.0),
        val tolerance: Double = 0.03,
        val minLevel: Double = 100.0,
        val minRatio: Double = 8.0,
        val minBlocks: Int = 2,
        val maxGapMs: Long = 600,
        val cooldownMs: Long = 5_000,
    ) {
        fun withThresholds(minLevel: Double, minRatio: Double) = copy(minLevel = minLevel, minRatio = minRatio)
    }

    /** A detected chime with the peak level and ratio of each note. */
    data class Hit(val timeMs: Long, val levels: List<Double>, val ratios: List<Double>)

    /** Measurement of one block (for logging/debugging). */
    data class Block(val note: Int, val levels: List<Double>, val reference: Double)

    // Bins every 1 % across ±tolerance: a 64 ms block resolves ~16 Hz, so a tone between two
    // bins loses at most a few dB.
    private val noteBins: List<List<Double>> = config.notes.map { f ->
        val steps = Math.round(config.tolerance / BIN_STEP).toInt()
        (-steps..steps).map { f * (1 + it * BIN_STEP) }
    }

    private var index = -1          // note currently being counted, -1 = none
    private var count = 0           // blocks of that note so far
    private var lastProgressMs = 0L
    private var cooldownUntil = 0L
    private val peakLevels = DoubleArray(config.notes.size)
    private val peakRatios = DoubleArray(config.notes.size)

    var lastBlock: Block? = null
        private set

    fun reset() {
        index = -1
        count = 0
        peakLevels.fill(0.0)
        peakRatios.fill(0.0)
    }

    /** Feeds one block ([count] samples of [samples]) ending at [timeMs]; returns a hit or null. */
    fun process(samples: ShortArray, count: Int = samples.size, timeMs: Long): Hit? {
        val levels = noteBins.map { bins -> bins.maxOf { goertzel(samples, count, it) } }
        val reference = config.references.maxOf { goertzel(samples, count, it) }.coerceAtLeast(1e-3)
        val note = dominant(levels, reference)
        lastBlock = Block(note, levels, reference)

        if (timeMs < cooldownUntil) return null
        if (index >= 0 && timeMs - lastProgressMs > config.maxGapMs) reset()
        if (note < 0) return null

        val last = config.notes.size - 1
        when {
            note == index -> this.count++
            index >= 0 && note == index + 1 && this.count >= config.minBlocks -> {
                index = note
                this.count = 1
            }
            note == 0 -> {
                reset()
                index = 0
                this.count = 1
            }
            else -> {
                reset()
                return null
            }
        }
        track(note, levels[note], levels[note] / reference, timeMs)
        return if (index == last && this.count >= config.minBlocks) hit(timeMs) else null
    }

    private fun track(note: Int, level: Double, ratio: Double, timeMs: Long) {
        lastProgressMs = timeMs
        if (level > peakLevels[note]) peakLevels[note] = level
        if (ratio > peakRatios[note]) peakRatios[note] = ratio
    }

    private fun hit(timeMs: Long): Hit {
        val hit = Hit(timeMs, peakLevels.toList(), peakRatios.toList())
        reset()
        cooldownUntil = timeMs + config.cooldownMs
        return hit
    }

    /** Index of the note that clearly dominates this block, or -1. */
    private fun dominant(levels: List<Double>, reference: Double): Int {
        var best = -1
        for (i in levels.indices) if (best < 0 || levels[i] > levels[best]) best = i
        if (best < 0) return -1
        val level = levels[best]
        if (level < config.minLevel || level < config.minRatio * reference) return -1
        // The other notes must be clearly weaker (no broadband click covering all notes).
        for (i in levels.indices) if (i != best && levels[i] * 2 > level) return -1
        return best
    }

    /** Goertzel magnitude of [freq], normalised by the block length (a sine of amplitude A gives about A/2). */
    private fun goertzel(samples: ShortArray, count: Int, freq: Double): Double {
        val k = 2 * cos(2 * PI * freq / config.sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until count) {
            val s0 = samples[i] + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - k * s1 * s2
        return sqrt(power.coerceAtLeast(0.0)) / count
    }

    companion object {
        /** Block length: 1024 samples = 64 ms at 16 kHz (about 16 Hz frequency resolution). */
        const val BLOCK = 1024
        private const val BIN_STEP = 0.01
    }
}
