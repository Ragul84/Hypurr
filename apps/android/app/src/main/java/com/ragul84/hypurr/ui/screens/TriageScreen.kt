package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.CodePlace
import com.ragul84.hypurr.model.Redaction
import com.ragul84.hypurr.model.TriageResult
import com.ragul84.hypurr.model.TriageSource
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalChip
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

/** Fix from a log. Secrets are removed on the computer before any agent sees the text. */
data class TriageUiState(
    val input: String = "",
    /** Where the text came from: "Screenshot · text read on this phone", "CI · Android build". */
    val sourceLabel: String? = null,
    /** Live preview of what the computer will remove. */
    val preview: List<Redaction> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: String = "Reading the log",
    val result: TriageResult? = null,
    val showTicket: Boolean = false,
    val ticket: String = "",
    val sources: List<TriageSource>? = null,
    val pickingSource: Boolean = false,
    val error: String? = null,
    val projectName: String? = null,
    val copied: Boolean = false,
)

fun redactionWords(list: List<Redaction>): String = list.joinToString(" · ") {
    val noun = when (it.kind) {
        "token" -> "token"
        "privateKey" -> "private key"
        "password" -> "password"
        "credentials" -> "login in a URL"
        "email" -> "email"
        "ip" -> "IP address"
        else -> it.kind
    }
    "${it.count} $noun${if (it.count == 1) "" else if (noun.endsWith("s")) "es" else "s"}"
}

