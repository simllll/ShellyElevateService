package com.stretter.shellyelevateservice.dashboard

import android.content.Context
import android.content.SharedPreferences
import com.stretter.shellyelevateservice.dashboard.hass.HassConfig

/**
 * Persistent dashboard settings (SharedPreferences `dashboard`, private to the `:dashboard` process).
 * Written by [DashboardConfigReceiver], read by [DashboardRuntime].
 */
class DashboardSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val url: String? get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
    val token: String? get() = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Dashboard `url_path` (e.g. `wohnbereich-walldisplay`); null = default dashboard. */
    val dashboard: String? get() = prefs.getString(KEY_DASHBOARD, null)?.takeIf { it.isNotBlank() }

    /**
     * UI zoom: 1 CSS px of the web dashboard = [scale] dp. Null = 1 dp per px, which matches the
     * web layout on an mdpi 1280×800 screen. Use e.g. 0.75 on an hdpi screen to get the same layout.
     */
    val scale: Float? get() = prefs.getString(KEY_SCALE, null)?.toFloatOrNull()?.takeIf { it in 0.25f..4f }

    /**
     * browser_mod browser id this dashboard registers as (default: the id of the web dashboard it
     * replaces, so automations targeting it keep working). `none` disables browser_mod.
     */
    val browserId: String?
        get() = (prefs.getString(KEY_BROWSER_ID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BROWSER_ID)
            .takeUnless { it.equals("none", ignoreCase = true) }

    val hassConfig: HassConfig?
        get() {
            val u = url ?: return null
            val t = token ?: return null
            return HassConfig(u, t)
        }

    companion object {
        const val PREFS_NAME = "dashboard"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_DASHBOARD = "dashboard"
        const val KEY_SCALE = "scale"
        const val KEY_BROWSER_ID = "browser_id"
        const val DEFAULT_BROWSER_ID = "walldisplay Wohnbereich"
        val ALL_KEYS = listOf(KEY_URL, KEY_TOKEN, KEY_DASHBOARD, KEY_SCALE, KEY_BROWSER_ID)
    }
}
