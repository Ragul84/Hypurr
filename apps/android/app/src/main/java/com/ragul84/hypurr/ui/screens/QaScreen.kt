package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.QaCase
import com.ragul84.hypurr.model.QaFramework
import com.ragul84.hypurr.model.QaRunResult
import com.ragul84.hypurr.qa.QaFormat
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.Segmented
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalChip
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

enum class QaStep { Input, Cases, Export, Running, Result }

/** QA test writer: cases in plain words, exported or automated. Nothing is posted anywhere. */
data class QaUiState(
    val step: QaStep = QaStep.Input,
    /** 0 ticket, 1 requirements, 2 screen or feature */
    val source: Int = 1,
    val input: String = "",
    val projects: List<Project> = emptyList(),
    val project: String? = null,
    val needsSetup: String? = null,
    val cases: List<QaCase> = emptyList(),
    val by: String = "agent",
    val frameworks: List<QaFramework> = emptyList(),
    val framework: String? = null,
    /** Case ids picked for automation. */
    val picked: Set<String> = emptySet(),
    /** The case open for editing. */
    val editing: String? = null,
    /** Explain each test (people new to QA). */
    val explain: Boolean = false,
    val format: QaFormat = QaFormat.Csv,
    val botId: String? = null,
    val taskId: String? = null,
    val agentStatus: String = "working",
    val result: QaRunResult? = null,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val error: String? = null,
    val copied: Boolean = false,
) {
    val sourceName: String get() = listOf("ticket", "text", "feature")[source]
    val projectName: String? get() = projects.firstOrNull { it.path == project }?.name ?: project?.substringAfterLast('/')
    val selectedFramework: QaFramework? get() = frameworks.firstOrNull { it.id == framework } ?: frameworks.firstOrNull()
    val automated: Set<String> get() = result?.automated?.toSet().orEmpty()

    fun update(id: String, f: (QaCase) -> QaCase) = copy(cases = cases.map { if (it.id == id) f(it) else it })

    /** A blank case at the end, open for editing. */
    fun addCase(): QaUiState {
        val n = (cases.mapNotNull { it.id.removePrefix("TC-").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val c = QaCase(id = "TC-$n", title = "", steps = listOf(""), expected = "")
        return copy(cases = cases + c, editing = c.id)
    }
}

fun kindLabel(k: String) = when (k) { "negative" -> "Negative"; "edge" -> "Edge case"; else -> "Positive" }
fun priorityLabel(p: String) = when (p) { "high" -> "High"; "low" -> "Low"; else -> "Medium" }
private fun next(v: String, all: List<String>) = all[(all.indexOf(v) + 1).mod(all.size)]

@Composable
fun QaScreen(
    state: QaUiState,
    onBack: () -> Unit = {},
    onChange: (QaUiState) -> Unit = {},
    onPaste: () -> Unit = {},
    onWrite: () -> Unit = {},
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onAutomate: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onRun: () -> Unit = {},
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
            Text(when (state.step) {
                QaStep.Input -> "Write tests"
                QaStep.Cases -> "Test cases"
                QaStep.Export -> "Export"
                QaStep.Running -> "Automating"
                QaStep.Result -> "Test results"
            }, style = MaterialTheme.typography.headlineLarge, color = c.text)
            Spacer(Modifier.height(6.dp))
            val sub = when (state.step) {
                QaStep.Cases -> "${state.cases.size} cases · ${state.projectName ?: "no project"}"
                else -> state.projectName ?: "Pick a project folder"
            }
            Eyebrow(sub, color = c.tertiary)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.step) {
                QaStep.Input -> {
                    item { InputCard(state, onChange, onPaste) }
                    if (state.projects.size > 1) item { ProjectPicker(state, onChange) }
                    item { ExplainToggle(state, onChange) }
                    state.needsSetup?.let { item { Notice("This tracker isn't set up on the computer. Set it up in Ticket to PR, or paste the requirements instead.") } }
                }
                QaStep.Cases -> {
                    item { Mix(state.cases) }
                    if (state.by != "agent") item { Notice("The agent wasn't reachable, so these are starter cases. Edit them or try again.") }
                    items(state.cases, key = { it.id }) { case -> CaseCard(case, state, onChange) }
                    item { SoftButton("Add a case", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Plus)) { onChange(state.addCase()) } }
                    if (state.frameworks.isNotEmpty()) item { AutomateCard(state, onChange) }
                }
                QaStep.Export -> {
                    item { FormatCard(state, onChange) }
                    item { PreviewCard(state) }
                }
                QaStep.Running -> item { RunningCard(state) }
                QaStep.Result -> state.result?.let { r ->
                    item { ResultCard(r) }
                    item { CoverageCard(state, r) }
                    if (r.output.isNotBlank()) item { OutputCard(r) }
                }
            }
            if (state.busy) item { Busy(state.busyLabel) }
            state.error?.let { item { ErrorNote(it) } }
        }
        if (!state.busy) Bottom(state, onChange, onWrite, onCopy, onShare, onAutomate, onOpenTask, onRun)
    }
}

