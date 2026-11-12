package com.stretter.shellyelevateservice.dashboard.lock

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Broadcast contract dashboard → service (the opposite direction of `ButtonFeedbackContract`):
 * the dashboard sends [ACTION_SLEEP] restricted to our package; the service's not-exported
 * receiver puts the display to sleep like MQTT `sleep`.
 */
object GuestLockContract {
    const val ACTION_SLEEP = "com.stretter.shellyelevateservice.dashboard.REQUEST_SLEEP"
}

/** Lock settings (see `DashboardSettings`): entity null = lock off. */
data class GuestLockConfig(
    val entity: String? = DEFAULT_ENTITY,
    val video: String? = DEFAULT_VIDEO,
    val sleepAfterS: Int = DEFAULT_SLEEP_AFTER_S,
) {
    companion object {
        const val DEFAULT_ENTITY = "binary_sensor.lock_dashboards"
        const val DEFAULT_VIDEO = "/local/screensaver.mp4"
        const val DEFAULT_SLEEP_AFTER_S = 10
    }
}

/**
 * State of the guest lock. While [locked] (the lock entity is `on`) the dashboard looks normal,
 * but every touch on it is swallowed and shows the lock overlay ([overlay]); [sleepAfterMs] after
 * the last touch the overlay goes away and the display is put to sleep. Unlocking hides the
 * overlay at once. Pure state (Compose snapshot state, no Android types) so it is unit-testable;
 * the timer itself runs in the UI ([dueAt]/[onTimer]).
 */
@Stable
class GuestLock(var sleepAfterMs: Long = GuestLockConfig.DEFAULT_SLEEP_AFTER_S * 1000L) {
    var locked by mutableStateOf(false)
        private set

    var overlay by mutableStateOf(false)
        private set

    /** Time of the last touch while locked (restarts the sleep timer). */
    var lastTouchMs by mutableLongStateOf(0L)
        private set

    fun updateLocked(value: Boolean) {
        locked = value
        if (!value) overlay = false
    }

    /**
     * A touch (or the back key) reached the dashboard. Returns true when it must be swallowed:
     * then the overlay is shown and the timer restarts.
     */
    fun onTouch(nowMs: Long): Boolean {
        if (!locked) return false
        overlay = true
        lastTouchMs = nowMs
        return true
    }

    /** When the overlay should end (null while it is not shown). */
    fun dueAt(): Long? = if (overlay) lastTouchMs + sleepAfterMs else null

    /** Timer check; true = the overlay ended now and the display should go to sleep. */
    fun onTimer(nowMs: Long): Boolean {
        val due = dueAt() ?: return false
        if (nowMs < due) return false
        overlay = false
        return locked
    }
}
