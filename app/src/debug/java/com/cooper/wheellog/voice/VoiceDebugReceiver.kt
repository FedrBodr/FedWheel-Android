package com.cooper.wheellog.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Debug-only. Works without the Garmin service and without the watch:
 *  adb shell am broadcast -n com.cooper.wheellog.debug/com.cooper.wheellog.voice.VoiceDebugReceiver -a com.cooper.wheellog.debug.FEDVOICE_DIAG
 *  ... --es speak "Привет"           TTS with ducking
 *  ... --es file /sdcard/Download/q.ogg   decode -> STT, logs the text (no server call)
 * Output: adb logcat -s FedVoice
 */
class VoiceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
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
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "debug: failed: ${e.message}")
            } finally {
                delay(6_000) // let TTS init/speak and async diagnostics log
                speaker.shutdown()
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "FedVoice" }
}