@Composable
fun TriageScreen(
    state: TriageUiState,
    onBack: () -> Unit = {},
    onChange: (TriageUiState) -> Unit = {},
    onPaste: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onFromProject: () -> Unit = {},
    onPickSource: (TriageSource) -> Unit = {},
    onExplain: () -> Unit = {},
    onFixIt: () -> Unit = {},
    onTicket: () -> Unit = {},
    onMore: () -> Unit = {},
    onCopyTicket: () -> Unit = {},
    onShareTicket: () -> Unit = {},
) {
    val c = Hypurr.colors
    val r = state.result
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
            Text(if (state.showTicket) "Ticket note" else "Fix from a log", style = MaterialTheme.typography.headlineLarge, color = c.text)
            Spacer(Modifier.height(6.dp))
            Eyebrow(state.projectName ?: "No project folder", color = c.tertiary)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state.showTicket && r != null -> item { TicketCard(state, onChange) }
                state.busy && r == null -> item { BusyCard(state.busyLabel) }
                r == null -> {
                    item { InputCard(state, onChange, onPaste, onScreenshot, onFromProject) }
                    if (state.pickingSource) item { SourcesCard(state.sources, onPickSource) }
                    state.error?.let { e -> item { ErrorNote(e) } }
                }
                else -> {
                    item { ResultCard("What broke") { Text(r.triage.broke, color = c.text, style = MaterialTheme.typography.titleMedium) } }
                    item {
                        ResultCard("Likely cause", trailing = { Confidence(r.triage.confidence) }) {
                            Text(r.triage.cause, color = c.secondary, style = MaterialTheme.typography.bodyLarge)
                            if (r.triage.confidenceWhy.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(r.triage.confidenceWhy, color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (r.triage.places.isNotEmpty()) item { PlacesCard(r.triage.places) }
                    if (r.triage.fix.isNotEmpty()) {
                        item {
                            ResultCard("How to fix") {
                                r.triage.fix.forEachIndexed { i, step ->
                                    if (i > 0) Spacer(Modifier.height(8.dp))
                                    Row {
                                        Text("${i + 1}", color = c.tertiary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(20.dp))
                                        Text(step, color = c.text, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                    if (r.triage.more.isNotBlank()) {
                        item { ResultCard("In more detail") { Text(r.triage.more, color = c.secondary, style = MaterialTheme.typography.bodyLarge) } }
                    }
                    item { RemovedCard(r.redactions, r.by) }
                    if (state.busy) item { BusyCard(state.busyLabel) }
                    state.error?.let { e -> item { ErrorNote(e) } }
                }
            }
        }
        Column(Modifier.background(c.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            when {
                state.showTicket && r != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(if (state.copied) "Copied" else "Copy", Modifier.weight(1f),
                            icon = signalIcon(if (state.copied) SignalIcons.Check else SignalIcons.Paste), onClick = onCopyTicket)
                        PrimaryButton("Share", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send), onClick = onShareTicket)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Nothing is sent until you share it.", color = c.tertiary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                r != null -> {
                    PrimaryButton("Fix it", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.FixError), onClick = onFixIt)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Write a ticket", Modifier.weight(1f), icon = signalIcon(SignalIcons.Docs), onClick = onTicket)
                        SoftButton("Explain more", Modifier.weight(1f), icon = signalIcon(SignalIcons.Explain), onClick = onMore)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Fix it runs a task on a safe branch. You approve risky steps and can undo.", color = c.tertiary,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                !state.busy -> PrimaryButton("Explain this error", Modifier.fillMaxWidth(), enabled = state.input.isNotBlank(),
                    icon = signalIcon(SignalIcons.Explain), onClick = onExplain)
            }
        }
    }
}

@Composable
private fun InputCard(state: TriageUiState, onChange: (TriageUiState) -> Unit, onPaste: () -> Unit, onScreenshot: () -> Unit, onFromProject: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Eyebrow(state.sourceLabel ?: "Log, error or stack trace")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2)
                .padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (state.input.isEmpty()) {
                    Text("Paste it here, share a screenshot into Hypurr, or pick a failing run from your project.",
                        color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(state.input, { onChange(state.copy(input = it, error = null)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 156.dp, max = 320.dp),
                    textStyle = SignalType.mono.copy(color = c.text), cursorBrush = SolidColor(c.text))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SoftButton("Paste", Modifier.weight(1f), icon = signalIcon(SignalIcons.Paste), onClick = onPaste)
                SoftButton("Image", Modifier.weight(1f), icon = signalIcon(SignalIcons.Image), onClick = onScreenshot)
                SoftButton("Project", Modifier.weight(1f), icon = signalIcon(SignalIcons.Folder), onClick = onFromProject)
            }
            if (state.preview.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HairlineDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(signalIcon(SignalIcons.Shield), null, tint = c.text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Removed before any agent sees it: ${redactionWords(state.preview)}", color = c.secondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(sources: List<TriageSource>?, onPick: (TriageSource) -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            when {
                sources == null -> Text("Looking for failing runs and test output…", color = c.secondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp, 12.dp))
                sources.isEmpty() -> Text("No failing CI runs or recent test output in this project.", color = c.secondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp, 12.dp))
                else -> sources.forEachIndexed { i, s ->
                    if (i > 0) HairlineDivider(Modifier.padding(start = 54.dp))
                    Row(Modifier.fillMaxWidth().pressable(s.title) { onPick(s) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                            Icon(signalIcon(if (s.kind == "ci") SignalIcons.Branch else SignalIcons.File), null, tint = c.text, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.detail, color = c.tertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.kind == "ci") Text("FAILED", color = c.needsText, style = SignalType.eyebrow)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(title, Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** How sure: three dots, ink only (red is reserved for "needs you"). */
@Composable
private fun Confidence(level: String) {
    val c = Hypurr.colors
    val n = when (level) { "high" -> 3; "medium" -> 2; else -> 1 }
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(Modifier.padding(end = 3.dp).size(7.dp).clip(CircleShape).background(if (i < n) c.text else c.dotOff))
        }
        Spacer(Modifier.width(6.dp))
        Text("${level.replaceFirstChar { it.uppercase() }} confidence", color = c.secondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlacesCard(places: List<CodePlace>) {
    val c = Hypurr.colors
    ResultCard("Where") {
        places.forEachIndexed { i, p ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            SignalChip(signalIcon(SignalIcons.File), "${p.path.substringAfterLast('/')}:${p.line}")
            Text(p.path.substringBeforeLast('/', ""), color = c.tertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (p.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(p.note, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (p.snippet.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    p.snippet.lines().forEach { line ->
                        val hit = line.trimStart().startsWith("${p.line}|")
                        Text(line, color = if (hit) c.text else c.tertiary, style = SignalType.mono, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemovedCard(redactions: List<Redaction>, by: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon(SignalIcons.Shield), null, tint = c.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (redactions.isEmpty()) "No secrets found in the log" else "Removed before the agent saw it",
                    color = c.text, style = MaterialTheme.typography.titleSmall)
            }
            if (redactions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    redactions.forEach { SignalChip(signalIcon(SignalIcons.Lock), "${redactionWords(listOf(it))} · ${it.hint}") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(if (by == "agent") "Explained by your agent from the log and your code." else "No agent answered: this is the log's first error only.",
                color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TicketCard(state: TriageUiState, onChange: (TriageUiState) -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Eyebrow("For Jira, a teammate or your lead")
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(14.dp)) {
                BasicTextField(state.ticket, { onChange(state.copy(ticket = it, copied = false)) }, Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text), cursorBrush = SolidColor(c.text))
            }
        }
    }
}

@Composable
private fun BusyCard(label: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DotCat(c.text, 64.dp, off = c.dotOff)
            Spacer(Modifier.height(16.dp))
            Text(label, color = c.text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Secrets are removed on your computer first.", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorNote(message: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth(), emphasis = true) {
        Column(Modifier.padding(16.dp)) {
            Eyebrow("Couldn't do that", color = c.needsText, dot = c.needs)
            Spacer(Modifier.height(6.dp))
            Text(message, color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
