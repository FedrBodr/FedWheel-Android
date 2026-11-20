package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoicePipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val history by lazy { VoiceHistory(File(tmp.root, "h.json")) }
    private val store = VoiceSessionStore(idGenerator = { "sid" })
    private val spoken = mutableListOf<String>()
    private var askedWith: Pair<String, String>? = null
    private var sttText = "включи свет"
    private var agentPending: PendingAction? = null

    private val pipeline by lazy { VoicePipeline(
        store = store,
        decoder = object : AudioDecoder {
            override fun decode(bytes: ByteArray) = Pcm(ShortArray(16_000), 16_000)
        },
        stt = object : SpeechToText {
            override suspend fun transcribe(pcm: ShortArray, sampleRate: Int) = sttText
        },
        agent = object : VoiceAgent {
            override fun ask(text: String, session: String): AskResult {
                askedWith = text to session
                return AskResult("Включаю", agentPending)
            }
            override fun confirm(actionId: String, confirm: Boolean) = if (confirm) "Сделано" else "Отменено"
        },
        speaker = object : Speaker {
            override fun speak(text: String) { spoken += text }
        },
        scope = CoroutineScope(Dispatchers.Unconfined),
        history = history,
    ) }

    @Test
    fun processesSessionToDone(): Unit = runBlocking {
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        assertThat(askedWith).isEqualTo("включи свет" to "sid")
        assertThat(store.result(id)).isEqualTo(VoiceResult(VoiceResult.DONE, "включи свет", "Включаю", false))
        assertThat(spoken).containsExactly("Включаю")
    }

    @Test
    fun pendingActionNeedsConfirmAndConfirmSpeaks(): Unit = runBlocking {
        agentPending = PendingAction("a1", "сводка")
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        assertThat(store.result(id)!!.needsConfirm).isTrue()
        val r = pipeline.onConfirm(id, true)
        assertThat(r).isEqualTo(VoiceResult(VoiceResult.DONE, "включи свет", "Сделано", false))
        assertThat(spoken).containsExactly("Включаю", "Сделано").inOrder()
    }

    @Test
    fun confirmWithoutPendingActionIsError() {
        val id = store.start()
        assertThat(pipeline.onConfirm(id, true).state).isEqualTo(VoiceResult.ERROR)
    }

    @Test
    fun emptyTranscriptIsError(): Unit = runBlocking {
        sttText = "  "
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        assertThat(store.result(id)!!.state).isEqualTo(VoiceResult.ERROR)
        assertThat(askedWith).isNull()
    }

    @Test
    fun historyRecordsDoneExchange(): Unit = runBlocking {
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        val e = history.entries.value.single()
        assertThat(e.sessionId).isEqualTo(id)
        assertThat(e.text).isEqualTo("включи свет")
        assertThat(e.reply).isEqualTo("Включаю")
        assertThat(e.state).isEqualTo(VoiceHistory.State.OK)
    }

    @Test
    fun historyRecordsConfirmAndCancel(): Unit = runBlocking {
        agentPending = PendingAction("a1", "сводка")
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        assertThat(history.entries.value.single().state).isEqualTo(VoiceHistory.State.AWAITING_CONFIRM)
        pipeline.onConfirm(id, false)
        val e = history.entries.value.single()
        assertThat(e.state).isEqualTo(VoiceHistory.State.CANCELLED)
        assertThat(e.reply).isEqualTo("Отменено")
        assertThat(e.text).isEqualTo("включи свет")
    }

    @Test
    fun historyRecordsConfirmed(): Unit = runBlocking {
        agentPending = PendingAction("a1", "сводка")
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        pipeline.onConfirm(id, true)
        assertThat(history.entries.value.single().state).isEqualTo(VoiceHistory.State.CONFIRMED)
    }

    @Test
    fun historyRecordsErrorWithRussianMessage(): Unit = runBlocking {
        sttText = "  "
        val id = store.start()
        pipeline.process(id, byteArrayOf(1))
        val e = history.entries.value.single()
        assertThat(e.state).isEqualTo(VoiceHistory.State.ERROR)
        assertThat(e.reply).isEqualTo("Не расслышал, повторите")
    }
}
