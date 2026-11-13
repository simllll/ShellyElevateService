package com.stretter.shellyelevateservice.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts a microphone sample recording ([AudioSampleRecorder]). adb only: protected by
 * android.permission.DUMP like AdbSettingReceiver.
 *
 * ```
 * adb shell pm grant com.stretter.shellyelevateservice android.permission.RECORD_AUDIO
 * adb shell am broadcast -n com.stretter.shellyelevateservice/.audio.AudioSampleReceiver \
 *     -a com.stretter.shellyelevateservice.RECORD_AUDIO_SAMPLE --ei seconds 60 [--es source mic]
 * adb pull /sdcard/Android/data/com.stretter.shellyelevateservice/files/audio/
 * ```
 * Sources: `mic` (default), `voice_recognition`, `unprocessed`, `camcorder`, `all` (each in turn).
 */
class AudioSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val seconds = intent.getIntExtra("seconds", 30).coerceIn(1, 600)
        val source = intent.getStringExtra("source") ?: "mic"
        val result = try {
            if (AudioSampleRecorder.start(context.applicationContext, seconds, source)) {
                "recording ${seconds}s ($source) to ${AudioSampleRecorder.outputDir(context).absolutePath}"
            } else "a recording is already running"
        } catch (e: IllegalArgumentException) {
            e.message ?: "invalid request"
        }
        Log.i("AudioSample", result)
        setResultData(result)
    }

    companion object {
        const val ACTION = "com.stretter.shellyelevateservice.RECORD_AUDIO_SAMPLE"
    }
}
