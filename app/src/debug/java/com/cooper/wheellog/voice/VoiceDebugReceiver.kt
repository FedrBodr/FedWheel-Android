package com.cooper.wheellog.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cooper.wheellog.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

/**
 * Debug-only. Works without the Garmin service and without the watch:
 *  adb shell am broadcast -n com.cooper.wheellog.debug/com.cooper.wheellog.voice.VoiceDebugReceiver -a com.cooper.wheellog.debug.FEDVOICE_DIAG
 *  ... --es speak "Привет"           TTS with ducking
 *  ... --es file /sdcard/Download/q.ogg   decode -> STT, logs the text (no server call)
 *  ... --es ask "Который час"      typed phrase through VoicePipeline (server + TTS + voice history), no audio
 * Voice samples (all ru voices of all TTS engines -> Android/data/<pkg>/files/voices/ (wav + voices.json)):
 *  adb shell am broadcast -n com.cooper.wheellog.debug/com.cooper.wheellog.voice.VoiceDebugReceiver -a com.cooper.wheellog.debug.FEDVOICE_VOICES            lists ru voices -> voices.json
 *  ... -a com.cooper.wheellog.debug.FEDVOICE_VOICES --ei index N --es text "Привет"   synthesizes voice #N
 * Output: adb logcat -s FedVoice
 */
class VoiceDebugReceiver : BroadcastReceiver(), KoinComponent {
    private val appConfig: AppConfig by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        if (intent.action == ACTION_VOICES) {
            val text = intent.getStringExtra("text") ?: "Привет! Это проверка голоса."
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                try { VoiceSamplesDebug.run(app, text, intent.getIntExtra("index", -1)) } catch (e: Exception) { Timber.tag(TAG).e(e, "voices failed") }
                finally { pending.finish() }
            }
            return
        }
        val stt = AndroidSpeechToText(app, realtimeFeed = intent.getBooleanExtra("realtime", false),
            trailingSilenceMs = intent.getIntExtra("silence", 300))
        try { stt.logDiagnostics() } catch (e: Exception) { Timber.tag(TAG).w(e, "diag failed") }
        val speaker = VoiceSpeaker(app) // logs TTS ru-RU availability once initialised
        intent.getStringExtra("speak")?.let { speaker.speak(it) }
        val file = intent.getStringExtra("file")
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (file != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val t0 = System.currentTimeMillis()
                    val pcm = OggOpusDecoder(app.cacheDir).decode(File(file).readBytes())
                    Timber.tag(TAG).i("debug: decoded ${pcm.samples.size} samples @ ${pcm.sampleRate} in ${System.currentTimeMillis() - t0} ms")
                    val t1 = System.currentTimeMillis()
                    val text = stt.transcribe(pcm.samples, pcm.sampleRate)
                    Timber.tag(TAG).i("debug: STT \"$text\" in ${System.currentTimeMillis() - t1} ms")
                    if (text.isNotBlank()) speaker.speak("Вы сказали: $text")
                }
                intent.getStringExtra("ask")?.let { phrase -> askThroughPipeline(app, phrase, speaker) }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "debug: failed: ${e.message}")
            } finally {
                delay(6_000) // let TTS init/speak and async diagnostics log
                speaker.shutdown()
                pending.finish()
            }
        }
    }

    private suspend fun askThroughPipeline(app: Context, phrase: String, speaker: Speaker) {
        val store = VoiceSessionStore()
        val pipeline = VoicePipeline(
            store = store,
            decoder = object : AudioDecoder {
                override fun decode(bytes: ByteArray) = Pcm(ShortArray(1), 16_000)
            },
            stt = object : SpeechToText {
                override suspend fun transcribe(pcm: ShortArray, sampleRate: Int) = phrase
            },
            agent = VoiceServerClient(baseUrl = { appConfig.voiceServerUrl }, token = { appConfig.voiceServerToken }),
            speaker = speaker,
            scope = CoroutineScope(Dispatchers.IO),
            history = VoiceHistory.get(app),
        )
        val id = store.start()
        store.end(id)
        pipeline.process(id, byteArrayOf(0))
        Timber.tag(TAG).i("debug: ask \"$phrase\" -> ${store.result(id)}")
    }

    private companion object {
        const val TAG = "FedVoice"
        const val ACTION_VOICES = "com.cooper.wheellog.debug.FEDVOICE_VOICES"
    }
}
