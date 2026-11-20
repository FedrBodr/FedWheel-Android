package com.cooper.wheellog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GarminPortAnnouncerTest {
    @Test
    fun announcesWhenWatchNeverPolled() {
        assertThat(GarminPortAnnouncer().shouldAnnounce(0)).isTrue()
    }

    @Test
    fun quietWhileWatchPolls() {
        val announcer = GarminPortAnnouncer()
        announcer.onWatchRequest(10_000)
        assertThat(announcer.shouldAnnounce(12_000)).isFalse()
    }

    @Test
    fun announcesAgainAfterSilence() {
        val announcer = GarminPortAnnouncer()
        announcer.onWatchRequest(10_000)
        assertThat(announcer.shouldAnnounce(15_000)).isTrue()
    }
}

class GarminPortAnnouncerBackoffTest {
    @Test
    fun backoffDoublesAfterEachAnnounce() {
        val a = GarminPortAnnouncer()
        var t = 0L
        val expectedGaps = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        assertThat(a.shouldAnnounce(t)).isTrue()
        a.onAnnounced(t)
        for (gap in expectedGaps) {
            assertThat(a.shouldAnnounce(t + gap - 1)).isFalse()
            assertThat(a.shouldAnnounce(t + gap)).isTrue()
            t += gap
            a.onAnnounced(t)
        }
    }

    @Test
    fun watchRequestResetsBackoff() {
        val a = GarminPortAnnouncer()
        a.onAnnounced(0); a.onAnnounced(2_000); a.onAnnounced(6_000) // next wait would be 8 s
        a.onWatchRequest(7_000)
        // quiet while the watch polls, then back to the initial 2 s schedule
        assertThat(a.shouldAnnounce(11_999)).isFalse()
        assertThat(a.shouldAnnounce(12_000)).isTrue()
        a.onAnnounced(12_000)
        assertThat(a.shouldAnnounce(14_000)).isTrue()
    }

    @Test
    fun resetRestartsSchedule() {
        val a = GarminPortAnnouncer()
        a.onAnnounced(0); a.onAnnounced(2_000); a.onAnnounced(6_000)
        a.reset()
        assertThat(a.shouldAnnounce(6_001)).isTrue()
        a.onAnnounced(6_001)
        assertThat(a.shouldAnnounce(8_001)).isTrue()
    }
}
