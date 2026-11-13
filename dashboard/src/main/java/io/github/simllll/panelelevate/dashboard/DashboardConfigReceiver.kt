package io.github.simllll.panelelevate.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Sets the Home Assistant connection (and UI options) for the dashboard. Only reachable via adb: the manifest
 * protects this receiver with android.permission.DUMP, which the adb shell holds but normal apps
 * cannot get. Every extra is optional; an empty string clears the value.
 *
 * ```
 * adb shell am broadcast -n io.github.simllll.panelelevate/.dashboard.DashboardConfigReceiver \
 *     -a io.github.simllll.panelelevate.dashboard.CONFIGURE \
 *     --es url http://192.168.1.20:8123 --es token 'eyJ…' --es dashboard wohnbereich-walldisplay
 * ```
 *
 * Optional: `--es scale 0.75` (see [DashboardSettings.scale]),
 * `--es browser_id 'walldisplay Eingang'` (see [DashboardSettings.browserId]),
 * `--es return_home_after_s 60` (see [DashboardSettings.returnHomeAfterS]; 0 = off),
 * `--es lock_entity binary_sensor.x`, `--es lock_video /local/x.mp4`, `--es lock_sleep_after_s 10`
 * (guest lock, see [DashboardSettings.guestLock]; `none` turns the lock or its video off).
 *
 * A running dashboard (same process) picks the change up immediately.
 */
class DashboardConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIGURE) return
        val editor = DashboardSettings(context).prefs.edit()
        val changed = mutableListOf<String>()
        for (key in DashboardSettings.ALL_KEYS) {
            if (!intent.hasExtra(key)) continue
            val value = intent.getStringExtra(key)?.trim().orEmpty()
            if (value.isEmpty()) editor.remove(key) else editor.putString(key, value)
            changed += key
        }
        editor.commit()
        val result = if (changed.isEmpty()) "nothing changed (extras: ${DashboardSettings.ALL_KEYS.joinToString()})" else "updated: ${changed.joinToString()}"
        Log.i("DashboardConfig", result)
        setResultData(result)
    }

    companion object {
        const val ACTION_CONFIGURE = "io.github.simllll.panelelevate.dashboard.CONFIGURE"
    }
}
