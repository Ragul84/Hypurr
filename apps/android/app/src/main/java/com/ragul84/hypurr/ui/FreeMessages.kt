package com.ragul84.hypurr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr

/** Same words as the host and gateway (host/src/agent/builtin.rs FREE_EXHAUSTED). */
const val FREE_MESSAGES_EXHAUSTED = "You've used your free messages. Buy credits to keep going."

fun freeMessagesLine(left: Int, limit: Int): String =
    "${left.coerceIn(0, limit)} of $limit free ${if (limit == 1) "message" else "messages"} left"

/**
 * Hypurr's free models are counted in messages (one per task turn sent from the phone).
 * Shows "N of 10 free messages left" (a lifetime total), or the plain used-up line with Add credits.
 */
@Composable
fun FreeMessagesNote(left: Int, limit: Int, onAddCredits: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    if (left > 0) {
        Text(freeMessagesLine(left, limit), color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    SignalCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(FREE_MESSAGES_EXHAUSTED, color = c.text, style = MaterialTheme.typography.bodyLarge)
            PrimaryButton("Add credits", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Plus), onClick = onAddCredits)
        }
    }
}
