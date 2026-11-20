package com.cooper.wheellog.voice

import com.cooper.wheellog.voice.VoiceHistory.State

/** Pure mapping of [VoiceHistory] entries (newest first) into a messenger-style chat (oldest first). */
object VoiceChat {
    /** Status chip shown next to an assistant reply. */
    enum class Chip { ERROR, AWAITING_CONFIRM, CONFIRMED, CANCELLED }

    sealed interface Item {
        val key: String
        val time: Long

        /** What the owner said into the watch (right-aligned bubble). */
        data class Said(override val key: String, override val time: Long, val text: String) : Item

        /** What the assistant answered (left-aligned bubble); an error carries its message as [text]. */
        data class Replied(override val key: String, override val time: Long, val text: String, val chip: Chip?) : Item

        /** The request is still being processed ("думаю…"), no reply yet. */
        data class Thinking(override val key: String, override val time: Long) : Item
    }

    fun toChatItems(entries: List<VoiceHistory.Entry>): List<Item> = entries.asReversed().flatMap { e ->
        buildList {
            if (e.text.isNotEmpty()) add(Item.Said("${e.sessionId}:u", e.time, e.text))
            val reply = "${e.sessionId}:r"
            when (e.state) {
                State.PROCESSING -> add(Item.Thinking("${e.sessionId}:t", e.time))
                State.ERROR -> add(Item.Replied(reply, e.time, e.reply, Chip.ERROR))
                State.OK -> if (e.reply.isNotEmpty()) add(Item.Replied(reply, e.time, e.reply, null))
                State.AWAITING_CONFIRM -> add(Item.Replied(reply, e.time, e.reply, Chip.AWAITING_CONFIRM))
                State.CONFIRMED -> add(Item.Replied(reply, e.time, e.reply, Chip.CONFIRMED))
                State.CANCELLED -> add(Item.Replied(reply, e.time, e.reply, Chip.CANCELLED))
            }
        }
    }
}
