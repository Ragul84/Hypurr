package com.ragul84.hypurr.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.launch

/** One vendor cloud the computer can hand a task to, on the user's own account. */
data class CloudVendor(val id: String, val name: String, val ready: Boolean, val needs: String? = null)

/** What the computer said about handing off this task's folder. */
data class CloudOptions(
    val vendors: List<CloudVendor> = emptyList(),
    val repo: String? = null,
    val branch: String? = null,
    val unpushed: Boolean = false,
    val codexEnv: String? = null,
)

/** The phone side of cloud handoff: choose a cloud, say what to do, get a link. */
interface CloudHandoffActions {
    suspend fun options(): CloudOptions
    suspend fun handOff(vendor: String, prompt: String, codexEnv: String?): String
    suspend fun saveCursorKey(key: String)
}

@Composable
fun CloudHandoffDialog(goal: String, actions: CloudHandoffActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { CloudHandoffForm(goal, actions) }
}

/** The dialog's body (tests render it without a window). */
@Composable
fun CloudHandoffForm(goal: String, actions: CloudHandoffActions, initial: CloudOptions? = null) {
    val c = Hypurr.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opts by remember { mutableStateOf(initial) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("Continue this task: $goal") }
    var env by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (opts == null) opts = runCatching { actions.options() }.getOrElse { error = it.message; CloudOptions() }
    }
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Continue in the cloud", color = c.text, style = MaterialTheme.typography.titleLarge)
            Text("Runs on the agent maker's cloud with your own account, so your PC can be off. The result comes back as a pull request or a link.",
                color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            val o = opts
            if (link != null) {
                Text("Started. Follow it there:", color = c.text, style = MaterialTheme.typography.bodyLarge)
                PrimaryButton("Open", Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } else if (o == null) {
                Text("Checking your computer…", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                if (o.repo == null) {
                    Text("This folder isn't on GitHub, so a cloud agent can't open it.", color = c.needsText, style = MaterialTheme.typography.bodyMedium)
                } else if (o.unpushed) {
                    Text("The cloud starts from ${o.branch ?: "your branch"} on GitHub. Push your latest changes first.",
                        color = c.needsText, style = MaterialTheme.typography.bodySmall)
                }
                o.vendors.forEach { v ->
                    val pick = chosen == v.id
                    val label = v.name + when { pick -> "  ✓"; !v.ready && v.id != "cursor" -> "  (not set up)"; else -> "" }
                    if (pick) PrimaryButton(label, Modifier.fillMaxWidth()) {} else
                        SoftButton(label, Modifier.fillMaxWidth(), tint = if (v.ready || v.id == "cursor") c.text else c.tertiary) {
                            if (v.ready || v.id == "cursor") { chosen = v.id; error = null }
                        }
                }
                val v = o.vendors.firstOrNull { it.id == chosen }
                if (v != null) {
                    if (v.id == "codex" && o.codexEnv == null) Field(env, { env = it }, "Codex cloud environment name")
                    if (v.id == "cursor" && !v.ready) {
                        Text("Paste a Cursor API key (Cursor dashboard → API Keys). It's saved only on your computer.",
                            color = c.secondary, style = MaterialTheme.typography.bodySmall)
                        Field(key, { key = it }, "Cursor API key", secret = true)
                    }
                    Field(prompt, { prompt = it }, "What should it do?", single = false)
                    PrimaryButton("Start in the cloud", Modifier.fillMaxWidth(),
                        enabled = !busy && o.repo != null && prompt.isNotBlank() && (v.id != "cursor" || v.ready || key.isNotBlank())) {
                        busy = true; error = null
                        scope.launch {
                            try {
                                if (v.id == "cursor" && !v.ready) actions.saveCursorKey(key.trim())
                                link = actions.handOff(v.id, prompt.trim(), env.trim().ifEmpty { null })
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't hand it off. Try again."
                            } finally { busy = false }
                        }
                    }
                }
            }
            error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false, single: Boolean = true) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxWidth().background(c.surface2, RoundedCornerShape(12.dp)).padding(14.dp)) {
        if (value.isEmpty()) Text(hint, color = c.secondary, style = MaterialTheme.typography.bodyLarge)
        BasicTextField(value, onChange, singleLine = single, cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), modifier = Modifier.fillMaxWidth())
    }
}
