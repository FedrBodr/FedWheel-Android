package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceSessionStoreTest {
    private var counter = 0
    private val store = VoiceSessionStore(maxSessions = 3) { "s${counter++}" }

    @Test
    fun startCreatesPendingSession() {
        val id = store.start()
        assertThat(id).isEqualTo("s0")
        assertThat(store.result(id)).isEqualTo(VoiceResult(VoiceResult.PENDING))
    }

    @Test
    fun chunksAreConcatenatedInSeqOrder() {
        val id = store.start()
        assertThat(store.addChunk(id, 2, byteArrayOf(5, 6))).isTrue()
        assertThat(store.addChunk(id, 0, byteArrayOf(1, 2))).isTrue()
        assertThat(store.addChunk(id, 1, byteArrayOf(3, 4))).isTrue()
        assertThat(store.end(id)).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6))
    }

    @Test
    fun duplicateSeqIsToleratedAndKeptOnce() {
        val id = store.start()
        store.addChunk(id, 0, byteArrayOf(1))
        assertThat(store.addChunk(id, 0, byteArrayOf(1))).isTrue()
        store.addChunk(id, 1, byteArrayOf(2))
        assertThat(store.end(id)).isEqualTo(byteArrayOf(1, 2))
    }

    @Test
    fun unknownIdIsRejected() {
        assertThat(store.addChunk("nope", 0, byteArrayOf(1))).isFalse()
        assertThat(store.end("nope")).isNull()
        assertThat(store.result("nope")).isNull()
    }

    @Test
    fun onlyLatestSessionsAreKept() {
        val first = store.start()
        store.start(); store.start(); val last = store.start()
        assertThat(store.result(first)).isNull()
        assertThat(store.result(last)).isNotNull()
    }

    @Test
    fun resultAndPendingActionAreStored() {
        val id = store.start()
        store.setResult(id, VoiceResult(VoiceResult.DONE, "текст", "ответ", true), pendingActionId = "a1")
        assertThat(store.result(id)).isEqualTo(VoiceResult(VoiceResult.DONE, "текст", "ответ", true))
        assertThat(store.pendingAction(id)).isEqualTo("a1")
    }

    @Test
    fun sixtySecondRecordingStreamedInChunksIsAssembledWhole() {
        // 60 s of 16 kHz Ogg/Opus from the watch is ~220 KB: 73+ chunks of 3000 bytes sent while recording
        val id = store.start()
        val audio = ByteArray(300_000) { (it % 251).toByte() }
        for (seq in 0 until 100) {
            assertThat(store.addChunk(id, seq, audio.copyOfRange(seq * 3000, (seq + 1) * 3000))).isTrue()
        }
        assertThat(store.end(id)).isEqualTo(audio)
    }
}
