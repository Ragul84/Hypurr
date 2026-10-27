package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.theme.SignalType
import com.ragul84.hypurr.ui.DotFigure
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.signalIcon

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.motion.DotCatMark
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.panel
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.templateIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

data class NewTaskUiState(
    val setup: TaskSetup? = null,
    val goal: String = "",
    val template: String? = null,
    val input: String = "",
    val route: TaskRoute? = null,
    /** The user's own picks; null = Hypurr's. */
    val project: String? = null,
    val agent: String? = null,
    /** Model for the agent; null = the agent's own default. */
    val model: String? = null,
    /** Models each agent offers (only runnable ones: no gateway models without the gateway). */
    val models: Map<String, List<com.ragul84.hypurr.model.AgentModel>> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Screenshots and files to send with the task. */
    val files: List<PickedFile> = emptyList(),
    /** The GitHub issue or Jira ticket it starts from. */
    val issue: Issue? = null,
    /** The issue picker: open, and what the host found (null while loading). */
    val pickingIssue: Boolean = false,
    val issues: IssueList? = null,
    /** Some of the goal was said by voice (adds a note for the agent). */
    val spoken: Boolean = false,
) {
    /** What the router reads: the goal, or the picked issue. */
    val routeText: String get() = listOf(goal, issue?.title.orEmpty(), input).filter { it.isNotBlank() }.joinToString(" ")
    val selectedTemplate: TaskTemplate? get() = setup?.templates?.firstOrNull { it.id == template }
    /** Required: the user's own folder pick (or the one they used last). Never a guess. */
    val projectPath: String? get() = project
    val projectName: String? get() = setup?.projects?.firstOrNull { it.path == projectPath }?.name
        ?: route?.project?.takeIf { it.path == projectPath }?.name ?: projectPath?.substringAfterLast('/')
    /** The picked agent, else the computer's suggestion (an installed, signed-in agent) when it is runnable. */
    /** The task runs on Hypurr's models: free on the user's own account, or paid from credits. */
    val usesHypurrModels: Boolean get() = agentId == "hypurr-agent" && (model ?: "hypurr/").startsWith("hypurr/")
    val agentId: String? get() = agent?.takeIf { a -> setup == null || setup.agents.any { it.id == a } }
        ?: route?.agent?.id?.takeIf { a -> setup?.agents.orEmpty().any { it.id == a } }
        ?: setup?.agents?.singleOrNull()?.id
    val agentName: String? get() = setup?.agents?.firstOrNull { it.id == agentId }?.name ?: route?.agent?.name ?: agentId
    val modelName: String? get() = models[agentId].orEmpty().firstOrNull { it.id == model }?.let { it.name.ifEmpty { it.id } }
    val pickersDone: Boolean get() = projectPath != null && agentId != null
    val canStart: Boolean get() = !busy && (goal.isNotBlank() || template != null || issue != null) && pickersDone
}

