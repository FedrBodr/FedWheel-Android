package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun file() = File(tmp.root, "voice_history.json")
    private fun history(max: Int = 100) = VoiceHistory(file(), max) { now }

    @Test
    fun recordCreatesEntryAndUpdateBySessionKeepsTimeAndPosition() {
        val h = history()
        h.record("s1", text = "включи свет", state = VoiceHistory.State.PROCESSING)
        now = 2_000L
        h.record("s1", reply = "Включаю", state = VoiceHistory.State.OK)
        assertThat(h.entries.value).containsExactly(
            VoiceHistory.Entry("s1", 1_000L, "включи свет", "Включаю", VoiceHistory.State.OK)
        )
    }

    @Test
    fun updateWithoutTextKeepsPreviousText() {
        val h = history()
        h.record("s1", text = "удали файл", reply = "Удалить?", state = VoiceHistory.State.AWAITING_CONFIRM)
        h.record("s1", reply = "Удалено", state = VoiceHistory.State.CONFIRMED)
        val e = h.entries.value.single()
        assertThat(e.text).isEqualTo("удали файл")
        assertThat(e.reply).isEqualTo("Удалено")
        assertThat(e.state).isEqualTo(VoiceHistory.State.CONFIRMED)
    }

    @Test
    fun newestFirst() {
        val h = history()
        h.record("a", text = "раз", state = VoiceHistory.State.OK)
        now++
        h.record("b", text = "два", state = VoiceHistory.State.OK)
        now++
        h.record("a", reply = "ответ", state = VoiceHistory.State.OK)
        assertThat(h.entries.value.map { it.sessionId }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun cappedAtMax() {
        val h = history(max = 100)
        repeat(105) { h.record("s$it", text = "t$it", state = VoiceHistory.State.OK); now++ }
        val ids = h.entries.value.map { it.sessionId }
        assertThat(ids).hasSize(100)
        assertThat(ids.first()).isEqualTo("s104")
        assertThat(ids.last()).isEqualTo("s5")
    }

    @Test
    fun persistenceRoundTrip() {
        val h = history()
        h.record("a", text = "раз \"кавычки\"\nстрока", reply = "ответ", state = VoiceHistory.State.ERROR)
        now++
        h.record("b", text = "два", reply = "Сделать?", state = VoiceHistory.State.AWAITING_CONFIRM)
        val reloaded = history()
        assertThat(reloaded.entries.value).isEqualTo(h.entries.value)
        assertThat(reloaded.entries.value).hasSize(2)
    }

    @Test
    fun corruptFileStartsEmpty() {
        file().writeText("not json")
        assertThat(history().entries.value).isEmpty()
    }

    @Test
    fun clearEmptiesAndPersists() {
        val h = history()
        h.record("a", text = "раз", state = VoiceHistory.State.OK)
        h.clear()
        assertThat(h.entries.value).isEmpty()
        assertThat(history().entries.value).isEmpty()
    }
}
