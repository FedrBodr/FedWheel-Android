package com.cooper.wheellog.voice

/**
 * The on-device recognizer fed via EXTRA_AUDIO_SOURCE can deliver an empty final result while the
 * partial results hold the text, and the last partial may repeat its tail ("A B C, B C").
 */
object TranscriptPicker {
    fun pick(final: String?, partials: List<String>): String {
        if (!final.isNullOrBlank()) return final.trim()
        val distinct = partials.map { it.trim() }.filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }
        val last = distinct.lastOrNull() ?: return ""
        val prev = distinct.getOrNull(distinct.size - 2) ?: return last
        if (last.startsWith(prev)) {
            val rest = last.substring(prev.length).trimStart(',', ' ', '.').trim()
            if (rest.isNotEmpty() && prev.lowercase().endsWith(rest.lowercase())) return prev
        }
        return last
    }
}
