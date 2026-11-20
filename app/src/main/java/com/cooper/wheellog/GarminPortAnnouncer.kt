package com.cooper.wheellog

/**
 * Decides when to (re)send the local web server port to a Garmin watch app.
 * The port is announced once per phone↔watch connection by upstream, so a watch app opened later
 * never learns it. We re-announce while the watch has not polled the server for [silenceMs],
 * backing off exponentially ([initialBackoffMs] doubling up to [maxBackoffMs]) so a closed watch app
 * does not get a message every 2 s forever. Any watch request or [reset] restarts the schedule.
 *
 * Called from the HTTP server threads and the main thread, hence synchronized.
 */
class GarminPortAnnouncer(
    private val silenceMs: Long = 5_000,
    private val initialBackoffMs: Long = 2_000,
    private val maxBackoffMs: Long = 60_000,
) {
    private var lastWatchRequestMs: Long? = null
    private var nextAnnounceAtMs: Long = Long.MIN_VALUE
    private var backoffMs: Long = initialBackoffMs

    @Synchronized
    fun onWatchRequest(nowMs: Long) {
        lastWatchRequestMs = nowMs
        backoffMs = initialBackoffMs
        nextAnnounceAtMs = Long.MIN_VALUE
    }

    @Synchronized
    fun shouldAnnounce(nowMs: Long): Boolean {
        if (nowMs < nextAnnounceAtMs) return false
        val last = lastWatchRequestMs ?: return true
        return nowMs - last >= silenceMs
    }

    @Synchronized
    fun onAnnounced(nowMs: Long) {
        nextAnnounceAtMs = nowMs + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
    }

    /** Restart the schedule, e.g. on device reconnect. */
    @Synchronized
    fun reset() {
        lastWatchRequestMs = null
        backoffMs = initialBackoffMs
        nextAnnounceAtMs = Long.MIN_VALUE
    }
}
