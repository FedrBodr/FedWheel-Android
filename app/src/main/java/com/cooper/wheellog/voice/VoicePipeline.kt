package com.cooper.wheellog.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/** decode -> STT -> server ask -> TTS, plus confirmation. Every stage is logged with timings (tag FedVoice). */
class VoicePipeline(
    private val store: VoiceSessionStore,
    private val decoder: AudioDecoder,
    private val stt: SpeechToText,
    private val agent: VoiceAgent,
    private val speaker: Speaker,
    private val scope: CoroutineScope,
    private val history: VoiceHistory? = null,
) : VoiceBridgeHandler.Listener {

    override fun onSessionEnded(id: String, audio: ByteArray) {
        scope.launch { process(id, audio) }
    }

    suspend fun process(id: String, audio: ByteArray) {
        val t0 = System.currentTimeMillis()
        var text = ""
        try {
            log("[$id] end: ${audio.size} bytes of audio")
            val pcm = timed(id, "decode") { decoder.decode(audio) }
            log("[$id] pcm: ${pcm.samples.size} samples @ ${pcm.sampleRate} Hz (${pcm.samples.size * 1000L / pcm.sampleRate.coerceAtLeast(1)} ms)")
            if (pcm.samples.isEmpty()) throw VoiceException("Пустая запись")
            text = timed(id, "stt") { stt.transcribe(pcm.samples, pcm.sampleRate) }.trim()
            log("[$id] text: \"$text\"")
            if (text.isNotEmpty()) history(id, text = text, state = VoiceHistory.State.PROCESSING)
            if (text.isEmpty()) throw VoiceException("Не расслышала, повторите")
            val answer = timed(id, "ask") { agent.ask(text, id) }
            store.setResult(id, VoiceResult(VoiceResult.DONE, text, answer.reply, answer.pendingAction != null), answer.pendingAction?.id)
            history(id, text = text, reply = answer.reply,
                state = if (answer.pendingAction != null) VoiceHistory.State.AWAITING_CONFIRM else VoiceHistory.State.OK)
            log("[$id] done in ${System.currentTimeMillis() - t0} ms, needsConfirm=${answer.pendingAction != null}")
            speak(answer.reply)
        } catch (e: VoiceException) {
            fail(id, text, e.message ?: "Ошибка", e)
        } catch (e: Exception) {
            fail(id, text, "Внутренняя ошибка", e)
        }
    }

    override fun onConfirm(id: String, yes: Boolean): VoiceResult {
        val current = store.result(id) ?: return VoiceResult.error(VoiceBridgeHandler.UNKNOWN_SESSION)
        val actionId = store.pendingAction(id) ?: return VoiceResult.error("Нечего подтверждать", current.text)
        return try {
            val reply = timedBlocking(id, "confirm(yes=$yes)") { agent.confirm(actionId, yes) }
            val result = VoiceResult(VoiceResult.DONE, current.text, reply, false)
            store.setResult(id, result, null)
            history(id, reply = reply, state = if (yes) VoiceHistory.State.CONFIRMED else VoiceHistory.State.CANCELLED)
            speak(reply)
            result
        } catch (e: VoiceException) {
            Timber.tag(TAG).w(e, "[$id] confirm failed")
            history(id, reply = e.message ?: "Ошибка", state = VoiceHistory.State.ERROR)
            VoiceResult.error(e.message ?: "Ошибка", current.text)
        }
    }

    private fun fail(id: String, text: String, message: String, e: Exception) {
        Timber.tag(TAG).w(e, "[$id] failed: $message")
        store.setResult(id, VoiceResult.error(message, text))
        history(id, text = text, reply = message, state = VoiceHistory.State.ERROR)
        try {
            speaker.speakError(message)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "speakError failed")
        }
    }

    private fun history(id: String, text: String? = null, reply: String? = null, state: VoiceHistory.State) {
        try {
            history?.record(id, text, reply, state)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "[$id] history record failed")
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        try {
            speaker.speak(text)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "speak failed")
        }
    }

    private inline fun <T> timedBlocking(id: String, stage: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            log("[$id] $stage took ${System.currentTimeMillis() - start} ms")
        }
    }

    private suspend fun <T> timed(id: String, stage: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            log("[$id] $stage took ${System.currentTimeMillis() - start} ms")
        }
    }

    private fun log(msg: String) = Timber.tag(TAG).i(msg)

    companion object {
        const val TAG = "FedVoice"
    }
}
