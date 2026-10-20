package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.ui.DotFigure
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

/**
 * What the "set up an agent" panel shows, from what the paired computer told us.
 * [Windows] is the fallback for a computer whose Hypurr can't install Hypurr Agent
 * (an older Windows Hypurr, or an unsupported CPU): it lists agents the user may already have.
 */
enum class AgentSetupMode { NotPaired, Windows, OneTap }

fun agentSetupMode(hello: Hello?): AgentSetupMode {
    if (hello == null || hello.hostId.isEmpty() && hello.os.isEmpty()) return AgentSetupMode.NotPaired
    val b = hello.builtinAgent
    return when {
        b != null && (b.available || b.installed) -> AgentSetupMode.OneTap
        b != null && !b.needsInstall -> AgentSetupMode.Windows
        isWindows(hello.os) -> AgentSetupMode.Windows
        else -> AgentSetupMode.OneTap
    }
}

fun isWindows(os: String) = os.lowercase().startsWith("win")

/** Progress of the one-tap Hypurr Agent install (Windows, Linux and Mac). */
data class AgentInstallState(val busy: Boolean = false, val done: Boolean = false, val error: String? = null)

/** An agent the user may already have, with plain install and sign-in steps (Windows commands). */
data class KnownAgent(val id: String, val name: String, val install: String, val signIn: String)

val KnownAgents = listOf(
    KnownAgent("claude", "Claude Code", "npm install -g @anthropic-ai/claude-code", "Run  claude  once and sign in with your Claude account."),
    KnownAgent("codex", "Codex", "npm install -g @openai/codex", "Run  codex  once and pick \"Sign in with ChatGPT\"."),
    KnownAgent("gemini", "Gemini CLI", "npm install -g @google/gemini-cli", "Run  gemini  once and pick \"Login with Google\"."),
    KnownAgent("cursor", "Cursor CLI", "irm 'https://cursor.com/install?win32=true' | iex", "Run  cursor-agent login  and finish in the browser."),
)

@Composable
fun AgentSetupPanel(
    hello: Hello?,
    install: AgentInstallState = AgentInstallState(),
    onInstall: () -> Unit = {},
    onPair: () -> Unit = {},
    onCheckAgain: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val c = Hypurr.colors
    val mode = agentSetupMode(hello)
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            when (mode) {
                AgentSetupMode.NotPaired -> {
                    Title("Pair with your PC", "Hypurr runs the agent on your computer. Connect it first.")
                    Steps(listOf(
                        "On your PC, download hypurr-host, or double-click \"1 - Start Hypurr\" if you already have it.",
                        "Double-click \"2 - Pair phone\" (or run  hypurr-host pair ). A QR appears.",
                        "Tap Pair with your PC below and scan that QR. Phone and PC must be on the same Wi-Fi.",
                    ))
                }
                AgentSetupMode.Windows -> {
                    Title("Use an agent you already have", "Update Hypurr on ${hello?.name?.ifEmpty { null } ?: "your PC"} to install Hypurr Agent with one tap. Until then, Hypurr can drive one of these.")
                    AgentList(hello?.backends.orEmpty())
                    Spacer(Modifier.height(12.dp))
                    Text("After installing, tap Check again.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                AgentSetupMode.OneTap -> {
                    val win = isWindows(hello?.os.orEmpty())
                    Title("Install Hypurr Agent", "One tap installs it on ${hello?.name?.ifEmpty { null } ?: "your computer"} with free models. No other AI account needed." +
                        if (win) " No admin rights needed; it goes in your own AppData folder." else "")
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp).semantics { contentDescription = "Install status" }) {
                            val (label, color) = when {
                                install.busy -> "Installing on your computer… this can take a minute." to c.text
                                install.done -> "Installed. Hypurr Agent is ready." to c.text
                                install.error != null -> "Install failed: ${install.error}" to c.needsText
                                else -> "Not installed yet." to c.secondary
                            }
                            Text(label, color = color, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Eyebrow("Or use one you have")
                    Spacer(Modifier.height(8.dp))
                    AgentList(hello?.backends.orEmpty(), windows = win)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
            when (mode) {
                AgentSetupMode.NotPaired -> PrimaryButton("Pair with your PC", Modifier.fillMaxWidth(), onClick = onPair)
                AgentSetupMode.Windows -> PrimaryButton("Check again", Modifier.fillMaxWidth(), onClick = onCheckAgain)
                AgentSetupMode.OneTap -> when {
                    install.done -> PrimaryButton("Done", Modifier.fillMaxWidth(), onClick = onClose)
                    else -> PrimaryButton(
                        when { install.busy -> "Installing…"; install.error != null -> "Try again"; else -> "Install Hypurr Agent" },
                        Modifier.fillMaxWidth(), enabled = !install.busy, onClick = onInstall,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SoftButton("Close", Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

@Composable
private fun Title(title: String, hint: String) {
    val c = Hypurr.colors
    Text(title, style = MaterialTheme.typography.headlineLarge, color = c.text)
    Spacer(Modifier.height(6.dp))
    Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.secondary)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Steps(lines: List<String>) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            lines.forEachIndexed { i, line ->
                if (i > 0) HairlineDivider()
                Row(Modifier.padding(vertical = 12.dp)) {
                    DotFigure("${i + 1}", SignalType.figureSmall, c.text, Modifier.width(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(line, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AgentList(backends: List<Backend>, windows: Boolean = true) {
    val c = Hypurr.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KnownAgents.forEach { a ->
            val b = backends.firstOrNull { it.id == a.id }
            val installed = b?.installed == true
            SignalCard(Modifier.fillMaxWidth(), emphasis = installed) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, color = c.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Pill(
                            when { !installed -> "Not found"; b?.signedIn == false -> "Sign in needed"; else -> "Installed" },
                            if (installed && b?.signedIn != false) c.text else c.secondary,
                        )
                    }
                    if (!installed) {
                        Text("Install (in a terminal):", color = c.secondary, style = MaterialTheme.typography.bodySmall)
                        Text(if (windows) a.install else b?.installHint?.ifEmpty { null } ?: a.install,
                            color = c.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    if (!installed || b?.signedIn == false) Text(a.signIn, color = c.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
