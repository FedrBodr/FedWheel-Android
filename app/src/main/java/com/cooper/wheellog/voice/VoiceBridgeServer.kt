package com.cooper.wheellog.voice

import fi.iki.elonen.NanoHTTPD
import timber.log.Timber

/** NanoHTTPD transport for the FedVoice watch contract (127.0.0.1, random port). */
class VoiceBridgeServer(
    private val handler: VoiceBridgeHandler,
    private val onWatchRequest: () -> Unit = {},
) : NanoHTTPD("127.0.0.1", 0) {

    init {
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun serve(session: IHTTPSession): Response {
        onWatchRequest()
        val method = session.method?.name ?: "?"
        val body = if (session.method == Method.POST || session.method == Method.PUT) {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
                files["postData"]
            } catch (e: Exception) {
                Timber.tag(VoicePipeline.TAG).w(e, "http: cannot read body")
                null
            }
        } else null
        val query = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
        val reply = try {
            handler.handle(method, session.uri, query, body)
        } catch (e: Exception) {
            Timber.tag(VoicePipeline.TAG).e(e, "http: handler crashed")
            VoiceBridgeHandler.Reply(500, VoiceBridgeHandler.resultJson(VoiceResult.error("Внутренняя ошибка")))
        }
        if (session.uri != "/voice/result" && session.uri != "/voice/chunk") {
            Timber.tag(VoicePipeline.TAG).i("http: $method ${session.uri} -> ${reply.status} ${reply.json.take(200)}")
        }
        val status = Response.Status.lookup(reply.status) ?: Response.Status.INTERNAL_ERROR
        return newFixedLengthResponse(status, "application/json; charset=utf-8", reply.json)
    }
}