/** Plain-language tasks: describe the goal (or tap a template); Hypurr picks project and agent, the user can override. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTaskScreen(
    state: NewTaskUiState,
    onChange: (NewTaskUiState) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onAddImage: () -> Unit = {},
    onPaste: () -> Unit = {},
    onPickIssue: () -> Unit = {},
    /** Opens the project-folder picker (folders listed by the computer). */
    onPickFolder: () -> Unit = {},
    onAddCredits: () -> Unit = {},
    ownFree: Boolean = false,
) {
    val c = Hypurr.colors
    val template = state.selectedTemplate
    var showTemplates by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().imePadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("New task", style = MaterialTheme.typography.headlineMedium, color = c.text)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SetupPickers(state, onChange, onPickFolder)
            Field(state.goal, template?.goalHint?.takeIf { it.isNotEmpty() } ?: "What do you need? e.g. Fix the login bug",
                minHeight = 96.dp) { onChange(state.copy(goal = it, error = null)) }
            VoiceGoal(state, onChange)
            if (state.usesHypurrModels) {
                com.ragul84.hypurr.ui.StartFreeNote(ownFree, onAddCredits, Modifier.padding(top = 8.dp))
            }
            // Screenshot / error paste, and tickets.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(signalIcon(SignalIcons.Image), "Add screenshot", onAddImage)
                ActionChip(signalIcon(SignalIcons.Paste), "Paste", onPaste)
                if (state.setup?.integrations?.anyIssues == true) ActionChip(signalIcon(SignalIcons.Check), "From an issue", onPickIssue)
                if (state.setup?.templates.orEmpty().isNotEmpty()) {
                    ActionChip(signalIcon(SignalIcons.Docs), template?.title ?: "Template") { showTemplates = !showTemplates }
                }
            }
            AnimatedVisibility(state.files.isNotEmpty(), enter = expandVertically(Motion.spatialDefault()) + fadeIn(),
                exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.files.forEach { f -> FileChip(f) { onChange(state.copy(files = state.files - f)) } }
                }
            }
            AnimatedVisibility(state.issue != null, enter = expandVertically(Motion.spatialDefault()) + fadeIn(),
                exit = shrinkVertically() + fadeOut()) {
                state.issue?.let { IssueRow(it, Modifier.padding(top = 10.dp), onRemove = { onChange(state.copy(issue = null)) }) }
            }
            AnimatedVisibility(state.pickingIssue, enter = expandVertically(Motion.spatialDefault()) + fadeIn(),
                exit = shrinkVertically() + fadeOut()) {
                IssuePicker(state.issues, onPick = { i ->
                    onChange(state.copy(issue = i, pickingIssue = false, project = i.project ?: state.project, error = null))
                }, onClose = { onChange(state.copy(pickingIssue = false)) })
            }

            val templates = if (showTemplates) state.setup?.templates.orEmpty() else emptyList()
            if (templates.isNotEmpty()) Spacer(Modifier.height(10.dp))
            templates.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        TemplateCard(t, t.id == state.template, Modifier.weight(1f)) {
                            onChange(state.copy(template = if (state.template == t.id) null else t.id, error = null))
                            showTemplates = false
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            AnimatedVisibility(template?.inputLabel != null, enter = expandVertically(Motion.spatialDefault()) + fadeIn(),
                exit = shrinkVertically() + fadeOut()) {
                Column {
                    Text(template?.inputLabel.orEmpty(), color = c.secondary, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp))
                    Field(state.input, "Paste it here", minHeight = 88.dp, mono = true) { onChange(state.copy(input = it, error = null)) }
                }
            }
            AnimatedVisibility(state.error != null) {
                Text(state.error.orEmpty(), color = c.needsText, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp, start = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(if (state.busy) "Starting…" else "Start task", Modifier.fillMaxWidth().padding(16.dp),
            enabled = state.canStart, icon = signalIcon(SignalIcons.Play), onClick = onStart)
    }
}

