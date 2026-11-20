package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.util.Base64

class VoiceBridgeHandlerTest {
    private val ended = mutableListOf<Pair<String, ByteArray>>()
    private val confirms = mutableListOf<Pair<String, Boolean>>()
    private val store = VoiceSessionStore(idGenerator = { "sid1" })
    private val handler = VoiceBridgeHandler(store, object : VoiceBridgeHandler.Listener {
        override fun onSessionEnded(id: String, audio: ByteArray) { ended += id to audio }
        override fun onConfirm(id: String, yes: Boolean): VoiceResult {
            confirms += id to yes
            return VoiceResult(VoiceResult.DONE, "t", "Готово", false)
        }
    })

    private fun b64(vararg b: Byte) = Base64.getEncoder().encodeToString(b)

    @Test
    fun startReturnsId() {
        val r = handler.handle("POST", "/voice/start", emptyMap(), "{}")
        assertThat(r.status).isEqualTo(200)
        assertThat(JSONObject(r.json).getString("id")).isEqualTo("sid1")
    }

    @Test
    fun chunkThenEndDeliversAudio() {
        handler.handle("POST", "/voice/start", emptyMap(), "{}")
        val c1 = handler.handle("POST", "/voice/chunk", emptyMap(), """{"id":"sid1","seq":1,"data":"${b64(3, 4)}"}""")
        handler.handle("POST", "/voice/chunk", emptyMap(), """{"id":"sid1","seq":0,"data":"${b64(1, 2)}"}""")
        assertThat(JSONObject(c1.json).getBoolean("ok")).isTrue()
        val end = handler.handle("POST", "/voice/end", emptyMap(), """{"id":"sid1"}""")
        assertThat(JSONObject(end.json).getBoolean("ok")).isTrue()
        assertThat(ended.single().first).isEqualTo("sid1")
        assertThat(ended.single().second).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun resultHasContractShape() {
        handler.handle("POST", "/voice/start", emptyMap(), "{}")
        store.setResult("sid1", VoiceResult(VoiceResult.DONE, "привет", "здравствуй", true), "a1")
        val r = handler.handle("GET", "/voice/result", mapOf("id" to "sid1"), null)
        val json = JSONObject(r.json)
        assertThat(r.status).isEqualTo(200)
        assertThat(json.getString("state")).isEqualTo("done")
        assertThat(json.getString("text")).isEqualTo("привет")
        assertThat(json.getString("reply")).isEqualTo("здравствуй")
        assertThat(json.getBoolean("needsConfirm")).isTrue()
    }

    @Test
    fun pendingResultBeforeProcessing() {
        handler.handle("POST", "/voice/start", emptyMap(), "{}")
        val json = JSONObject(handler.handle("GET", "/voice/result", mapOf("id" to "sid1"), null).json)
        assertThat(json.getString("state")).isEqualTo("pending")
        assertThat(json.getBoolean("needsConfirm")).isFalse()
    }

    @Test
    fun unknownSessionIsError() {
        val chunk = handler.handle("POST", "/voice/chunk", emptyMap(), """{"id":"x","seq":0,"data":"${b64(1)}"}""")
        assertThat(chunk.status).isEqualTo(404)
        assertThat(JSONObject(chunk.json).getBoolean("ok")).isFalse()
        val result = handler.handle("GET", "/voice/result", mapOf("id" to "x"), null)
        assertThat(result.status).isEqualTo(404)
        assertThat(JSONObject(result.json).getString("state")).isEqualTo("error")
    }

    @Test
    fun malformedBodyIsBadRequest() {
        val r = handler.handle("POST", "/voice/chunk", emptyMap(), "not json")
        assertThat(r.status).isEqualTo(400)
    }

    @Test
    fun confirmForwardsAndReturnsResultShape() {
        handler.handle("POST", "/voice/start", emptyMap(), "{}")
        val r = handler.handle("POST", "/voice/confirm", emptyMap(), """{"id":"sid1","yes":true}""")
        assertThat(confirms.single()).isEqualTo("sid1" to true)
        val json = JSONObject(r.json)
        assertThat(json.getString("state")).isEqualTo("done")
        assertThat(json.getString("reply")).isEqualTo("Готово")
        assertThat(json.getBoolean("needsConfirm")).isFalse()
    }

    @Test
    fun unknownPathIs404() {
        assertThat(handler.handle("GET", "/voice/zzz", emptyMap(), null).status).isEqualTo(404)
    }
}
