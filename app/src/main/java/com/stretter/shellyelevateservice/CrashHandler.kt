package com.stretter.shellyelevateservice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.stretter.shellyelevateservice.helper.ServiceHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        // Own prefs file so the counters do not show up in GET /settings
        private const val PREFS_NAME = "crash_handler"
        private const val KEY_CRASH_COUNT = "crashCount"
        private const val BASE_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 5 * 60_000L
        // A process that ran this long before crashing counts as stable again
        private const val STABLE_RUNTIME_MS = 10 * 60_000L
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Log to file
            val logFile = File(context.filesDir, "crash_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            logFile.appendText("\n=== Crash @ $timestamp ===\n${Log.getStackTraceString(throwable)}\n")

            Log.e("CrashHandler", "App crashed", throwable)

            // Restart the service with exponential backoff (2 s, 4 s, 8 s, ... max 5 min)
            // so a crash during startup does not restart-loop every 2 seconds
            val delayMs = nextRestartDelayMs()
            Log.w("CrashHandler", "Restarting service in ${delayMs / 1000}s")
            val restartIntent = Intent(context, ShellyElevateService::class.java)
            val pendingIntent = PendingIntent.getForegroundService(
                context,
                0,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pendingIntent)
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error while handling crash", e)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    private fun nextRestartDelayMs(): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val runtimeMs = System.currentTimeMillis() - ShellyElevateApplication.getApplicationStartTime()
        val count = if (runtimeMs >= STABLE_RUNTIME_MS) 1 else prefs.getInt(KEY_CRASH_COUNT, 0) + 1
        // commit, not apply: the process is killed right after this
        prefs.edit().putInt(KEY_CRASH_COUNT, count).commit()

        val shift = (count - 1).coerceAtMost(20)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}
