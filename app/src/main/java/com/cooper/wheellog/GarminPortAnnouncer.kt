package com.cooper.wheellog

/**
 * Decides when to (re)send the local web server port to the Garmin watch app.
 * The port is announced once per phone↔watch connection by upstream, so a watch app opened later
 * never learns it. We re-announce while the watch has not polled the server for [silenceMs].
 */
class GarminPortAnnouncer(private val silenceMs: Long = 5_000) {
    private var lastWatchRequestMs: Long? = null

    fun onWatchRequest(nowMs: Long) {
        lastWatchRequestMs = nowMs
    }

    fun shouldAnnounce(nowMs: Long): Boolean {
        val last = lastWatchRequestMs ?: return true
        return nowMs - last >= silenceMs
    }
}
