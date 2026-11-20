package com.cooper.wheellog.voice

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import okhttp3.OkHttpClient
import okio.Buffer
import java.util.concurrent.TimeUnit

class VoiceServerClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: VoiceServerClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString() // ends with '/', client must normalise
        client = VoiceServerClient(baseUrl = { base }, token = { "tok" })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun askBuildsRequestAndParsesReply() {
        server.enqueue(MockResponse().setBody("""{"reply":"Привет","pending_action":null}"""))
        val result = client.ask("как дела", "sid1")
        assertThat(result).isEqualTo(AskResult("Привет", null))

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/ask")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok")
        assertThat(req.getHeader("Content-Type")).startsWith("application/json")
        val body = JSONObject(req.body.readUtf8())
        assertThat(body.getString("text")).isEqualTo("как дела")
        assertThat(body.getString("session")).isEqualTo("sid1")
    }

    @Test
    fun askParsesPendingAction() {
        server.enqueue(MockResponse().setBody("""{"reply":"Удалить?","pending_action":{"id":"a1","summary":"удалить файл"}}"""))
        val result = client.ask("удали", "sid1")
        assertThat(result.pendingAction).isEqualTo(PendingAction("a1", "удалить файл"))
    }

    @Test
    fun askWithoutPendingActionField() {
        server.enqueue(MockResponse().setBody("""{"reply":"ok"}"""))
        assertThat(client.ask("x", "s").pendingAction).isNull()
    }

    @Test
    fun confirmBuildsRequest() {
        server.enqueue(MockResponse().setBody("""{"reply":"Сделано"}"""))
        assertThat(client.confirm("a1", false)).isEqualTo("Сделано")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/confirm")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok")
        val body = JSONObject(req.body.readUtf8())
        assertThat(body.getString("action_id")).isEqualTo("a1")
        assertThat(body.getBoolean("confirm")).isFalse()
    }

    @Test
    fun unauthorizedIsVoiceException() {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertThrows(VoiceException::class.java) { client.ask("x", "s") }
        assertThat(e.message).contains("токен")
    }

    @Test
    fun serverErrorIsVoiceException() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(VoiceException::class.java) { client.ask("x", "s") }
    }

    @Test
    fun emptyUrlIsVoiceException() {
        val c = VoiceServerClient(baseUrl = { "" }, token = { "" })
        assertThrows(VoiceException::class.java) { c.ask("x", "s") }
    }

    @Test
    fun ttsReturnsAudioBytes() {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setHeader("X-Voice", "jessica")
            .setBody(Buffer().write(mp3)))
        assertThat(client.tts("Привет")).isEqualTo(mp3)
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/tts")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok")
        assertThat(JSONObject(req.body.readUtf8()).getString("text")).isEqualTo("Привет")
    }

    @Test
    fun tts503IsVoiceException() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"elevenlabs down"}"""))
        assertThrows(VoiceException::class.java) { client.tts("x") }
    }

    @Test
    fun ttsEmptyBodyIsVoiceException() {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg"))
        assertThrows(VoiceException::class.java) { client.tts("x") }
    }

    @Test
    fun ttsTimeoutIsVoiceException() {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(2, TimeUnit.SECONDS))
        val base = server.url("/").toString()
        val c = VoiceServerClient(baseUrl = { base }, token = { "tok" },
            ttsHttp = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build())
        assertThrows(VoiceException::class.java) { c.tts("x") }
    }

    @Test
    fun ttsClientAllowsLongSynthesis() {
        assertThat(VoiceServerClient.TTS_READ_TIMEOUT_S).isAtLeast(90L)
    }

    @Test
    fun askAndConfirmWaitLongEnoughForSlowAgentReplies() {
        // the agent may take ~30 s+ (extra Hub tool calls); the watch keeps polling for 90 s
        assertThat(VoiceServerClient.ASK_READ_TIMEOUT_S).isEqualTo(60L)
        assertThat(VoiceServerClient.TTS_READ_TIMEOUT_S).isAtLeast(VoiceServerClient.ASK_READ_TIMEOUT_S)
    }
}
