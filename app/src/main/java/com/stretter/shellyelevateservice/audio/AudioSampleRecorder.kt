package com.stretter.shellyelevateservice.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records microphone samples to WAV (16 kHz, mono, 16 bit) for tuning the doorbell detector:
 * `adb shell am broadcast -n …/.audio.AudioSampleReceiver -a …RECORD_AUDIO_SAMPLE --ei seconds 60`.
 * Files go to the app's external files dir (`/sdcard/Android/data/<pkg>/files/audio/`, pullable
 * via adb without root); the RMS/peak of every second is logged (tag `AudioSample`).
 * Uses AudioRecord because the panel's real microphone path is only switched on by the audio
 * HAL for an app recording (tinycap from the shell only gets silence).
 */
object AudioSampleRecorder {
    private const val TAG = "AudioSample"
    const val SAMPLE_RATE = 16_000
    private val busy = AtomicBoolean(false)

    /** Source names accepted by the receiver; `all` records each one in turn. */
    val SOURCES = linkedMapOf(
        "mic" to MediaRecorder.AudioSource.MIC,
        "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
        "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
    )

    /** Starts recording on a background thread; false if a recording is already running. */
    fun start(context: Context, seconds: Int, source: String): Boolean {
        val names = if (source == "all") SOURCES.keys.toList() else listOf(source)
        if (names.any { it !in SOURCES }) throw IllegalArgumentException("unknown source $source (${SOURCES.keys.joinToString()}, all)")
        if (!busy.compareAndSet(false, true)) return false
        val dir = File(context.getExternalFilesDir(null), "audio").apply { mkdirs() }
        Thread({
            // The doorbell listener lets go of the microphone while we record.
            MicOwner.acquireForSampler()
            try {
                for (name in names) record(dir, name, SOURCES.getValue(name), seconds)
            } finally {
                MicOwner.releaseFromSampler()
                busy.set(false)
            }
        }, "audio-sample").start()
        return true
    }

    fun outputDir(context: Context): File = File(context.getExternalFilesDir(null), "audio")

    @SuppressLint("MissingPermission")
    private fun record(dir: File, name: String, source: Int, seconds: Int) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE * 2))
        } catch (e: Exception) {
            Log.w(TAG, "$name: cannot create AudioRecord: ${e.message}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "$name: AudioRecord not initialized (RECORD_AUDIO granted? source supported?)")
            recorder.release()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "sample-$stamp-$name.wav")
        Log.i(TAG, "$name: recording ${seconds}s to ${file.absolutePath}")
        val second = ShortArray(SAMPLE_RATE)
        var total = 0L
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(Wav.header(0))
                recorder.startRecording()
                for (s in 0 until seconds) {
                    var filled = 0
                    while (filled < second.size) {
                        val n = recorder.read(second, filled, second.size - filled)
                        if (n <= 0) throw IllegalStateException("read returned $n")
                        filled += n
                    }
                    out.write(Wav.pcmBytes(second))
                    total += second.size
                    val stats = Levels.of(second)
                    Log.i(TAG, "$name t=${s + 1}s rms=%.1f (%.1f dBFS) peak=%d".format(Locale.US, stats.rms, stats.dbfs, stats.peak))
                }
                recorder.stop()
                out.seek(0)
                out.write(Wav.header(total * 2))
            }
            Log.i(TAG, "$name: done, ${file.length()} bytes")
        } catch (e: Exception) {
            Log.w(TAG, "$name: recording failed: ${e.message}")
        } finally {
            recorder.release()
        }
    }
}

/** Level statistics of a block of 16-bit samples. */
data class Levels(val rms: Double, val peak: Int) {
    val dbfs: Double get() = if (rms <= 0.0) -120.0 else 20 * log10(rms / 32768.0)

    companion object {
        fun of(samples: ShortArray, count: Int = samples.size): Levels {
            var sum = 0.0
            var peak = 0
            for (i in 0 until count) {
                val v = samples[i].toInt()
                sum += v.toDouble() * v
                peak = maxOf(peak, abs(v))
            }
            return Levels(if (count == 0) 0.0 else sqrt(sum / count), peak)
        }
    }
}

/** Canonical 44-byte PCM WAV header for 16 kHz mono 16 bit. */
object Wav {
    fun header(dataBytes: Long, sampleRate: Int = AudioSampleRecorder.SAMPLE_RATE, channels: Int = 1, bits: Int = 16): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
            .putInt(sampleRate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    fun pcmBytes(samples: ShortArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putShort(s)
        return b.array()
    }
}
