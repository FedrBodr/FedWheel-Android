package com.cooper.wheellog.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks replies into the headphones with Android TTS (ru-RU). Takes transient may-duck audio focus,
 * so music from other apps keeps playing quieter, and releases it when the utterance is done.
 */
class VoiceSpeaker(context: Context, private val locale: Locale = Locale("ru", "RU")) : Speaker {
    private val focus = SpeechFocus(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    private val attributes = focus.attributes
    private val ids = AtomicInteger()
    private val activeUtterances = AtomicInteger()

    @Volatile private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Timber.tag(VoicePipeline.TAG).e("tts: init failed ($status)")
            return
        }
        val lang = tts.setLanguage(locale)
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
        })
        Timber.tag(VoicePipeline.TAG).i(
            "diag: tts engine=${tts.defaultEngine}, setLanguage($locale)=$lang (${langName(lang)}), " +
                "isLanguageAvailable=${langName(tts.isLanguageAvailable(locale))}, " +
                "ruVoices=${runCatching { tts.voices?.filter { it.locale.language == "ru" }?.map { "${it.name}${if (it.isNetworkConnectionRequired) "(net)" else ""}" } }.getOrNull()}"
        )
        ready = true
        synchronized(this) { pending?.also { pending = null } }?.let { speak(it) }
    }

    override fun speak(text: String) {
        if (!ready) {
            synchronized(this) { pending = text }
            Timber.tag(VoicePipeline.TAG).i("tts: not ready yet, queued")
            return
        }
        val id = "fv${ids.incrementAndGet()}"
        val granted = requestFocus()
        activeUtterances.incrementAndGet()
        Timber.tag(VoicePipeline.TAG).i("tts: speak $id (focus=${if (granted) "granted" else "denied"}): \"$text\"")
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
        if (result != TextToSpeech.SUCCESS) finished(id)
    }

    private fun finished(utteranceId: String?) {
        Timber.tag(VoicePipeline.TAG).i("tts: done $utteranceId")
        if (activeUtterances.decrementAndGet() <= 0) {
            activeUtterances.set(0)
            abandonFocus()
        }
    }

    private fun requestFocus() = focus.request()

    private fun abandonFocus() = focus.abandon()

    fun shutdown() {
        try { tts.stop(); tts.shutdown() } catch (_: Exception) {}
        abandonFocus()
    }

    private fun langName(code: Int) = when (code) {
        TextToSpeech.LANG_AVAILABLE -> "AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "COUNTRY_VAR_AVAILABLE"
        TextToSpeech.LANG_MISSING_DATA -> "MISSING_DATA"
        TextToSpeech.LANG_NOT_SUPPORTED -> "NOT_SUPPORTED"
        else -> code.toString()
    }
}
