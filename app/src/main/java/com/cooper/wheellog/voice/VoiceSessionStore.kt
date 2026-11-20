package com.cooper.wheellog.voice

import java.io.ByteArrayOutputStream
import java.util.TreeMap
import java.util.UUID

/** In-memory store of the latest few watch voice sessions. Thread-safe. */
class VoiceSessionStore(
    private val maxSessions: Int = 4,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {
    private class Session {
        val chunks = TreeMap<Int, ByteArray>()
        var ended = false
        var result = VoiceResult(VoiceResult.PENDING)
        var pendingActionId: String? = null
    }

    private val sessions = object : LinkedHashMap<String, Session>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) = size > maxSessions
    }

    @Synchronized
    fun start(): String {
        val id = idGenerator()
        sessions[id] = Session()
        return id
    }

    /** Returns false for an unknown session. A duplicate seq keeps the first copy. */
    @Synchronized
    fun addChunk(id: String, seq: Int, data: ByteArray): Boolean {
        val s = sessions[id] ?: return false
        if (!s.ended) s.chunks.putIfAbsent(seq, data)
        return true
    }

    /** Marks the session ended and returns its audio in seq order, or null for an unknown session. */
    @Synchronized
    fun end(id: String): ByteArray? {
        val s = sessions[id] ?: return null
        s.ended = true
        val out = ByteArrayOutputStream()
        s.chunks.values.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Synchronized
    fun result(id: String): VoiceResult? = sessions[id]?.result

    @Synchronized
    fun setResult(id: String, result: VoiceResult, pendingActionId: String? = null) {
        val s = sessions[id] ?: return
        s.result = result
        s.pendingActionId = pendingActionId
    }

    @Synchronized
    fun pendingAction(id: String): String? = sessions[id]?.pendingActionId
}
