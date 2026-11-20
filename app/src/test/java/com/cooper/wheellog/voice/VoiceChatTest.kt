package com.cooper.wheellog.voice

import com.cooper.wheellog.voice.VoiceChat.Chip
import com.cooper.wheellog.voice.VoiceChat.Item
import com.cooper.wheellog.voice.VoiceHistory.Entry
import com.cooper.wheellog.voice.VoiceHistory.State
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceChatTest {
    @Test
    fun emptyHistoryGivesNoItems() {
        assertThat(VoiceChat.toChatItems(emptyList())).isEmpty()
    }

    @Test
    fun newestFirstHistoryBecomesOldestFirstChatWithUserAndReplyPerExchange() {
        val history = listOf(
            Entry("s2", 2_000L, "который час", "Восемь вечера", State.OK),
            Entry("s1", 1_000L, "включи свет", "Включаю", State.OK),
        )
        assertThat(VoiceChat.toChatItems(history)).containsExactly(
            Item.Said("s1:u", 1_000L, "включи свет"),
            Item.Replied("s1:r", 1_000L, "Включаю", null),
            Item.Said("s2:u", 2_000L, "который час"),
            Item.Replied("s2:r", 2_000L, "Восемь вечера", null),
        ).inOrder()
    }

    @Test
    fun processingHasNoReplyBubbleButThinkingMarker() {
        val items = VoiceChat.toChatItems(listOf(Entry("s1", 1_000L, "привет", "", State.PROCESSING)))
        assertThat(items).containsExactly(
            Item.Said("s1:u", 1_000L, "привет"),
            Item.Thinking("s1:t", 1_000L),
        ).inOrder()
    }

    @Test
    fun errorShowsMessageAsErrorReply() {
        val items = VoiceChat.toChatItems(listOf(Entry("s1", 1_000L, "привет", "timeout", State.ERROR)))
        assertThat(items).containsExactly(
            Item.Said("s1:u", 1_000L, "привет"),
            Item.Replied("s1:r", 1_000L, "timeout", Chip.ERROR),
        ).inOrder()
    }

    @Test
    fun errorWithoutRecognizedTextHasOnlyErrorReply() {
        val items = VoiceChat.toChatItems(listOf(Entry("s1", 1_000L, "", "не расслышал", State.ERROR)))
        assertThat(items).containsExactly(Item.Replied("s1:r", 1_000L, "не расслышал", Chip.ERROR))
    }

    @Test
    fun confirmStatesAreChipsOnTheReply() {
        val items = VoiceChat.toChatItems(listOf(
            Entry("s3", 3_000L, "выключи всё", "Выключаю", State.CONFIRMED),
            Entry("s2", 2_000L, "удали заметку", "Отменено", State.CANCELLED),
            Entry("s1", 1_000L, "закажи такси", "Заказать такси?", State.AWAITING_CONFIRM),
        ))
        assertThat(items.filterIsInstance<Item.Replied>().map { it.chip })
            .containsExactly(Chip.AWAITING_CONFIRM, Chip.CANCELLED, Chip.CONFIRMED).inOrder()
    }

    @Test
    fun okWithEmptyReplyHasNoReplyBubble() {
        val items = VoiceChat.toChatItems(listOf(Entry("s1", 1_000L, "привет", "", State.OK)))
        assertThat(items).containsExactly(Item.Said("s1:u", 1_000L, "привет"))
    }
}
