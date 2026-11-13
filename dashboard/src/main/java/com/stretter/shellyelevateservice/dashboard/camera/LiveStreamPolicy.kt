package com.stretter.shellyelevateservice.dashboard.camera

/** What a live camera view shows. */
sealed interface LiveState {
    /** Negotiating (attempt 1-based); the still image is shown. */
    data class Connecting(val attempt: Int) : LiveState

    /** Frames are arriving. */
    data object Playing : LiveState

    /** The last attempt failed; the next one starts after [delayMs]. */
    data class Retrying(val attempt: Int, val delayMs: Long, val reason: String) : LiveState

    /** Gave up (or WebRTC is not possible for this camera): refreshing stills instead. */
    data class Failed(val reason: String) : LiveState
}

/**
 * Few attempts with growing pauses: every offer is a real call to some cameras (the BTicino
 * intercom answers each one, and too many in a row make it reboot), so a broken stream must
 * not turn into a reconnect loop.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    private val delaysMs: List<Long> = listOf(5_000L, 20_000L),
) {
    /** Pause before attempt [nextAttempt] (2-based), or null when no attempt is left. */
    fun delayBefore(nextAttempt: Int): Long? {
        if (nextAttempt > maxAttempts || nextAttempt < 2) return null
        return delaysMs.getOrElse(nextAttempt - 2) { delaysMs.lastOrNull() ?: 0L }
    }
}

/**
 * Keeps offers for one camera at least [minIntervalMs] apart, across all views of the process
 * (opening and closing a popup quickly must not fire a burst of calls).
 */
class OfferThrottle(private val minIntervalMs: Long = 5_000L) {
    private val lastStart = HashMap<String, Long>()

    /** How long to wait before an offer for [entityId] may start at [nowMs]. */
    @Synchronized
    fun waitBefore(entityId: String, nowMs: Long): Long {
        val last = lastStart[entityId] ?: return 0L
        return (last + minIntervalMs - nowMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun onStarted(entityId: String, nowMs: Long) {
        lastStart[entityId] = nowMs
    }
}

/** Errors that no retry can fix: report and fall back to stills at once. */
fun isPermanentWebRtcError(code: String): Boolean = code in PERMANENT_CODES

// WebSocket API errors (unknown entity, schema, permissions) and bticino_intercom's "no bridge".
private val PERMANENT_CODES = setOf(
    "no_device",
    "not_found",
    "home_assistant_error",
    "invalid_format",
    "unauthorized",
)
