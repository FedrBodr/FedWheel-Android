package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.Executor

class ReplySpeakerTest {
    private val fallback = mutableListOf<String>()
    private val playedMp3 = mutableListOf<String>()
    private val playedRaw = mutableListOf<String>()
    private var mp3Starts = true
    private var mp3Failure: (() -> Unit)? = null
    private var rawAvailable = setOf<String>()
    private var tts: (String) -> ByteArray = { it.toByteArray() }

    private val audio = object : ReplyAudio {
        override fun playMp3(bytes: ByteArray, onFailed: () -> Unit): Boolean {
            if (!mp3Starts) return false
            playedMp3 += String(bytes)
            mp3Failure = onFailed
            return true
        }
        override fun playRaw(name: String, onFailed: () -> Unit): Boolean {
            if (name !in rawAvailable) return false
            playedRaw += name
            return true
        }
    }

    private val speaker = ReplySpeaker(
        tts = object : VoiceTts { override fun tts(text: String) = this@ReplySpeakerTest.tts(text) },
        audio = audio,
        fallback = object : Speaker { override fun speak(text: String) { fallback += text } },
        executor = Executor { it.run() },
    )

    @Test
    fun playsServerAudioWhenAvailable() {
        speaker.speak("Привет")
        assertThat(playedMp3).containsExactly("Привет")
        assertThat(fallback).isEmpty()
    }

    @Test
    fun fallsBackToTtsWhenFetchFails() {
        tts = { throw VoiceException("Синтез недоступен (503)") }
        speaker.speak("Привет")
        assertThat(playedMp3).isEmpty()
        assertThat(fallback).containsExactly("Привет")
    }

    @Test
    fun fallsBackWhenPlaybackCannotStart() {
        mp3Starts = false
        speaker.speak("Привет")
        assertThat(fallback).containsExactly("Привет")
    }

    @Test
    fun fallsBackWhenPlaybackFailsLater() {
        speaker.speak("Привет")
        mp3Failure!!.invoke()
        assertThat(fallback).containsExactly("Привет")
    }

    @Test
    fun dropsStaleReplyWhenNewerArrivesDuringFetch() {
        var nested = false
        tts = { text ->
            if (text == "старый" && !nested) { nested = true; speaker.speak("новый") }
            text.toByteArray()
        }
        speaker.speak("старый")
        assertThat(playedMp3).containsExactly("новый")
        assertThat(fallback).isEmpty()
    }

    @Test
    fun staleFailureDoesNotFallBack() {
        tts = { text ->
            if (text == "старый") { speaker.speak("новый"); throw VoiceException("Сервер недоступен") }
            text.toByteArray()
        }
        speaker.speak("старый")
        assertThat(playedMp3).containsExactly("новый")
        assertThat(fallback).isEmpty()
    }

    @Test
    fun lateFailureOfOlderPlaybackIsIgnored() {
        speaker.speak("первый")
        val olderFailure = mp3Failure!!
        speaker.speak("второй")
        olderFailure()
        assertThat(fallback).isEmpty()
    }

    @Test
    fun errorPlaysBundledClip() {
        rawAvailable = setOf("fv_server_unavailable")
        speaker.speakError("Сервер недоступен")
        assertThat(playedRaw).containsExactly("fv_server_unavailable")
        assertThat(fallback).isEmpty()
    }

    @Test
    fun errorWithMissingClipFallsBackToTts() {
        speaker.speakError("Неверный токен сервера")
        assertThat(fallback).containsExactly("Неверный токен сервера")
    }

    @Test
    fun unmappedErrorIsSpokenByTts() {
        rawAvailable = setOf("fv_server_unavailable")
        speaker.speakError("Внутренняя ошибка")
        assertThat(playedRaw).isEmpty()
        assertThat(fallback).containsExactly("Внутренняя ошибка")
    }

    @Test
    fun mapsErrorMessagesToRawNames() {
        assertThat(ReplySpeaker.rawFor("Сервер недоступен")).isEqualTo("fv_server_unavailable")
        assertThat(ReplySpeaker.rawFor("Неверный токен сервера")).isEqualTo("fv_bad_token")
        assertThat(ReplySpeaker.rawFor("Не расслышала, повторите")).isEqualTo("fv_not_heard")
        assertThat(ReplySpeaker.rawFor("Не удалось прочитать аудио")).isEqualTo("fv_audio_error")
        assertThat(ReplySpeaker.rawFor("Ошибка сервера (500)")).isNull()
    }
}
