package io.github.simllll.panelelevate.dashboard.actions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.simllll.panelelevate.dashboard.popup.PopupHost

/**
 * In-dashboard navigation for `navigate` actions, view swipes and the back key.
 *
 * - `#hash` only changes the location hash (bubble-card pop-ups listen to it); `#` alone clears it.
 * - `/dashboard/view[#hash]` switches the view (by `path` or index); other dashboards are not
 *   loaded, the view part is still applied to the current one.
 * - View changes are kept in a history (`navigation_replace` replaces the current entry) that the
 *   back key walks back ([back]).
 */
@Stable
class Navigator(private val popups: PopupHost) {
    /** Current view `path` or index as string; null = first view. */
    var viewPath: String? by mutableStateOf(null)
        private set

    /**
     * Direction of the last view change for its animation: +1 the new view comes from the right
     * (swipe to the next view), -1 from the left, 0 no animation (buttons, back, return home).
     */
    var transition: Int by mutableIntStateOf(0)
        private set

    /** Incremented by `browser_mod.refresh`; the dashboard reloads its config. */
    var reloadCounter: Int by mutableIntStateOf(0)
        private set

    /** Incremented on every touch; restarts the return-home timer. */
    var interactions: Int by mutableIntStateOf(0)
        private set

    private val history = ArrayList<String?>()

    fun navigate(path: String, replace: Boolean = false) {
        val hashIndex = path.indexOf('#')
        val pathPart = if (hashIndex >= 0) path.substring(0, hashIndex) else path
        val hash = if (hashIndex >= 0) path.substring(hashIndex) else null
        if (pathPart.isNotEmpty()) {
            val segments = pathPart.trim('/').split('/').filter { it.isNotEmpty() }
            showView(if (segments.size >= 2) segments[1] else null, replace, 0)
        }
        popups.setHash(hash?.takeIf { it.length > 1 })
    }

    /** A swipe to the view [path] ([direction] see [transition]). */
    fun swipeTo(path: String?, direction: Int) = showView(path, replace = false, direction)

    /** Back key: the previous view of the history, else the first view. False if already there. */
    fun back(): Boolean {
        if (history.isNotEmpty()) {
            transition = 0
            viewPath = history.removeAt(history.size - 1)
            return true
        }
        return home()
    }

    /** Goes to the first view and forgets the history. False if it is already shown. */
    fun home(): Boolean {
        history.clear()
        if (viewPath == null) return false
        transition = 0
        viewPath = null
        return true
    }

    fun onUserInteraction() {
        interactions++
    }

    fun reload() {
        reloadCounter++
    }

    private fun showView(path: String?, replace: Boolean, direction: Int) {
        if (path == viewPath) return
        if (!replace) {
            history += viewPath
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        transition = direction
        viewPath = path
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
