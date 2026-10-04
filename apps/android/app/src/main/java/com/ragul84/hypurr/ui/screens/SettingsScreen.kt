package com.ragul84.hypurr.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskTemplate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.LinkPill
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.theme.ColorFlow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

data class SettingsUiState(
    val computer: Computer,
    val link: LinkState,
    val theme: ThemeMode,
    val dynamicColor: Boolean,
    val notifications: Boolean,
    /** Firebase is configured (not the placeholder google-services.json). */
    val pushAvailable: Boolean,
    val version: String,
    val dynamicSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    /** The computer's safety-net settings; null until loaded. */
    val safety: SafetySettings? = null,
    val customTemplates: List<TaskTemplate> = emptyList(),
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onNotifications: (Boolean) -> Unit,
    onForget: () -> Unit,
    onSafety: (SafetySettings) -> Unit = {},
    onAddTemplate: (title: String, prompt: String) -> Unit = { _, _ -> },
    onDeleteTemplate: (String) -> Unit = {},
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = c.text)
        }
        Section("Computer") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(ColorFlow.linear()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Computer, null, tint = ColorFlow.FlowInk)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.computer.name, style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(state.computer.id, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.tertiary)
                }
                LinkPill(state.link)
            }
            Detail("Direct", state.computer.urls.joinToString("\n").ifEmpty { "None" })
            Detail("Cloud relay", state.computer.cloud ?: "Off on this computer")
            SoftButton("Forget this computer", Modifier.fillMaxWidth().padding(top = 6.dp), tint = c.danger, onClick = onForget)
        }
        Section("Appearance") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.bg.copy(alpha = 0.6f)).padding(4.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val selected = mode == state.theme
                    val fill by animateColorAsState(if (selected) c.accent else c.bg.copy(alpha = 0f), Motion.effects(), label = "seg")
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(fill)
                        .pressable(mode.name, role = Role.RadioButton) { onTheme(mode) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(mode.name, color = if (selected) c.onAccent else c.secondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            ToggleRow("Wallpaper colours", if (state.dynamicSupported) "Use Android's dynamic colour instead of the Hypurr palette"
            else "Needs Android 12 or later", state.dynamicColor && state.dynamicSupported, state.dynamicSupported, onDynamic)
        }
        state.safety?.let { safety ->
            Section("Safety net") {
                Detail("Protected", safety.protectedBranches.joinToString(", ").ifEmpty { "None" })
                ToggleRow("Block pushes to protected branches", "Agents can't push to these branches or force-push; they're refused without asking",
                    safety.blockProtected, true) { onSafety(safety.copy(blockProtected = it)) }
                ToggleRow("Always ask for high risk", "Even bots set to approve automatically ask before risky actions",
                    safety.alwaysAskHigh, true) { onSafety(safety.copy(alwaysAskHigh = it)) }
                ToggleRow("Only git projects", "Refuse tasks in folders without git, where Hypurr can't save checkpoints",
                    safety.requireGit, true) { onSafety(safety.copy(requireGit = it)) }
            }
            Section("My templates") {
                if (state.customTemplates.isEmpty()) {
                    Text("Save prompts you use often. They show up next to the built-in templates.", color = c.secondary,
                        style = MaterialTheme.typography.bodySmall)
                }
                state.customTemplates.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = c.text, style = MaterialTheme.typography.titleSmall)
                            Text(t.prompt, color = c.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        IconBubble(Icons.Rounded.Delete, "Delete ${t.title}", tint = c.danger, fill = c.danger.copy(alpha = 0.1f), size = 34.dp) {
                            onDeleteTemplate(t.id)
                        }
                    }
                }
                AddTemplate(onAddTemplate)
            }
        }
        Section("Notifications") {
            ToggleRow("Alerts", if (state.pushAvailable) "Needs you, done and failed, sealed end to end"
            else "Push isn't configured in this build (placeholder Firebase project)", state.notifications, true, onNotifications)
        }
        Section("About") {
            Detail("Version", state.version)
            Detail("Remote screen", "Not on Android yet. Use the iPhone or Mac app.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AddTemplate(onAdd: (String, String) -> Unit) {
    val c = Hypurr.colors
    var open by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    if (!open) {
        SoftButton("Add a template", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) { open = true }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallField(title, "Name, e.g. Add API docs") { title = it }
        SmallField(prompt, "What the agent should do. Use {goal} for what you type.", minLines = 3) { prompt = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Save", Modifier.weight(1f)) {
                if (title.isNotBlank() && prompt.isNotBlank()) {
                    onAdd(title.trim(), prompt.trim())
                    title = ""; prompt = ""; open = false
                }
            }
            SoftButton("Cancel", Modifier.weight(1f), tint = c.secondary) { open = false }
        }
    }
}

@Composable
private fun SmallField(value: String, hint: String, minLines: Int = 1, onChange: (String) -> Unit) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bg.copy(alpha = 0.6f)).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.text.BasicTextField(value, onChange, minLines = minLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Hypurr.colors
    Text(title.uppercase(), color = c.tertiary, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun Detail(label: String, value: String) {
    val c = Hypurr.colors
    Row {
        Text(label, color = c.secondary, modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A Hypurr toggle: a pill whose knob springs across. */
@Composable
private fun ToggleRow(title: String, subtitle: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.fillMaxWidth().pressable(title, role = Role.Switch, enabled = enabled) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.text else c.tertiary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        val knob by animateDpAsState(if (on) 22.dp else 2.dp, Motion.spatialFast(), label = "knob")
        Box(Modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(50))
            .background(if (on) ColorFlow.linear() else androidx.compose.ui.graphics.SolidColor(c.border))) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(26.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White))
        }
    }
}
