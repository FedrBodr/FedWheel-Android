package com.cooper.wheellog.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Client of the FedVoice server agent: POST {url}/v1/ask and {url}/v1/confirm. */
class VoiceServerClient(
    private val baseUrl: () -> String,
    private val token: () -> String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_S + 5, TimeUnit.SECONDS)
        .build(),
) : VoiceAgent {

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

    private fun post(path: String, body: JSONObject): JSONObject {
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
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
