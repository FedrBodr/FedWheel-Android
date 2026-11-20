package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PcmUtilsTest {
    @Test
    fun downmixAveragesChannels() {
        val stereo = shortArrayOf(100, 300, -100, -300)
        assertThat(PcmUtils.downmix(stereo, 2).toList()).containsExactly(200.toShort(), (-200).toShort()).inOrder()
    }

    @Test
    fun monoDownmixIsIdentity() {
        val mono = shortArrayOf(1, 2, 3)
        assertThat(PcmUtils.downmix(mono, 1).toList()).isEqualTo(mono.toList())
    }

    @Test
    fun resample48kTo16kKeepsDuration() {
        val src = ShortArray(48_000) { 1000 }
        val out = PcmUtils.resample(src, 48_000, 16_000)
        assertThat(out.size).isEqualTo(16_000)
        assertThat(out.all { it == 1000.toShort() }).isTrue()
    }

    @Test
    fun sameRateIsIdentity() {
        val src = shortArrayOf(1, 2, 3)
        assertThat(PcmUtils.resample(src, 16_000, 16_000).toList()).isEqualTo(src.toList())
    }

    @Test
    fun toLittleEndianBytes() {
        assertThat(PcmUtils.toBytesLE(shortArrayOf(0x0102, -1))).isEqualTo(byteArrayOf(0x02, 0x01, -1, -1))
    }
}

class TranscriptPickerTest {
    @Test
    fun finalResultWins() {
        assertThat(TranscriptPicker.pick("привет", listOf("при"))).isEqualTo("привет")
    }

    @Test
    fun emptyFinalUsesLastPartial() {
        assertThat(TranscriptPicker.pick("", listOf("Какая", "Какая сегодня погода"))).isEqualTo("Какая сегодня погода")
    }

    @Test
    fun repeatedTailIsDropped() {
        // seen on Android System Intelligence with EXTRA_AUDIO_SOURCE
        val partials = listOf("Какая", "Какая сегодня погода в Москве", "Какая сегодня погода в Москве, сегодня погода в Москве")
        assertThat(TranscriptPicker.pick(null, partials)).isEqualTo("Какая сегодня погода в Москве")
    }

    @Test
    fun genuineContinuationIsKept() {
        val partials = listOf("Включи", "Включи свет, пожалуйста")
        assertThat(TranscriptPicker.pick(null, partials)).isEqualTo("Включи свет, пожалуйста")
    }

    @Test
    fun nothingRecognised() {
        assertThat(TranscriptPicker.pick(null, emptyList())).isEmpty()
    }
}
