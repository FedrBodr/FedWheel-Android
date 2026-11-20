package com.cooper.wheellog.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Debug-only: synthesizes [text] with every Russian voice of every installed TTS engine
 * into getExternalFilesDir(null)/voices/voice_<engine>_<voice>.wav and writes voices.json.
 * Does not touch the app's own VoiceSpeaker settings.
 */
object VoiceSamplesDebug {
    private const val TAG = "FedVoice"

    /**
     * index < 0: enumerate ru voices of all engines into voices.json (fast).
     * index >= 0: synthesize voice #index of voices.json, write voice_<n>.json with the result.
     * One voice per broadcast keeps each run well under the broadcast ANR timeout.
     */
    suspend fun run(context: Context, text: String, index: Int) {
        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "voices").apply { mkdirs() }
        val listFile = File(outDir, "voices.json")
        if (index < 0) {
            val report = JSONArray()
            val probe = initTts(context, null)
            val engines = probe?.engines?.map { it.name } ?: emptyList()
            probe?.shutdown()
            Timber.tag(TAG).i("voices: engines=$engines out=$outDir")
            for (engine in engines) {
                val tts = initTts(context, engine)
                if (tts == null) { report.put(JSONObject().put("engine", engine).put("error", "init failed")); continue }
                try {
                    val voices = try { tts.voices?.filter { it.locale.language == "ru" } ?: emptyList() } catch (e: Exception) { emptyList() }
                    if (voices.isEmpty()) report.put(JSONObject().put("engine", engine).put("error", "no ru voices"))
                    for (v in voices.sortedBy { it.name }) {
                        val safe = "${engine}_${v.name}".replace(Regex("[^A-Za-z0-9._-]"), "_")
                        report.put(JSONObject()
                            .put("engine", engine).put("voice", v.name).put("locale", v.locale.toLanguageTag())
                            .put("quality", v.quality).put("latency", v.latency)
                            .put("networkRequired", v.isNetworkConnectionRequired)
                            .put("features", JSONArray(v.features?.sorted() ?: emptyList<String>()))
                            .put("file", "voice_$safe.wav"))
                    }
                } finally { tts.shutdown() }
            }
            listFile.writeText(report.toString(2))
            Timber.tag(TAG).i("voices: listed ${report.length()} entries -> $listFile")
            return
        }
        val entry = JSONArray(listFile.readText()).getJSONObject(index)
        val engine = entry.getString("engine")
        val name = entry.optString("voice", "")
        if (name.isEmpty()) { Timber.tag(TAG).i("voices: #$index skip $entry"); return }
        val file = File(outDir, entry.getString("file"))
        val tts = initTts(context, engine)
        try {
            val v = tts?.voices?.firstOrNull { it.name == name }
            val ok = try { v != null && tts.setVoice(v) == TextToSpeech.SUCCESS && synth(tts, text, file) }
                     catch (e: Exception) { entry.put("exception", e.toString()); false }
            if (!ok || !file.exists() || file.length() < 1000) {
                entry.put("error", "synthesis failed (voiceFound=${v != null}, size=${if (file.exists()) file.length() else -1})")
                file.delete()
            } else entry.put("bytes", file.length())
        } finally { tts?.shutdown() }
        File(outDir, "voice_$index.json").writeText(entry.toString(2))
        Timber.tag(TAG).i("voices: #$index $entry")
    }

    private suspend fun initTts(context: Context, engine: String?): TextToSpeech? {
        val done = CompletableDeferred<Int>()
        val tts = if (engine == null) TextToSpeech(context) { done.complete(it) }
        else TextToSpeech(context, { done.complete(it) }, engine)
        val status = withTimeoutOrNull(15_000) { done.await() }
        if (status != TextToSpeech.SUCCESS) { tts.shutdown(); return null }
        return tts
    }

    private suspend fun synth(tts: TextToSpeech, text: String, file: File): Boolean {
        val result = CompletableDeferred<Boolean>()
        val id = "s_${System.nanoTime()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) result.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) result.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) result.complete(false) }
        })
        if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) return false
        return withTimeoutOrNull(60_000) { result.await() } ?: false
    }
}
