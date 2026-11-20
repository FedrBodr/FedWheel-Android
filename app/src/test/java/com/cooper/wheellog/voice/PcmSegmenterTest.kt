package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sin

class PcmSegmenterTest {
    private val rate = 16_000

    /** Tone ("speech") with silent gaps: spans = list of (ms, loud). */
    private fun pcm(vararg spans: Pair<Int, Boolean>): ShortArray {
        val out = ArrayList<Short>()
        for ((ms, loud) in spans) {
            repeat(rate * ms / 1000) { i ->
                out += if (loud) (8000 * sin(i * 2 * Math.PI * 220 / rate)).toInt().toShort() else 0
            }
        }
        return out.toShortArray()
    }

    private fun ms(samples: Int) = samples * 1000L / rate

    @Test
    fun shortAudioIsOneSegment() {
        val p = pcm(15_000 to true)
        assertThat(PcmSegmenter.split(p, rate)).containsExactly(0 until p.size)
    }

    @Test
    fun emptyAudioHasNoSegments() {
        assertThat(PcmSegmenter.split(ShortArray(0), rate)).isEmpty()
    }

    @Test
    fun longAudioSplitsAtTheQuietestPointWithinTheLimit() {
        // speech 14 s, pause 600 ms, speech 14 s -> split inside the pause
        val p = pcm(14_000 to true, 600 to false, 14_000 to true)
        val segs = PcmSegmenter.split(p, rate, maxMs = 20_000)
        assertThat(segs).hasSize(2)
        val cut = segs[0].last + 1
        assertThat(ms(cut)).isAtLeast(14_000)
        assertThat(ms(cut)).isAtMost(14_600)
        assertThat(segs[1].first).isEqualTo(cut)
        assertThat(segs[1].last).isEqualTo(p.size - 1)
    }

    @Test
    fun segmentsAreContiguousCoverEverythingAndRespectTheLimit() {
        val p = pcm(
            9_000 to true, 400 to false, 8_000 to true, 500 to false, 12_000 to true,
            300 to false, 10_000 to true, 700 to false, 16_000 to true,
        )
        val segs = PcmSegmenter.split(p, rate, maxMs = 20_000)
        assertThat(segs.first().first).isEqualTo(0)
        assertThat(segs.last().last).isEqualTo(p.size - 1)
        segs.zipWithNext().forEach { (a, b) -> assertThat(b.first).isEqualTo(a.last + 1) }
        segs.forEach { assertThat(ms(it.last - it.first + 1)).isAtMost(20_000) }
        // every cut lands in a pause, never in the middle of a word
        segs.dropLast(1).forEach { s -> assertThat(p[s.last].toInt()).isEqualTo(0) }
    }

    @Test
    fun continuousSpeechIsStillCutAtTheLimit() {
        val p = pcm(45_000 to true)
        val segs = PcmSegmenter.split(p, rate, maxMs = 20_000)
        assertThat(segs.size).isAtLeast(3)
        segs.forEach { assertThat(ms(it.last - it.first + 1)).isAtMost(20_000) }
        assertThat(segs.sumOf { it.last - it.first + 1 }).isEqualTo(p.size)
    }

    @Test
    fun noTinyTrailingSegment() {
        // 20.5 s of speech with a pause at 10 s: better two halves than 20 s + 0.5 s
        val p = pcm(10_000 to true, 400 to false, 10_100 to true)
        val segs = PcmSegmenter.split(p, rate, maxMs = 20_000)
        assertThat(segs).hasSize(2)
        segs.forEach { assertThat(ms(it.last - it.first + 1)).isAtLeast(5_000) }
    }

    @Test
    fun joinsTranscriptsInOrderSkippingBlanks() {
        assertThat(PcmSegmenter.join(listOf(" Привет ", "", "как дела", "  "))).isEqualTo("Привет как дела")
    }
}
