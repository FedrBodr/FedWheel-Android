package com.cooper.wheellog.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cooper.wheellog.R
import com.cooper.wheellog.voice.VoiceChat.Chip
import com.cooper.wheellog.voice.VoiceChat.Item
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

private val Accent = Color(0xFFFF5722)
private val ErrorRed = Color(0xFFE53935)

private data class ChatColors(val replyBubble: Color, val replyText: Color, val meta: Color)

@Composable
private fun chatColors(): ChatColors =
    // The main pager always sits on a dark-blue (day) / dark-grey (night) window background.
    if (isSystemInDarkTheme()) ChatColors(Color(0xFF3A4357), Color(0xFFECEFF4), Color(0xB3FFFFFF))
    else ChatColors(Color(0xFFF4F6FA), Color(0xFF1B1F27), Color(0xCCFFFFFF))

/**
 * Main-pager page: the watch voice dialog as a read-only messenger, oldest at the top, newest at the bottom.
 * [openedSignal] changes every time the page is swiped into view, which scrolls to the newest message.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceChatPage(openedSignal: StateFlow<Int>? = null) {
    val context = LocalContext.current
    val history = remember { VoiceHistory.get(context) }
    val entries by history.entries.collectAsState()
    val opened = openedSignal?.collectAsState()?.value ?: 0
    val items = remember(entries) { VoiceChat.toChatItems(entries) }
    // reverseLayout: index 0 is drawn at the bottom, so the newest message sits there like in a messenger.
    val newestFirst = remember(items) { items.asReversed() }
    val listState = rememberLazyListState()
    val colors = chatColors()
    val copied = stringResource(R.string.voice_history_copied)

    LaunchedEffect(newestFirst.firstOrNull()?.key, newestFirst.size, opened) {
        if (newestFirst.isNotEmpty()) listState.animateScrollToItem(0)
    }

    fun copy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FedVoice", text))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.voice_chat_empty),
                color = colors.meta,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
            )
        }
        return
    }

    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
    ) {
        items(newestFirst, key = { it.key }) { item ->
            when (item) {
                is Item.Said -> Bubble(
                    text = item.text, time = item.time, mine = true, maxWidth = maxBubble,
                    bubble = Accent, textColor = Color.White, meta = colors.meta, chip = null,
                    onLongClick = { copy(item.text) },
                )
                is Item.Replied -> {
                    val text = item.text.ifEmpty { stringResource(R.string.voice_state_error) }
                    Bubble(
                        text = text, time = item.time, mine = false, maxWidth = maxBubble,
                        bubble = colors.replyBubble,
                        textColor = if (item.chip == Chip.ERROR) ErrorRed else colors.replyText,
                        meta = colors.meta, chip = item.chip,
                        onLongClick = { copy(text) },
                    )
                }
                is Item.Thinking -> Text(
                    text = stringResource(R.string.voice_chat_thinking),
                    color = colors.meta,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    text: String,
    time: Long,
    mine: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp,
    bubble: Color,
    textColor: Color,
    meta: Color,
    chip: Chip?,
    onLongClick: () -> Unit,
) {
    val shape = if (mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 16.sp,
            modifier = Modifier
                .widthIn(max = maxWidth)
                .background(bubble, shape)
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (chip != null) ChipLabel(chip)
            Text(text = formatTime(time), color = meta, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChipLabel(chip: Chip) {
    val (label, color) = when (chip) {
        Chip.ERROR -> R.string.voice_state_error to ErrorRed
        Chip.AWAITING_CONFIRM -> R.string.voice_state_awaiting_confirm to Color(0xFFFFA000)
        Chip.CONFIRMED -> R.string.voice_state_confirmed to Color(0xFF43A047)
        Chip.CANCELLED -> R.string.voice_state_cancelled to Color(0xFF78909C)
    }
    Text(
        text = stringResource(label),
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun formatTime(time: Long): String {
    val date = Date(time)
    return if (DateUtils.isToday(time)) DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
}
