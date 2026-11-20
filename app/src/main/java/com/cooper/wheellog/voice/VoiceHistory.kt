package com.cooper.wheellog.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Persistent log of watch voice exchanges (what was said, what the assistant answered), newest first,
 * capped at [maxEntries]. Stored as a small JSON file; observable through [entries]. Thread-safe.
 */
class VoiceHistory(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State { PROCESSING, OK, ERROR, AWAITING_CONFIRM, CONFIRMED, CANCELLED }

    data class Entry(
        val sessionId: String,
        val time: Long,
        val text: String,
        val reply: String,
        val state: State,
    )

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * Creates the entry for [sessionId] or updates it in place (time and position are kept).
     * A null [text]/[reply] keeps the previous value.
     */
    @Synchronized
    fun record(sessionId: String, text: String? = null, reply: String? = null, state: State) {
        val list = _entries.value
        val i = list.indexOfFirst { it.sessionId == sessionId }
        val updated = if (i >= 0) {
            val old = list[i]
            list.toMutableList().apply {
                this[i] = old.copy(text = text ?: old.text, reply = reply ?: old.reply, state = state)
            }
        } else {
            (listOf(Entry(sessionId, clock(), text.orEmpty(), reply.orEmpty(), state)) + list).take(maxEntries)
        }
        _entries.value = updated
        save(updated)
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save(emptyList())
    }

    private fun load(): List<Entry> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
                val state = runCatching { State.valueOf(o.getString("state")) }.getOrNull() ?: return@mapNotNull null
                Entry(o.getString("id"), o.getLong("time"), o.optString("text"), o.optString("reply"), state)
            }.take(maxEntries)
        }
    } catch (e: JSONException) {
        Timber.tag(VoicePipeline.TAG).w(e, "voice history unreadable, starting empty")
        emptyList()
    } catch (e: IOException) {
        Timber.tag(VoicePipeline.TAG).w(e, "voice history unreadable, starting empty")
        emptyList()
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.sessionId).put("time", it.time).put("text", it.text)
                .put("reply", it.reply).put("state", it.state.name))
        }
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (e: IOException) {
            Timber.tag(VoicePipeline.TAG).w(e, "voice history save failed")
        }
    }

    companion object {
        const val MAX_ENTRIES = 100

        @Volatile
        private var instance: VoiceHistory? = null

        /** Process-wide instance shared by the Garmin service and the settings UI. */
        fun get(context: Context): VoiceHistory = instance ?: synchronized(this) {
            instance ?: VoiceHistory(File(context.applicationContext.filesDir, "voice_history.json")).also { instance = it }
        }
    }
}
