package com.cooper.wheellog.voice

import android.annotation.SuppressLint
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Watch <-> phone HTTP contract for FedVoice, independent of the HTTP server so it can be unit-tested.
 * See VoiceBridgeServer for the transport.
 */
class VoiceBridgeHandler(
    private val store: VoiceSessionStore,
    private val listener: Listener,
) {
    interface Listener {
        /** Called once the watch finished sending audio; must return quickly (process async). */
        fun onSessionEnded(id: String, audio: ByteArray)

        /** Forwards the user's confirmation to the server; may block. */
        fun onConfirm(id: String, yes: Boolean): VoiceResult
    }

    data class Reply(val status: Int, val json: String)

    fun handle(method: String, path: String, query: Map<String, String>, body: String?): Reply = try {
        when ("$method $path") {
            "POST /voice/start" -> ok(JSONObject().put("id", store.start()))
            "POST /voice/chunk" -> chunk(parse(body))
            "POST /voice/end" -> end(parse(body))
            "GET /voice/result" -> result(query["id"])
            "POST /voice/confirm" -> confirm(parse(body))
            else -> Reply(404, okJson(false, "not found"))
        }
    } catch (e: JSONException) {
        Reply(400, okJson(false, "bad request"))
    } catch (e: IllegalArgumentException) {
        Reply(400, okJson(false, "bad request"))
    }

    @SuppressLint("NewApi") // the voice bridge only runs on API 29+
    private fun chunk(json: JSONObject): Reply {
        val id = json.getString("id")
        val seq = json.getInt("seq")
        require(seq >= 0)
        val data = Base64.getMimeDecoder().decode(json.getString("data"))
        return if (store.addChunk(id, seq, data)) ok() else Reply(404, okJson(false, UNKNOWN_SESSION))
    }

    private fun end(json: JSONObject): Reply {
        val id = json.getString("id")
        val audio = store.end(id) ?: return Reply(404, okJson(false, UNKNOWN_SESSION))
        store.setResult(id, VoiceResult(VoiceResult.PENDING))
        listener.onSessionEnded(id, audio)
        return ok()
    }

    private fun result(id: String?): Reply {
        val r = id?.let { store.result(it) } ?: return Reply(404, resultJson(VoiceResult.error(UNKNOWN_SESSION)))
        return Reply(200, resultJson(r))
    }

    private fun confirm(json: JSONObject): Reply {
        val id = json.getString("id")
        val yes = json.getBoolean("yes")
        if (store.result(id) == null) return Reply(404, resultJson(VoiceResult.error(UNKNOWN_SESSION)))
        return Reply(200, resultJson(listener.onConfirm(id, yes)))
    }

    private fun parse(body: String?): JSONObject = JSONObject(body ?: throw IllegalArgumentException("empty body"))

    private fun ok(json: JSONObject = JSONObject().put("ok", true)) = Reply(200, json.toString())

    private fun okJson(ok: Boolean, error: String) = JSONObject().put("ok", ok).put("error", error).toString()

    companion object {
        const val UNKNOWN_SESSION = "Неизвестная сессия"

        fun resultJson(r: VoiceResult): String = JSONObject()
            .put("state", r.state)
            .put("text", r.text)
            .put("reply", r.reply)
            .put("needsConfirm", r.needsConfirm)
            .toString()
    }
}