@Composable
private fun Field(value: String, hint: String, minHeight: androidx.compose.ui.unit.Dp, mono: Boolean = false, onChange: (String) -> Unit) {
    val c = Hypurr.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = c.text, fontFamily = if (mono) FontFamily.Monospace else null,
        fontSize = if (mono) 14.sp else MaterialTheme.typography.bodyLarge.fontSize)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight).panel(RoundedCornerShape(22.dp), c.surface).padding(16.dp)) {
        if (value.isEmpty()) Text(hint, color = c.tertiary, style = style.copy(color = c.tertiary))
        BasicTextField(value, onChange, textStyle = style, cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TemplateCard(t: TaskTemplate, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill by animateColorAsState(if (selected) c.surface2 else c.surface, Motion.effects(), label = "tpl")
    Column(modifier.heightIn(min = 104.dp).panel(RoundedCornerShape(22.dp), fill)
        .pressable(t.title, role = Role.RadioButton, onClick = onClick).padding(14.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
            .background(if (selected) SolidColor(c.accent) else SolidColor(c.surface2)), contentAlignment = Alignment.Center) {
            Icon(templateIcon(t.icon), null, tint = if (selected) c.onAccent else c.accent, modifier = Modifier.size(19.dp))
        }
        Text(t.title, color = c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.summary, color = c.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Models per agent that can actually run: gateway (free) models only when the computer has the gateway. */
fun runnableModels(hello: com.ragul84.hypurr.model.Hello?): Map<String, List<com.ragul84.hypurr.model.AgentModel>> =
    hello?.backends.orEmpty().associate { b ->
        val gateway = b.gatewayReady == true
        b.id to b.freeModels.filter { gateway || !it.id.startsWith("hypurr/") }
    }.filterValues { it.isNotEmpty() }

/** Required picks at the top: 1 agent (+ model), 2 project folder. Start stays off until both are set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPickers(state: NewTaskUiState, onChange: (NewTaskUiState) -> Unit, onPickFolder: () -> Unit) {
    val c = Hypurr.colors
    var open by remember { mutableStateOf<String?>(null) }
    val agents = state.setup?.agents.orEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerCard("Agent", state.agentName?.let { n -> state.modelName?.let { "$n · $it" } ?: n },
            if (agents.isEmpty()) "No agent ready. Install Claude Code or Codex and sign in." else "Choose an agent",
            open == "agent") { open = if (open == "agent") null else "agent" }
        AnimatedVisibility(open == "agent") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    agents.forEach { a ->
                        Choice(a.name, a.id == state.agentId) {
                            onChange(state.copy(agent = a.id, model = null, error = null))
                            if (state.models[a.id].isNullOrEmpty()) open = null
                        }
                    }
                }
                val models = state.models[state.agentId].orEmpty()
                if (models.isNotEmpty()) {
                    Text("Model", color = c.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Agent's default", state.model == null) { onChange(state.copy(model = null)); open = null }
                        models.forEach { m ->
                            Choice(m.name.ifEmpty { m.id }, m.id == state.model) { onChange(state.copy(model = m.id)); open = null }
                        }
                    }
                    models.firstOrNull { it.id == state.model }?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        PickerCard("Folder", state.projectPath?.let { state.projectName ?: folderName(it) }, "Choose a folder on your PC",
            false, detail = state.projectPath) { open = null; onPickFolder() }
    }
}

@Composable
private fun PickerCard(label: String, value: String?, empty: String, open: Boolean, detail: String? = null, onClick: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth().pressable("$label: ${value ?: empty}", onClick = onClick), emphasis = open) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                Text(value ?: empty, color = if (value != null) c.text else c.secondary, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, color = c.tertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill by animateColorAsState(if (selected) c.accent else c.surface2, Motion.effects(), label = "choice")
    Text(text, color = if (selected) c.onAccent else c.accent, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).pressable(text, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface2).pressable(label, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = c.accent, style = MaterialTheme.typography.labelLarge)
    }
}

/** A picked screenshot (thumbnail) or file (name), with remove. */
@Composable
private fun FileChip(file: PickedFile, onRemove: () -> Unit) {
    val c = Hypurr.colors
    val bitmap = remember(file) {
        if (file.isImage) runCatching { BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)?.asImageBitmap() }.getOrNull() else null
    }
    Box(Modifier.size(width = if (bitmap != null) 76.dp else 150.dp, height = 76.dp).panel(RoundedCornerShape(20.dp), c.surface)) {
        if (bitmap != null) {
            Image(bitmap, file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon(SignalIcons.Docs), null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(file.name, color = c.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(RoundedCornerShape(50))
            .background(c.surface2).pressable("Remove ${file.name}", onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(signalIcon(SignalIcons.Close), null, tint = c.text, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SourceTag(source: String) {
    val c = Hypurr.colors
    val (label, color) = if (source == "jira") "Jira" to c.accent else "GitHub" to c.text
    Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun IssueRow(issue: Issue, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val c = Hypurr.colors
    Row(modifier.fillMaxWidth().panel(RoundedCornerShape(20.dp), c.surface)
        .then(if (onClick != null) Modifier.pressable("${issue.key} ${issue.title}", onClick = onClick) else Modifier)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceTag(issue.source)
                Spacer(Modifier.width(8.dp))
                Text(issue.key, color = c.secondary, style = MaterialTheme.typography.labelLarge)
            }
            Text(issue.title, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (onRemove != null) IconBubble(signalIcon(SignalIcons.Close), "Remove the issue", onClick = onRemove)
    }
}

/** Open issues and tickets from GitHub and Jira; tap one to start from it. */
@Composable
private fun IssuePicker(list: IssueList?, onPick: (Issue) -> Unit, onClose: () -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).panel(RoundedCornerShape(22.dp), c.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pick an issue", color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconBubble(signalIcon(SignalIcons.Close), "Close", onClick = onClose)
        }
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { DotCatMark(28.dp) }
            list.issues.isEmpty() && list.errors.isEmpty() ->
                Text("No open issues assigned to you.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        list?.issues.orEmpty().forEach { i -> IssueRow(i, onClick = { onPick(i) }) }
        list?.errors.orEmpty().forEach { e ->
            Text(e.message, color = c.needsText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Mic under the task box: speak the task in the chosen language; the words land in the box to check. */
@Composable
private fun VoiceGoal(state: NewTaskUiState, onChange: (NewTaskUiState) -> Unit) {
    val c = Hypurr.colors
    val voice = com.ragul84.hypurr.voice.LocalVoiceSettings.current
    val latest = androidx.compose.runtime.rememberUpdatedState(state)
    val mic = com.ragul84.hypurr.voice.rememberVoiceInput(voice.language) { said ->
        val s = latest.value
        onChange(s.copy(goal = listOf(s.goal.trim(), said.trim()).filter { it.isNotEmpty() }.joinToString(" "), spoken = true, error = null))
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        com.ragul84.hypurr.ui.MicButton(mic.listening, voice.language) { if (mic.listening) mic.stop() else mic.start() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (mic.listening) "Listening…" else "Say it in ${voice.language.label}", color = c.text,
                style = MaterialTheme.typography.titleSmall)
            com.ragul84.hypurr.ui.VoiceHint(mic.listening, mic.partial, mic.error, voice.language)
        }
    }
}
