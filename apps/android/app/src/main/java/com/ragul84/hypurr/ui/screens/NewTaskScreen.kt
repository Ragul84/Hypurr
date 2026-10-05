package com.ragul84.hypurr.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.FlowOrb
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.templateIcon
import com.ragul84.hypurr.ui.theme.ColorFlow
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
    val busy: Boolean = false,
    val error: String? = null,
    /** Screenshots and files to send with the task. */
    val files: List<PickedFile> = emptyList(),
    /** The GitHub issue or Jira ticket it starts from. */
    val issue: Issue? = null,
    /** The issue picker: open, and what the host found (null while loading). */
    val pickingIssue: Boolean = false,
    val issues: IssueList? = null,
) {
    /** What the router reads: the goal, or the picked issue. */
    val routeText: String get() = listOf(goal, issue?.title.orEmpty(), input).filter { it.isNotBlank() }.joinToString(" ")
    val selectedTemplate: TaskTemplate? get() = setup?.templates?.firstOrNull { it.id == template }
    val projectPath: String? get() = project ?: route?.project?.path
    val projectName: String? get() = setup?.projects?.firstOrNull { it.path == projectPath }?.name
        ?: route?.project?.takeIf { it.path == projectPath }?.name ?: projectPath?.substringAfterLast('/')
    val agentId: String? get() = agent ?: route?.agent?.id
    val agentName: String? get() = setup?.agents?.firstOrNull { it.id == agentId }?.name ?: route?.agent?.name ?: agentId
    val canStart: Boolean get() = !busy && (goal.isNotBlank() || template != null || issue != null) && projectPath != null && agentId != null
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
) {
    val c = Hypurr.colors
    val template = state.selectedTemplate
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().imePadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("New task", style = MaterialTheme.typography.headlineMedium, color = c.text)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("What do you need?", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(top = 4.dp))
            Text("Describe it in your own words. Hypurr picks the project and the agent, and keeps your code safe.",
                color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Field(state.goal, template?.goalHint?.takeIf { it.isNotEmpty() } ?: "e.g. Fix the login bug in ticket 142",
                minHeight = 96.dp) { onChange(state.copy(goal = it, error = null)) }
            // Screenshot / error paste, and tickets.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(Icons.Rounded.Image, "Add screenshot", onAddImage)
                ActionChip(Icons.Rounded.ContentPaste, "Paste", onPaste)
                if (state.setup?.integrations?.anyIssues == true) ActionChip(Icons.Rounded.TaskAlt, "From an issue", onPickIssue)
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

            Text("OR START FROM A TEMPLATE", color = c.tertiary, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 8.dp))
            val templates = state.setup?.templates.orEmpty()
            if (templates.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { FlowOrb(32.dp) }
            }
            templates.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        TemplateCard(t, t.id == state.template, Modifier.weight(1f)) {
                            onChange(state.copy(template = if (state.template == t.id) null else t.id, error = null))
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
            Plan(state, onChange)
            AnimatedVisibility(state.error != null) {
                Text(state.error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp, start = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
        FlowButton(if (state.busy) "Starting…" else "Start task", Modifier.fillMaxWidth().padding(16.dp),
            enabled = state.canStart, icon = Icons.Rounded.PlayArrow, onClick = onStart)
    }
}

@Composable
private fun Field(value: String, hint: String, minHeight: androidx.compose.ui.unit.Dp, mono: Boolean = false, onChange: (String) -> Unit) {
    val c = Hypurr.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = c.text, fontFamily = if (mono) FontFamily.Monospace else null,
        fontSize = if (mono) 14.sp else MaterialTheme.typography.bodyLarge.fontSize)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight).glass(RoundedCornerShape(22.dp), c.surface).padding(16.dp)) {
        if (value.isEmpty()) Text(hint, color = c.tertiary, style = style.copy(color = c.tertiary))
        BasicTextField(value, onChange, textStyle = style, cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TemplateCard(t: TaskTemplate, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill by animateColorAsState(if (selected) c.accent.copy(alpha = 0.16f) else c.surface, Motion.effects(), label = "tpl")
    Column(modifier.heightIn(min = 104.dp).glass(RoundedCornerShape(22.dp), fill)
        .pressable(t.title, role = Role.RadioButton, onClick = onClick).padding(14.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
            .background(if (selected) ColorFlow.linear() else SolidColor(c.accent.copy(alpha = 0.12f))), contentAlignment = Alignment.Center) {
            Icon(templateIcon(t.icon), null, tint = if (selected) ColorFlow.FlowInk else c.accent, modifier = Modifier.size(19.dp))
        }
        Text(t.title, color = c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.summary, color = c.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Hypurr's pick of project and agent, why, and the safety promise. Each pick can be changed in place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Plan(state: NewTaskUiState, onChange: (NewTaskUiState) -> Unit) {
    val c = Hypurr.colors
    var choosing by remember { mutableStateOf<String?>(null) }
    Text("HYPURR'S PLAN", color = c.tertiary, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), c.surface).padding(16.dp).animateContentSize(Motion.spatialDefault()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlanRow(Icons.Rounded.Folder, "Project", state.projectName ?: "Pick a project") { choosing = if (choosing == "project") null else "project" }
        AnimatedVisibility(choosing == "project") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.setup?.projects.orEmpty().forEach { p ->
                    Choice(p.name, p.path == state.projectPath) {
                        onChange(state.copy(project = p.path))
                        choosing = null
                    }
                }
                if (state.setup?.projects.isNullOrEmpty()) {
                    Text("No projects yet. Add one on your computer, or create a bot in a project folder.", color = c.secondary,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PlanRow(Icons.Rounded.SmartToy, "Agent", state.agentName ?: "No agent installed") { choosing = if (choosing == "agent") null else "agent" }
        AnimatedVisibility(choosing == "agent") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.setup?.agents.orEmpty().forEach { a ->
                    Choice(a.name, a.id == state.agentId) {
                        onChange(state.copy(agent = a.id))
                        choosing = null
                    }
                }
            }
        }
        val reason = when {
            state.project != null || state.agent != null -> "You picked this yourself."
            else -> state.route?.reason
        }
        if (!reason.isNullOrEmpty()) Text(reason, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.success.copy(alpha = 0.10f)).padding(12.dp),
            verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Shield, null, tint = c.success, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Runs on its own branch with a checkpoint after every step. You can go back with one tap, and main stays untouched.",
                color = c.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlanRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable("Change $label", onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(68.dp))
        Text(value, color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text("Change", color = c.accent, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill by animateColorAsState(if (selected) c.accent else c.accent.copy(alpha = 0.10f), Motion.effects(), label = "choice")
    Text(text, color = if (selected) c.onAccent else c.accent, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(fill).pressable(text, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.10f)).pressable(label, onClick = onClick)
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
    Box(Modifier.size(width = if (bitmap != null) 76.dp else 150.dp, height = 76.dp).glass(RoundedCornerShape(16.dp), c.surface)) {
        if (bitmap != null) {
            Image(bitmap, file.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(file.name, color = c.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(RoundedCornerShape(50))
            .background(c.bg.copy(alpha = 0.85f)).pressable("Remove ${file.name}", onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, null, tint = c.text, modifier = Modifier.size(14.dp))
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
    Row(modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), c.surface)
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
        if (onRemove != null) IconBubble(Icons.Rounded.Close, "Remove the issue", onClick = onRemove)
    }
}

/** Open issues and tickets from GitHub and Jira; tap one to start from it. */
@Composable
private fun IssuePicker(list: IssueList?, onPick: (Issue) -> Unit, onClose: () -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).glass(RoundedCornerShape(22.dp), c.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pick an issue", color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconBubble(Icons.Rounded.Close, "Close", onClick = onClose)
        }
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { FlowOrb(28.dp) }
            list.issues.isEmpty() && list.errors.isEmpty() ->
                Text("No open issues assigned to you.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        list?.issues.orEmpty().forEach { i -> IssueRow(i, onClick = { onPick(i) }) }
        list?.errors.orEmpty().forEach { e ->
            Text(e.message, color = c.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}
