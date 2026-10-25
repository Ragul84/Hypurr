package com.ragul84.hypurr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.launch

/** Opens "Keep going free" (provided by the app root; null in previews and tests). */
val LocalKeepGoingFree = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Same words as the host (host/src/agent/builtin.rs FREE_EXHAUSTED / FREE_BUSY). */
const val FREE_MESSAGES_EXHAUSTED = "You've used your 10 free messages. Keep going free with your own account, or add credits."
const val FREE_BUSY = "Hypurr Free is busy today, try again tomorrow. Or keep going free with your own account."
const val NOT_FOR_COMPANY_CODE = "Free models: for trying out, not for company code."

fun freeMessagesLine(left: Int, limit: Int): String =
    "${left.coerceIn(0, limit)} of $limit free ${if (limit == 1) "message" else "messages"} left"

/** A host notice the "Keep going free" action belongs under. */
fun offersKeepGoingFree(text: String): Boolean = text.startsWith(FREE_MESSAGES_EXHAUSTED) || text.startsWith(FREE_BUSY)

/**
 * Hypurr's free models are counted in messages (a lifetime total of 10). Shows "N of 10 free
 * messages left", the own-account line once connected, or the used-up card with
 * Keep going free and Add credits.
 */
@Composable
fun FreeMessagesNote(
    left: Int,
    limit: Int,
    onAddCredits: () -> Unit,
    modifier: Modifier = Modifier,
    ownFree: Boolean = false,
    onKeepGoingFree: (() -> Unit)? = LocalKeepGoingFree.current,
) {
    val c = Hypurr.colors
    if (ownFree) {
        Text("Hypurr Free on your own account · not for company code", color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    if (left > 0) {
        Text(freeMessagesLine(left, limit), color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    SignalCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(FREE_MESSAGES_EXHAUSTED, color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (onKeepGoingFree != null) PrimaryButton("Keep going free", Modifier.fillMaxWidth(), onClick = onKeepGoingFree)
            SoftButton("Add credits", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Plus), onClick = onAddCredits)
        }
    }
}

/**
 * Connect the user's own OpenRouter account (OpenRouter's OAuth PKCE, headless mode): open the
 * page, sign in or sign up there yourself, copy the code it shows, paste it here. The computer
 * keeps the key; Hypurr never creates accounts.
 */
@Composable
fun KeepGoingFreeDialog(
    start: suspend () -> String,
    finish: suspend (String) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    /** Tests start on the paste step. */
    startOpened: Boolean = false,
) {
    val c = Hypurr.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf(startOpened) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        SignalCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Keep going free", color = c.text, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Use your own free OpenRouter account. You sign in or sign up there yourself, and its free daily limit is yours.",
                    color = c.secondary, style = MaterialTheme.typography.bodyMedium,
                )
                Text(NOT_FOR_COMPANY_CODE, color = c.secondary, style = MaterialTheme.typography.bodySmall)
                if (!opened) {
                    PrimaryButton("Open OpenRouter", Modifier.fillMaxWidth(), enabled = !busy) {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val url = start()
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                opened = true
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't start. Try again."
                            } finally { busy = false }
                        }
                    }
                } else {
                    Text("Copy the code OpenRouter shows, then paste it here.", color = c.text, style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(12.dp)).padding(14.dp)) {
                        if (code.isEmpty()) Text("Paste code", color = c.secondary, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(code, { code = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), modifier = Modifier.fillMaxWidth())
                    }
                    PrimaryButton("Connect", Modifier.fillMaxWidth(), enabled = code.isNotBlank() && !busy) {
                        busy = true; error = null
                        scope.launch {
                            try { finish(code.trim()); onDone() } catch (e: Exception) {
                                error = e.message ?: "Couldn't connect. Try again."
                            } finally { busy = false }
                        }
                    }
                }
                error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** "Keep going free" under a used-up or busy notice in a chat. */
@Composable
fun KeepGoingFreeAction(text: String, modifier: Modifier = Modifier) {
    val open = LocalKeepGoingFree.current ?: return
    if (!offersKeepGoingFree(text)) return
    SoftButton("Keep going free", modifier, onClick = open)
}
