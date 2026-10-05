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
) {
    val selectedTemplate: TaskTemplate? get() = setup?.templates?.firstOrNull { it.id == template }
    val projectPath: String? get() = project ?: route?.project?.path
    val projectName: String? get() = setup?.projects?.firstOrNull { it.path == projectPath }?.name
        ?: route?.project?.takeIf { it.path == projectPath }?.name ?: projectPath?.substringAfterLast('/')
    val agentId: String? get() = agent ?: route?.agent?.id
    val agentName: String? get() = setup?.agents?.firstOrNull { it.id == agentId }?.name ?: route?.agent?.name ?: agentId
    val canStart: Boolean get() = !busy && (goal.isNotBlank() || template != null) && projectPath != null && agentId != null
}

/** Plain-language tasks: describe the goal (or tap a template); Hypurr picks project and agent, the user can override. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTaskScreen(state: NewTaskUiState, onChange: (NewTaskUiState) -> Unit, onStart: () -> Unit, onBack: () -> Unit) {
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
