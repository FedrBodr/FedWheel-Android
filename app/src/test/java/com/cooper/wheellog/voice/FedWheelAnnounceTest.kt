package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FedWheelAnnounceTest {
    @Test
    fun wheelPortFirstThenVoicePortForFedWheelApp() {
        assertThat(FedVoiceAnnounce.fedWheelMessages(wheelPort = 41000, voicePort = 42000))
            .containsExactly(41000, listOf("fv", 42000)).inOrder()
    }

    @Test
    fun onlyWheelPortWhenVoiceServerIsDown() {
        assertThat(FedVoiceAnnounce.fedWheelMessages(wheelPort = 41000, voicePort = null))
            .containsExactly(41000)
    }
}