@Composable
private fun Bottom(state: QaUiState, onChange: (QaUiState) -> Unit, onWrite: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit,
                   onAutomate: () -> Unit, onOpenTask: () -> Unit, onRun: () -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.background(c.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        when (state.step) {
            QaStep.Input -> PrimaryButton("Write test cases", Modifier.fillMaxWidth(), enabled = state.input.isNotBlank(),
                icon = signalIcon(SignalIcons.Tests), onClick = onWrite)
            QaStep.Cases -> {
                if (state.picked.isNotEmpty() && state.frameworks.isNotEmpty()) {
                    PrimaryButton("Automate ${state.picked.size} with ${state.selectedFramework?.name}", Modifier.fillMaxWidth(),
                        icon = signalIcon(SignalIcons.Play), onClick = onAutomate)
                    Spacer(Modifier.height(8.dp))
                    SoftButton("Export or share", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Send)) { onChange(state.copy(step = QaStep.Export, copied = false)) }
                } else {
                    PrimaryButton("Export or share", Modifier.fillMaxWidth(), enabled = state.cases.isNotEmpty(),
                        icon = signalIcon(SignalIcons.Send)) { onChange(state.copy(step = QaStep.Export, copied = false)) }
                }
            }
            QaStep.Export -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(if (state.copied) "Copied" else "Copy", Modifier.weight(1f),
                        icon = signalIcon(if (state.copied) SignalIcons.Check else SignalIcons.Paste), onClick = onCopy)
                    PrimaryButton("Share", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send), onClick = onShare)
                }
                Spacer(Modifier.height(6.dp))
                Text("Hypurr never posts these anywhere. You paste or import them.", color = c.tertiary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            QaStep.Running -> {
                PrimaryButton("Run the tests", Modifier.fillMaxWidth(), enabled = state.agentStatus == "idle", icon = signalIcon(SignalIcons.Play), onClick = onRun)
                Spacer(Modifier.height(8.dp))
                SoftButton("Open the task", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Tasks), onClick = onOpenTask)
            }
            QaStep.Result -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Run again", Modifier.weight(1f), icon = signalIcon(SignalIcons.Refresh), onClick = onRun)
                PrimaryButton("Export cases", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send)) { onChange(state.copy(step = QaStep.Export, copied = false)) }
            }
        }
    }
}

@Composable
private fun QaCard(eyebrow: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(eyebrow, Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, minHeight: Int = 0) {
    val c = Hypurr.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = c.text)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.tertiary))
        BasicTextField(value, onValue, Modifier.fillMaxWidth(), textStyle = style, cursorBrush = SolidColor(c.text))
    }
}

@Composable
private fun InputCard(state: QaUiState, onChange: (QaUiState) -> Unit, onPaste: () -> Unit) {
    QaCard("What to test") {
        Segmented(listOf("Ticket", "Requirements", "Screen"), state.source, Modifier.fillMaxWidth()) { onChange(state.copy(source = it, needsSetup = null)) }
        Spacer(Modifier.height(12.dp))
        Field(state.input, { onChange(state.copy(input = it, error = null)) }, when (state.source) {
            0 -> "A Jira or GitHub link, or a key like SHOP-142"
            2 -> "The screen or feature, e.g. Checkout or Forgot password"
            else -> "Paste the requirements or acceptance criteria"
        }, minHeight = if (state.source == 1) 150 else 56)
        Spacer(Modifier.height(12.dp))
        SoftButton("Paste", icon = signalIcon(SignalIcons.Paste), onClick = onPaste)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectPicker(state: QaUiState, onChange: (QaUiState) -> Unit) {
    QaCard("Project folder") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.projects.forEach { p ->
                SignalChip(signalIcon(if (p.path == state.project) SignalIcons.Check else SignalIcons.Folder), p.name, quiet = p.path != state.project) {
                    onChange(state.copy(project = p.path))
                }
            }
        }
    }
}

