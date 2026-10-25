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

/** Opens "Start free with your own account" (provided by the app root; null in previews and tests). */
val LocalStartFree = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

const val START_FREE = "Start free with your own account"
/** Same words as the host (host/src/agent/builtin.rs NEEDS_ACCOUNT). */
const val NEEDS_ACCOUNT = "Start free with your own account, or add credits."
const val NOT_FOR_COMPANY_CODE = "Free models: for trying out, not for company code."

/** A host notice the "Start free" action belongs under. */
fun offersStartFree(text: String): Boolean =
    text.startsWith(NEEDS_ACCOUNT) || (text.startsWith("Free models aren't available yet") && text.contains(START_FREE))

/**
 * Hypurr Free runs only on the user's own free account (no shared allowance). Shows one quiet
 * line once connected; otherwise the start-free card, with Add credits as the paid option.
 */
@Composable
fun StartFreeNote(
    ownFree: Boolean,
    onAddCredits: () -> Unit,
    modifier: Modifier = Modifier,
    onStartFree: (() -> Unit)? = LocalStartFree.current,
) {
    val c = Hypurr.colors
    if (ownFree) {
        Text("Hypurr Free on your own account · not for company code", color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }
    SignalCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Hypurr Free runs on your own free account. For trying out, not for company code.",
                color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (onStartFree != null) PrimaryButton(START_FREE, Modifier.fillMaxWidth(), onClick = onStartFree)
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
fun StartFreeDialog(
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
                Text(START_FREE, color = c.text, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Hypurr Free runs on your own free OpenRouter account. You sign in or sign up there yourself, and its free daily limit is yours.",
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

/** "Start free with your own account" under a needs-an-account notice in a chat. */
@Composable
fun StartFreeAction(text: String, modifier: Modifier = Modifier) {
    val open = LocalStartFree.current ?: return
    if (!offersStartFree(text)) return
    SoftButton(START_FREE, modifier, onClick = open)
}
