package com.stretter.shellyelevateservice.dashboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.stretter.shellyelevateservice.dashboard.icons.MdiIcons
import kotlin.system.exitProcess

/**
 * Native Home Assistant dashboard (runs in the `:dashboard` process). Fullscreen, immersive, dark.
 * Configure the connection with [DashboardConfigReceiver]; start with
 * `adb shell am start -n com.stretter.shellyelevateservice/.dashboard.DashboardActivity`.
 */
class DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRestart(applicationContext)
        MdiIcons.init(this)
        val runtime = DashboardRuntime.get(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Kiosk: back closes popups, then walks back the view history; it never leaves the dashboard.
            // Guest lock: back only closes popups (they stay usable); otherwise it shows the lock.
            override fun handleOnBackPressed() {
                if (runtime.guestLock.locked) {
                    if (!runtime.popups.isShowingPopup || !runtime.popups.handleBack()) {
                        runtime.guestLock.onTouch(System.currentTimeMillis())
                    }
                    return
                }
                if (!runtime.popups.handleBack()) runtime.navigator.back()
            }
        })
        val scale = runtime.settings.scale
        setContent {
            val density = LocalDensity.current
            val scaled = if (scale == null) density else Density(density.density * scale, density.fontScale)
            CompositionLocalProvider(LocalDensity provides scaled) {
                DashboardScreen(runtime)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        DashboardRuntime.get(this).browserMod.setVisible(true)
        DashboardRuntime.get(this).buttonFeedback.register(this)
    }

    override fun onStop() {
        super.onStop()
        DashboardRuntime.get(this).browserMod.setVisible(false)
        DashboardRuntime.get(this).buttonFeedback.unregister(this)
    }

    override fun onResume() {
        super.onResume()
        DashboardRuntime.get(this).hass.reconnectNow()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val TAG = "DashboardActivity"
        private const val CRASH_PREFS = "dashboard_crashes"

        /**
         * Restarts the dashboard 3 s after an uncaught exception (this process only hosts the
         * dashboard). Gives up after 3 crashes within 5 minutes to avoid a crash loop.
         * Replaces the app's CrashHandler in this process: that one restarts the service, which
         * lives in the main process and is unaffected by a dashboard crash.
         */
        private fun installCrashRestart(context: Context) {
            if (Thread.getDefaultUncaughtExceptionHandler() is RestartHandler) return
            Thread.setDefaultUncaughtExceptionHandler(RestartHandler(context))
        }
    }

    private class RestartHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                Log.e(TAG, "dashboard crashed", error)
                val prefs = context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val recent = (prefs.getString("times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
                    .filter { now - it < 5 * 60_000 } + now
                prefs.edit().putString("times", recent.joinToString(",")).commit()
                if (recent.size <= 3) {
                    val intent = Intent(context, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
                    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 3_000, pending)
                }
            } catch (_: Throwable) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}