@Composable
private fun ExplainToggle(state: QaUiState, onChange: (QaUiState) -> Unit) {
    SignalCard(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            ToggleRow("Explain each test", "Adds a plain sentence on why each test matters. On by default if your profile says you're new to QA.",
                state.explain, true) { onChange(state.copy(explain = it)) }
        }
    }
}

@Composable
private fun Mix(cases: List<QaCase>) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf("positive" to "positive", "negative" to "negative", "edge" to "edge").forEach { (k, label) ->
                Column {
                    Text("${cases.count { it.kind == k }}", color = c.text, style = SignalType.figureSmall)
                    Text(label, color = c.tertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column {
                Text("${cases.count { it.priority == "high" }}", color = c.text, style = SignalType.figureSmall)
                Text("high priority", color = c.tertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Tag(text: String, strong: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Hypurr.colors
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(if (strong) c.text else c.surface2)
        .then(if (onClick != null) Modifier.pressable(text, onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (strong) c.bg else c.secondary, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseCard(case: QaCase, state: QaUiState, onChange: (QaUiState) -> Unit) {
    val c = Hypurr.colors
    val open = state.editing == case.id
    val picked = case.id in state.picked
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().pressable(case.title.ifEmpty { case.id }) { onChange(state.copy(editing = if (open) null else case.id)) },
                verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(case.id, color = c.tertiary, style = SignalType.eyebrow)
                    Spacer(Modifier.height(2.dp))
                    Text(case.title.ifEmpty { "New case" }, color = c.text, style = MaterialTheme.typography.titleSmall)
                }
                if (state.frameworks.isNotEmpty()) {
                    val box = RoundedCornerShape(6.dp)
                    Box(Modifier.padding(start = 10.dp).size(22.dp).clip(box).background(if (picked) c.text else c.surface)
                        .border(1.5.dp, if (picked) c.text else c.border, box)
                        .pressable("Automate ${case.id}") { onChange(state.copy(picked = if (picked) state.picked - case.id else state.picked + case.id)) },
                        contentAlignment = Alignment.Center) {
                        if (picked) Icon(signalIcon(SignalIcons.Check), null, tint = c.bg, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(kindLabel(case.kind), onClick = if (open) ({ onChange(state.update(case.id) { it.copy(kind = next(it.kind, listOf("positive", "negative", "edge"))) }) }) else null)
                Tag(priorityLabel(case.priority), strong = case.priority == "high",
                    onClick = if (open) ({ onChange(state.update(case.id) { it.copy(priority = next(it.priority, listOf("high", "medium", "low"))) }) }) else null)
                if (case.id in state.automated) Tag("Automated")
            }
            if (open) {
                Spacer(Modifier.height(12.dp))
                Label("Title")
                Field(case.title, { v -> onChange(state.update(case.id) { it.copy(title = v) }) }, "What this checks")
                Spacer(Modifier.height(10.dp))
                Label("Steps, one per line")
                Field(case.steps.joinToString("\n"), { v -> onChange(state.update(case.id) { it.copy(steps = v.split("\n")) }) }, "1. Open the cart", minHeight = 80)
                Spacer(Modifier.height(10.dp))
                Label("Expected result")
                Field(case.expected, { v -> onChange(state.update(case.id) { it.copy(expected = v) }) }, "What should happen")
                Spacer(Modifier.height(10.dp))
                Text("Tap the tags to change type and priority.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Delete", Modifier.weight(1f), icon = signalIcon(SignalIcons.Trash)) {
                        onChange(state.copy(cases = state.cases.filter { it.id != case.id }, picked = state.picked - case.id, editing = null))
                    }
                    SoftButton("Done", Modifier.weight(1f), icon = signalIcon(SignalIcons.Check)) { onChange(state.copy(editing = null)) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                case.steps.filter { it.isNotBlank() }.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("${i + 1}", color = c.tertiary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(18.dp))
                        Text(s, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("Expect ", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
                    Text(case.expected, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.explain && case.why.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Why: ${case.why}", color = c.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, color = Hypurr.colors.tertiary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomateCard(state: QaUiState, onChange: (QaUiState) -> Unit) {
    val c = Hypurr.colors
    val auto = state.cases.filter { it.automatable }.map { it.id }.toSet()
    QaCard("Automate", trailing = {
        if (auto.isNotEmpty()) SoftButton(if (state.picked == auto) "Clear" else "Pick suggested") {
            onChange(state.copy(picked = if (state.picked == auto) emptySet() else auto))
        }
    }) {
        Text("Tick cases to turn into automated tests. They're written on a safe branch, then Hypurr runs them and shows the result here.",
            color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.frameworks.forEach { f ->
                val on = f.id == state.selectedFramework?.id
                SignalChip(signalIcon(if (on) SignalIcons.Check else SignalIcons.Tests), f.name, quiet = !on) { onChange(state.copy(framework = f.id)) }
            }
        }
        state.selectedFramework?.takeIf { it.needsDevice }?.let {
            Spacer(Modifier.height(8.dp))
            Text("${it.name} needs a ${if (it.id == "espresso") "phone or emulator" else "browser"} on the computer to run.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FormatCard(state: QaUiState, onChange: (QaUiState) -> Unit) {
    val c = Hypurr.colors
    QaCard("Format") {
        QaFormat.entries.forEachIndexed { i, f ->
            if (i > 0) HairlineDivider()
            Row(Modifier.fillMaxWidth().pressable(f.label) { onChange(state.copy(format = f, copied = false)) }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.label, color = c.text, style = MaterialTheme.typography.titleSmall)
                    Text(f.detail, color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                Box(Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (f == state.format) c.text else c.border, CircleShape), contentAlignment = Alignment.Center) {
                    if (f == state.format) Box(Modifier.size(10.dp).clip(CircleShape).background(c.text))
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(state: QaUiState) {
    val c = Hypurr.colors
    val text = com.ragul84.hypurr.qa.QaExport.export(state.cases, state.format, state.automated)
    QaCard("Preview · ${state.cases.size} cases") {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp)) {
            Text(text.lines().take(10).joinToString("\n"), color = c.secondary, style = SignalType.mono, maxLines = 10, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RunningCard(state: QaUiState) {
    val c = Hypurr.colors
    QaCard("${state.picked.size} cases · ${state.selectedFramework?.name ?: ""}") {
        when (state.agentStatus) {
            "needsInput" -> Eyebrow("Needs you", dot = c.needs, color = c.needsText, pulse = true)
            "working" -> Eyebrow("Agent is writing tests", dot = c.text, color = c.secondary)
            else -> Eyebrow("Tests written", color = c.secondary)
        }
        Spacer(Modifier.height(8.dp))
        Text(when (state.agentStatus) {
            "needsInput" -> "The agent is waiting for your OK on a step. Open the task to approve or deny it."
            "working" -> "It's adding tests to the project on a safe branch. You can undo anything from the task."
            else -> "Run them to see what passes. Hypurr runs ${state.selectedFramework?.command ?: "the tests"} on the computer."
        }, color = c.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ResultCard(r: QaRunResult) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            if (r.ok) Eyebrow("All passed", dot = c.text, color = c.text) else Eyebrow(if (r.timedOut) "Stopped: too slow" else "Some tests failed", dot = c.needs, color = c.needsText)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("${r.passed}", color = c.text, style = SignalType.counter)
                    Text("passed", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                }
                Column {
                    Text("${r.failed}", color = if (r.failed > 0) c.needs else c.text, style = SignalType.counter)
                    Text("failed", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(when {
                r.ok -> "Every test passed with ${r.command} in ${r.seconds}s."
                r.failed > 0 -> "${r.failed} test${if (r.failed == 1L) "" else "s"} failed. Ask the agent in the task to look at why, or check the output below."
                else -> "The run didn't finish cleanly. The output below says why."
            }, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CoverageCard(state: QaUiState, r: QaRunResult) {
    val c = Hypurr.colors
    val byId = state.cases.associateBy { it.id }
    QaCard("Coverage · ${r.automated.size} automated · ${r.manual.size} manual") {
        state.cases.forEachIndexed { i, case ->
            if (i > 0) HairlineDivider(Modifier.padding(vertical = 6.dp))
            val auto = case.id in r.automated
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (auto) c.text else c.dotOff))
                Spacer(Modifier.width(10.dp))
                Text(case.id, color = c.tertiary, style = SignalType.mono, modifier = Modifier.width(48.dp))
                Text(byId[case.id]?.title ?: "", color = c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (auto) "Automated" else "Manual", color = if (auto) c.text else c.tertiary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun OutputCard(r: QaRunResult) {
    val c = Hypurr.colors
    QaCard("Output") {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp)) {
            Text(r.output.lines().takeLast(12).joinToString("\n"), color = c.secondary, style = SignalType.mono)
        }
    }
}

@Composable
private fun Busy(label: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c.text))
            Spacer(Modifier.width(10.dp))
            Text(label.ifEmpty { "Working" } + "…", color = c.secondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, color = Hypurr.colors.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun ErrorNote(text: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(signalIcon(SignalIcons.NeedsYou), null, tint = c.needs, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
