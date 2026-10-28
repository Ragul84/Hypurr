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
import androidx.compose.ui.text.input.PasswordVisualTransformation

const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * Gemini with an API key, for accounts where Google refuses free Google sign-in. The key goes
 * to the computer (kept in its vault, never logged); the phone doesn't keep it.
 */
@Composable
fun GeminiKeyDialog(
    save: suspend (String) -> Unit,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) { GeminiKeyForm(save, onDone) }
}

/** The dialog's body (tests render it without a window). */
@Composable
fun GeminiKeyForm(save: suspend (String) -> Unit, onDone: () -> Unit) {
    val c = Hypurr.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    run {
        SignalCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Use a Gemini API key", color = c.text, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Get a free key from Google AI Studio, then paste it here. It's saved only on your computer.",
                    color = c.secondary, style = MaterialTheme.typography.bodyMedium,
                )
                SoftButton("Get a key", Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_KEY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Box(Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    if (key.isEmpty()) Text("Paste key", color = c.secondary, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(key, { key = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), modifier = Modifier.fillMaxWidth())
                }
                PrimaryButton("Save", Modifier.fillMaxWidth(), enabled = key.isNotBlank() && !busy) {
                    busy = true; error = null
                    scope.launch {
                        try { save(key.trim()); key = ""; onDone() } catch (e: Exception) {
                            error = e.message ?: "Couldn't save the key. Try again."
                        } finally { busy = false }
                    }
                }
                error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
