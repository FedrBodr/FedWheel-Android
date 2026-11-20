package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FedVoiceAnnounceTest {
    @Test
    fun messageIsFvAndPort() {
        assertThat(FedVoiceAnnounce.message(41234)).containsExactly("fv", 41234).inOrder()
    }

    @Test
    fun appIdIsFedVoice() {
        assertThat(FedVoiceAnnounce.APP_ID).isEqualTo("28cd12eb-0005-43c3-ae75-2e90bc0e3f9f")
    }
}
