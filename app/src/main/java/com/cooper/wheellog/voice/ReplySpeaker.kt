package com.cooper.wheellog.voice

import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Plays audio clips for [ReplySpeaker]; Android implementation is [AndroidReplyAudio]. */
interface ReplyAudio {
    /** Starts playing mp3 bytes; false if it could not start. [onFailed] is called if playback fails later. */
    fun playMp3(bytes: ByteArray, onFailed: () -> Unit): Boolean

    /** Plays a bundled res/raw clip by name; false if the clip is missing or could not start. */
    fun playRaw(name: String, onFailed: () -> Unit): Boolean
}

/**
 * Speaks assistant replies in the owner's chosen voice (server /v1/tts), falling back to the
 * phone's TTS on any failure. Only the newest reply is played: a reply still being fetched when
 * a newer one arrives is dropped. Errors use bundled clips (res/raw/fv_*.mp3) when present,
 * because the server is often the thing that is unreachable.
 */
class ReplySpeaker(
    private val tts: VoiceTts,
    private val audio: ReplyAudio,
    private val fallback: Speaker,
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) : Speaker {
    private val generation = AtomicLong()

    override fun speak(text: String) {
        if (text.isBlank()) return
        val gen = generation.incrementAndGet()
        executor.execute { speakReply(gen, text) }
    }

    override fun speakError(message: String) {
        if (message.isBlank()) return
        val gen = generation.incrementAndGet()
        val raw = rawFor(message)
        if (raw == null) {
            fallback(gen, message, "no bundled clip for this error")
            return
        }
        val started = try {
            audio.playRaw(raw) { fallback(gen, message, "bundled clip $raw failed to play") }
        } catch (e: Exception) {
            Timber.tag(VoicePipeline.TAG).w(e, "reply: raw $raw failed")
            false
        }
        if (started) log("reply: played bundled clip $raw for \"$message\"")
        else fallback(gen, message, "bundled clip $raw missing")
    }

    private fun speakReply(gen: Long, text: String) {
        val start = clock()
        val bytes = try {
            tts.tts(text)
        } catch (e: Exception) {
            log("reply: tts fetch failed after ${clock() - start} ms: ${e.message}")
            fallback(gen, text, "tts fetch failed (${e.message})")
            return
        }
        log("reply: tts fetch took ${clock() - start} ms, ${bytes.size} bytes")
        if (!isCurrent(gen)) {
            log("reply: dropped stale audio for \"$text\"")
            return
        }
        val started = try {
            audio.playMp3(bytes) { fallback(gen, text, "playback error") }
        } catch (e: Exception) {
            Timber.tag(VoicePipeline.TAG).w(e, "reply: playMp3 threw")
            false
        }
        if (started) log("reply: played server voice for \"$text\"")
        else fallback(gen, text, "playback could not start")
    }

    private fun fallback(gen: Long, text: String, reason: String) {
        if (!isCurrent(gen)) {
            log("reply: stale, skip fallback ($reason) for \"$text\"")
            return
        }
        log("reply: fallback to phone TTS: $reason")
        fallback.speak(text)
    }

    private fun isCurrent(gen: Long) = generation.get() == gen

    private fun log(msg: String) = Timber.tag(VoicePipeline.TAG).i(msg)

    companion object {
        private val RAW_BY_MESSAGE = mapOf(
            "Сервер недоступен" to "fv_server_unavailable",
            "Неверный токен сервера" to "fv_bad_token",
            "Не расслышала, повторите" to "fv_not_heard",
            "Не удалось прочитать аудио" to "fv_audio_error",
        )

        /** res/raw clip name for a VoiceException message, or null if there is none. */
        fun rawFor(message: String): String? = RAW_BY_MESSAGE[message.trim()]
    }
}
