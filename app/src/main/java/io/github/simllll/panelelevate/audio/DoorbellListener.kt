package io.github.simllll.panelelevate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens to the microphone continuously (16 kHz mono, 64 ms blocks) and runs a [ChimeDetector]
 * (setting `doorbellDetectEnabled`). The microphone is only held while enabled; a sample
 * recording ([AudioSampleRecorder]) takes it over and the listener resumes afterwards
 * ([MicOwner]).
 */
class DoorbellListener(
    private val config: ChimeDetector.Config,
    private val onHit: (ChimeDetector.Hit) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::loop, "doorbell-listener").apply { isDaemon = true; start() }
        Log.i(TAG, "started (notes ${config.notes}, min ratio ${config.minRatio}, min level ${config.minLevel})")
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(2_000)
        thread = null
        Log.i(TAG, "stopped")
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val detector = ChimeDetector(config)
        val block = ShortArray(ChimeDetector.BLOCK)
        while (running.get()) {
            if (MicOwner.samplerWants.get()) {
                // A sample recording runs: stay off the microphone until it is done.
                sleep(500)
                continue
            }
            val recorder = open()
            if (recorder == null) {
                sleep(30_000)
                continue
            }
            MicOwner.detectorHolding.set(true)
            try {
                recorder.startRecording()
                detector.reset()
                while (running.get() && !MicOwner.samplerWants.get()) {
                    var filled = 0
                    while (filled < block.size) {
                        val n = recorder.read(block, filled, block.size - filled)
                        if (n <= 0) throw IllegalStateException("read returned $n")
                        filled += n
                    }
                    val hit = detector.process(block, timeMs = SystemClock.elapsedRealtime()) ?: continue
                    Log.i(TAG, "chime detected: levels ${hit.levels.fmt()} ratios ${hit.ratios.fmt()}")
                    try {
                        onHit(hit)
                    } catch (e: Exception) {
                        Log.w(TAG, "hit handler failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "microphone error: ${e.message}; retrying")
                sleep(5_000)
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                MicOwner.detectorHolding.set(false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, config.sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, ChimeDetector.BLOCK * 2 * 4))
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                Log.w(TAG, "AudioRecord not initialized (RECORD_AUDIO granted?)")
                r.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot open the microphone: ${e.message}")
            null
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun List<Double>.fmt() = joinToString(prefix = "[", postfix = "]") { "%.0f".format(Locale.US, it) }

    private companion object {
        const val TAG = "DoorbellDetector"
    }
}

/** Who has the microphone: a sample recording has priority over the doorbell listener. */
object MicOwner {
    /** Set while [AudioSampleRecorder] records (or is about to). */
    val samplerWants = AtomicBoolean(false)

    /** Set while [DoorbellListener] holds an AudioRecord. */
    val detectorHolding = AtomicBoolean(false)

    /** Takes the microphone over from the listener (waits up to [timeoutMs] for it to let go). */
    fun acquireForSampler(timeoutMs: Long = 2_000) {
        samplerWants.set(true)
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (detectorHolding.get() && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
    }

    fun releaseFromSampler() = samplerWants.set(false)
}
