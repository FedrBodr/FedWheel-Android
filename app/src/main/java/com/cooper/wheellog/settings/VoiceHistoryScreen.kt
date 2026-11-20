package com.cooper.wheellog.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cooper.wheellog.R
import com.cooper.wheellog.voice.VoiceHistory
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun voiceHistoryScreen() {
    val context = LocalContext.current
    val history = remember { VoiceHistory.get(context) }
    val entries by history.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val you = stringResource(R.string.voice_history_you, "%s")
    val replyFmt = stringResource(R.string.voice_history_reply, "%s")
    val copied = stringResource(R.string.voice_history_copied)
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    fun copy(e: VoiceHistory.Entry) {
        val text = you.format(e.text) + "\n" + replyFmt.format(e.reply)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FedVoice", text))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.voice_history_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Text(stringResource(R.string.voice_history_clear))
            }
        }
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.voice_history_empty),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.sessionId }) { e ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { copy(e) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeFormat.format(Date(e.time)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stateLabel(e.state),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (e.state == VoiceHistory.State.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { copy(e) }) {
                            Text(stringResource(R.string.voice_history_copy), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (e.text.isNotEmpty()) {
                        Text(
                            text = you.format(e.text),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (e.reply.isNotEmpty()) {
                        Text(text = replyFmt.format(e.reply), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            shape = RoundedCornerShape(8.dp),
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.voice_history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { history.clear(); confirmClear = false }) {
                    Text(stringResource(R.string.voice_history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.voice_history_cancel))
                }
            },
        )
    }
}

@Composable
private fun stateLabel(state: VoiceHistory.State): String = stringResource(
    when (state) {
        VoiceHistory.State.PROCESSING -> R.string.voice_state_processing
        VoiceHistory.State.OK -> R.string.voice_state_ok
        VoiceHistory.State.ERROR -> R.string.voice_state_error
        VoiceHistory.State.AWAITING_CONFIRM -> R.string.voice_state_awaiting_confirm
        VoiceHistory.State.CONFIRMED -> R.string.voice_state_confirmed
        VoiceHistory.State.CANCELLED -> R.string.voice_state_cancelled
    }
)
