package com.stretter.shellyelevateservice.dashboard.actions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stretter.shellyelevateservice.dashboard.popup.PopupHost

/**
 * In-dashboard navigation for `navigate` actions.
 *
 * - `#hash` only changes the location hash (bubble-card pop-ups listen to it); `#` alone clears it.
 * - `/dashboard/view[#hash]` switches the view (by `path` or index); other dashboards are not
 *   loaded, the view part is still applied to the current one.
 */
@Stable
class Navigator(private val popups: PopupHost) {
    /** Current view `path` or index as string; null = first view. */
    var viewPath: String? by mutableStateOf(null)
        private set

    /** Incremented by `browser_mod.refresh`; the dashboard reloads its config. */
    var reloadCounter: Int by mutableIntStateOf(0)
        private set

    fun navigate(path: String, @Suppress("UNUSED_PARAMETER") replace: Boolean = false) {
        val hashIndex = path.indexOf('#')
        val pathPart = if (hashIndex >= 0) path.substring(0, hashIndex) else path
        val hash = if (hashIndex >= 0) path.substring(hashIndex) else null
        if (pathPart.isNotEmpty()) {
            val segments = pathPart.trim('/').split('/').filter { it.isNotEmpty() }
            viewPath = if (segments.size >= 2) segments[1] else null
        }
        popups.setHash(hash?.takeIf { it.length > 1 })
    }

    fun reload() {
        reloadCounter++
    }
}
