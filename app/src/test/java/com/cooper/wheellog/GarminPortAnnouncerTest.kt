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
