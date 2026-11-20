package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VoicePipelineTest {
    private val store = VoiceSessionStore(idGenerator = { "sid" })
    private val spoken = mutableListOf<String>()
    private var askedWith: Pair<String, String>? = null
    private var sttText = "включи свет"
    private var agentPending: PendingAction? = null

    private val pipeline = VoicePipeline(
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
    )

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
}
