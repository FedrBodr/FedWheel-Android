package com.cooper.wheellog.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client of the FedVoice server agent: POST {url}/v1/ask, {url}/v1/confirm and {url}/v1/tts.
 * TTS uses its own client with a long read timeout: uncached synthesis takes ~30-40 s.
 */
class VoiceServerClient(
    private val baseUrl: () -> String,
    private val token: () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(ASK_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(ASK_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build(),
    private val ttsHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TTS_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TTS_READ_TIMEOUT_S + 10, TimeUnit.SECONDS)
        .build(),
) : VoiceAgent, VoiceTts {

    override fun ask(text: String, session: String): AskResult {
        val json = post("/v1/ask", JSONObject().put("text", text).put("session", session))
        val pending = json.optJSONObject("pending_action")?.let {
            PendingAction(it.getString("id"), it.optString("summary", ""))
        }
        return AskResult(json.optString("reply", ""), pending)
    }

    override fun confirm(actionId: String, confirm: Boolean): String {
        val json = post("/v1/confirm", JSONObject().put("action_id", actionId).put("confirm", confirm))
        return json.optString("reply", "")
    }

    /** POST /v1/tts -> mp3 bytes; any failure is a VoiceException (caller falls back to phone TTS). */
    override fun tts(text: String): ByteArray {
        val request = request("/v1/tts", JSONObject().put("text", text))
        try {
            ttsHttp.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> throw VoiceException("Неверный токен сервера")
                    !response.isSuccessful -> throw VoiceException("Синтез недоступен (${response.code})")
                }
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) throw VoiceException("Пустой ответ синтеза")
                return bytes
            }
        } catch (e: IOException) {
            throw VoiceException("Сервер недоступен", e)
        }
    }

    private fun request(path: String, body: JSONObject): Request {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) throw VoiceException("Не задан адрес голосового сервера")
        val request = try {
            Request.Builder()
                .url(base + path)
                .header("Authorization", "Bearer ${token().trim()}")
                .post(body.toString().toRequestBody(JSON))
                .build()
        } catch (e: IllegalArgumentException) {
            throw VoiceException("Неверный адрес голосового сервера", e)
        }
        return request
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = request(path, body)
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> throw VoiceException("Неверный токен сервера")
                    !response.isSuccessful -> throw VoiceException("Ошибка сервера (${response.code})")
                }
                return JSONObject(response.body.string())
            }
        } catch (e: IOException) {
            throw VoiceException("Сервер недоступен", e)
        } catch (e: JSONException) {
            throw VoiceException("Непонятный ответ сервера", e)
        }
    }

    companion object {
        private const val TIMEOUT_S = 20L
        /** /v1/ask and /v1/confirm: the agent may call several Hub tools (~30 s+); the watch polls for up to 90 s. */
        const val ASK_READ_TIMEOUT_S = 60L
        const val TTS_READ_TIMEOUT_S = 90L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
