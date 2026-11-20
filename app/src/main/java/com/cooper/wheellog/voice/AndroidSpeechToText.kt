package com.cooper.wheellog.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android SpeechRecognizer fed with PCM through EXTRA_AUDIO_SOURCE (API 33+).
 * Tries the on-device recognizer first, then falls back to the regular one with the same audio.
 */
class AndroidSpeechToText(
    private val context: Context,
    private val language: String = "ru-RU",
    /** Feed audio at real-time pace instead of as fast as the pipe allows. */
    var realtimeFeed: Boolean = false,
    var trailingSilenceMs: Int = 1_500,
) : SpeechToText {

    private class RecognizerError(val code: Int) : Exception("recognizer error $code (${errorName(code)})")

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw VoiceException("Нужен Android 13+")
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (onDevice) {
            try {
                val text = runRecognizer(onDevice = true, pcm, sampleRate)
                if (text.isNotBlank()) return text
                Timber.tag(VoicePipeline.TAG).w("stt: on-device returned empty text, falling back to regular recognizer")
            } catch (e: RecognizerError) {
                if (e.code == SpeechRecognizer.ERROR_NO_MATCH || e.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    throw VoiceException("Не расслышал, повторите", e)
                }
                Timber.tag(VoicePipeline.TAG).w("stt: on-device failed (${e.message}), falling back to regular recognizer")
            }
        } else {
            Timber.tag(VoicePipeline.TAG).i("stt: on-device recognizer unavailable, using regular recognizer")
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) throw VoiceException("Распознавание речи недоступно")
        return try {
            runRecognizer(onDevice = false, pcm, sampleRate)
        } catch (e: RecognizerError) {
            throw VoiceException(userMessage(e.code), e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun runRecognizer(onDevice: Boolean, pcm: ShortArray, sampleRate: Int): String {
        val kind = if (onDevice) "on-device" else "regular"
        val start = System.currentTimeMillis()
        val text = withTimeout(RECOGNITION_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { recognizeOnMain(onDevice, pcm, sampleRate) }
        }
        Timber.tag(VoicePipeline.TAG).i("stt: $kind recognizer ran in ${System.currentTimeMillis() - start} ms")
        return text
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognizeOnMain(onDevice: Boolean, pcm: ShortArray, sampleRate: Int): String =
        suspendCancellableCoroutine { cont ->
            val recognizer = if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            var finished = false
            val partials = mutableListOf<String>()
            fun cleanup() {
                if (finished) return
                finished = true
                try { recognizer.destroy() } catch (_: Exception) {}
                try { readSide.close() } catch (_: IOException) {}
                try { writeSide.close() } catch (_: IOException) {}
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    Timber.tag(VoicePipeline.TAG).d("stt: results ${results?.keySet()?.associateWith { results.get(it) }}")
                    val final = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    val text = TranscriptPicker.pick(final, partials)
                    if (final.isNullOrBlank() && text.isNotEmpty()) {
                        Timber.tag(VoicePipeline.TAG).i("stt: empty final result, using partials -> \"$text\"")
                    }
                    cleanup()
                    if (cont.isActive) cont.resume(text)
                }

                override fun onError(error: Int) {
                    Timber.tag(VoicePipeline.TAG).w("stt: ${if (onDevice) "on-device" else "regular"} error $error (${errorName(error)})")
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(RecognizerError(error))
                }

                override fun onReadyForSpeech(params: Bundle?) = Timber.tag(VoicePipeline.TAG).d("stt: ready")
                override fun onBeginningOfSpeech() = Timber.tag(VoicePipeline.TAG).d("stt: speech begins")
                override fun onEndOfSpeech() = Timber.tag(VoicePipeline.TAG).d("stt: speech ends")
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    Timber.tag(VoicePipeline.TAG).d("stt: partial $p")
                    if (!p.isNullOrBlank()) partials += p
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation {
                // Called from the timeout; recognizer must be touched on the main thread.
                android.os.Handler(android.os.Looper.getMainLooper()).post { cleanup() }
            }

            recognizer.startListening(buildIntent(readSide, sampleRate))

            // Feed the audio (plus trailing silence so end-of-speech is detected) from a background thread:
            // the pipe blocks when full.
            Thread({
                try {
                    FileOutputStream(writeSide.fileDescriptor).use { os ->
                        val bytes = PcmUtils.toBytesLE(pcm) + ByteArray(sampleRate * 2 * trailingSilenceMs / 1000)
                        if (realtimeFeed) {
                            val step = sampleRate * 2 / 10 // 100 ms
                            var off = 0
                            while (off < bytes.size) {
                                val n = minOf(step, bytes.size - off)
                                os.write(bytes, off, n)
                                off += n
                                Thread.sleep(100)
                            }
                        } else {
                            os.write(bytes)
                        }
                        os.flush()
                    }
                } catch (e: IOException) {
                    Timber.tag(VoicePipeline.TAG).d("stt: audio pipe closed early: ${e.message}")
                } finally {
                    try { writeSide.close() } catch (_: IOException) {}
                }
            }, "FedVoice-stt-feed").start()
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun buildIntent(audio: ParcelFileDescriptor?, sampleRate: Int) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (audio != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            }
        }

    /** Logs what recognizers exist and whether ru-RU is installed. Must be called on the main thread. */
    fun logDiagnostics() {
        val tag = VoicePipeline.TAG
        Timber.tag(tag).i(
            "diag: SDK=${Build.VERSION.SDK_INT}, recognitionAvailable=${SpeechRecognizer.isRecognitionAvailable(context)}, " +
                "onDeviceAvailable=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)}"
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        listOf(true, false).forEach { onDevice ->
            val kind = if (onDevice) "on-device" else "regular"
            if (onDevice && !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@forEach
            if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) return@forEach
            val recognizer = try {
                if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                Timber.tag(tag).w(e, "diag: cannot create $kind recognizer"); return@forEach
            }
            val executor = Executors.newSingleThreadExecutor()
            recognizer.checkRecognitionSupport(buildIntent(null, 16_000), executor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    val installed = support.installedOnDeviceLanguages
                    val supported = support.supportedOnDeviceLanguages
                    val pending = support.pendingOnDeviceLanguages
                    val online = support.onlineLanguages
                    Timber.tag(tag).i(
                        "diag: $kind $language installed=${installed.any { it.equals(language, true) }} " +
                            "supportedOnDevice=${supported.any { it.equals(language, true) }} pending=${pending.any { it.equals(language, true) }} " +
                            "online=${online.any { it.equals(language, true) }} (installed: $installed)"
                    )
                    android.os.Handler(android.os.Looper.getMainLooper()).post { recognizer.destroy() }
                    executor.shutdown()
                }

                override fun onError(error: Int) {
                    Timber.tag(tag).w("diag: $kind checkRecognitionSupport error $error (${errorName(error)})")
                    android.os.Handler(android.os.Looper.getMainLooper()).post { recognizer.destroy() }
                    executor.shutdown()
                }
            })
        }
    }

    companion object {
        private const val RECOGNITION_TIMEOUT_MS = 30_000L

        private fun userMessage(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал, повторите"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к распознаванию (микрофон)"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Русский язык распознавания недоступен"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Нет сети для распознавания"
            else -> "Ошибка распознавания ($code)"
        }

        fun errorName(code: Int) = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            10 -> "TOO_MANY_REQUESTS"
            11 -> "SERVER_DISCONNECTED"
            12 -> "LANGUAGE_NOT_SUPPORTED"
            13 -> "LANGUAGE_UNAVAILABLE"
            14 -> "CANNOT_CHECK_SUPPORT"
            15 -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "UNKNOWN"
        }
    }
}
