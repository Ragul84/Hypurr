package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SunfieldIcons
import com.ragul84.hypurr.ui.sunfieldVector

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.costLabel
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
    /** Work tools on the computer (no secrets, only hints); null until loaded. */
    val integrations: Integrations? = null,
    val costs: TaskCosts? = null,
    /** The last "Test" result per kind (github, jira, slack, teams). */
    val testResults: Map<String, String> = emptyMap(),
    /** This phone's role on the computer; null until the host says. */
    val you: com.ragul84.hypurr.model.Actor? = null,
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
    onIntegrations: (JsonObject) -> Unit = {},
    onTestIntegration: (String) -> Unit = {},
    onTeamAdmin: () -> Unit = {},
    showHomeTabs: Boolean = false,
    onTab: (HomeTab) -> Unit = {},
) {
    val admin = state.you?.isAdmin != false
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = if (showHomeTabs) (56.dp + 24.dp + 16.dp) else 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!showHomeTabs) {
                IconBubble(sunfieldVector(SunfieldIcons.Back), "Back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
            }
            Text(if (showHomeTabs) "You" else "Settings", style = MaterialTheme.typography.headlineMedium, color = c.text, fontWeight = FontWeight.ExtraBold)
        }
        Section("Computer") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(20.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(sunfieldVector(SunfieldIcons.Host), null, tint = c.onAccent)
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
        Section("Team") {
            Row(Modifier.fillMaxWidth().pressable("Team admin", onClick = onTeamAdmin), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(20.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(sunfieldVector(SunfieldIcons.Shield), null, tint = c.accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Team admin", style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(if (admin) "Activity, rules, people and the audit log" else "The team's rules and who can do what",
                        color = c.secondary, style = MaterialTheme.typography.bodySmall)
                }
                state.you?.let { com.ragul84.hypurr.ui.Pill(it.role, if (it.isAdmin) c.accent else c.success) }
                Icon(sunfieldVector(SunfieldIcons.Link), null, tint = c.tertiary)
            }
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
                    safety.blockProtected, admin) { onSafety(safety.copy(blockProtected = it)) }
                ToggleRow("Always ask for high risk", "Even bots set to approve automatically ask before risky actions",
                    safety.alwaysAskHigh, admin) { onSafety(safety.copy(alwaysAskHigh = it)) }
                ToggleRow("Only git projects", "Refuse tasks in folders without git, where Hypurr can't save checkpoints",
                    safety.requireGit, admin) { onSafety(safety.copy(requireGit = it)) }
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
                        IconBubble(sunfieldVector(SunfieldIcons.Close), "Delete ${t.title}", tint = c.danger, fill = c.danger.copy(alpha = 0.1f), size = 34.dp) {
                            onDeleteTemplate(t.id)
                        }
                    }
                }
                AddTemplate(onAddTemplate)
            }
        }
        // Work tools hold the team's keys: admins only.
        if (admin) state.integrations?.let { work -> WorkTools(work, state.testResults, onIntegrations, onTestIntegration) }
        state.costs?.let { Spending(it) }
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
    if (showHomeTabs) {
        HypurrTabBar(
            selected = HomeTab.You,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            onSelect = onTab,
        )
    }
    }
}

@Composable
private fun AddTemplate(onAdd: (String, String) -> Unit) {
    val c = Hypurr.colors
    var open by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    if (!open) {
        SoftButton("Add a template", Modifier.fillMaxWidth(), icon = sunfieldVector(SunfieldIcons.Plus)) { open = true }
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
internal fun SmallField(value: String, hint: String, minLines: Int = 1, secret: Boolean = false, onChange: (String) -> Unit) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bg.copy(alpha = 0.6f)).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.text.BasicTextField(value, onChange, minLines = minLines, singleLine = secret,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Hypurr.colors
    Text(title.uppercase(), color = c.tertiary, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
internal fun Detail(label: String, value: String) {
    val c = Hypurr.colors
    Row {
        Text(label, color = c.secondary, modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A Hypurr toggle: a pill whose knob springs across. */
@Composable
internal fun ToggleRow(title: String, subtitle: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
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
            .background(if (on) androidx.compose.ui.graphics.SolidColor(c.accent) else androidx.compose.ui.graphics.SolidColor(c.border))) {
            Box(Modifier.offset(x = knob, y = 2.dp).size(26.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White))
        }
    }
}

/**
 * Work tools: GitHub, Jira, Slack and Teams. Credentials go straight to the computer and stay
 * there; this screen only ever shows whether each is set up and the last few characters.
 */
@Composable
private fun WorkTools(work: Integrations, tests: Map<String, String>, onSave: (JsonObject) -> Unit, onTest: (String) -> Unit) {
    val c = Hypurr.colors
    Section("When a task finishes") {
        ToggleRow("Learning mode", "The agent explains what it changed, why, and what to check", work.learning, true) {
            onSave(buildJsonObject { put("learning", it) })
        }
        ToggleRow("Open a pull request", "On GitHub projects, upload the task branch and open a PR", work.autoPr, true) {
            onSave(buildJsonObject { put("autoPr", it) })
        }
        ToggleRow("Post the result", "Send a summary to Slack or Teams", work.notify, true) {
            onSave(buildJsonObject { put("notify", it) })
        }
    }
    Section("Work tools") {
        Text("Keys are saved on your computer, never on this phone or our servers.", color = c.secondary,
            style = MaterialTheme.typography.bodySmall)
        Service("GitHub", when {
            work.github.usesCli -> "Connected through the GitHub CLI on your computer"
            work.github.configured -> "Token ${work.github.tokenHint}"
            else -> "Issues as tasks, and a pull request when a task finishes"
        }, work.github.configured, tests["github"], onTest = { onTest("github") }) {
            var token by remember { mutableStateOf("") }
            SmallField(token, if (work.github.tokenHint.isNotEmpty()) "Paste a new token to replace ${work.github.tokenHint}"
                else "Personal access token (optional with the GitHub CLI)", secret = true) { token = it }
            SoftButton("Save", Modifier.fillMaxWidth()) {
                onSave(buildJsonObject { putJsonObject("github") { put("token", token.trim()) } })
                token = ""
            }
        }
        Service("Jira", if (work.jira.configured) "${work.jira.email} · ${work.jira.baseUrl.removePrefix("https://")}"
            else "Your tickets as tasks, and a comment when done", work.jira.configured, tests["jira"], onTest = { onTest("jira") }) {
            var url by remember(work.jira.baseUrl) { mutableStateOf(work.jira.baseUrl) }
            var email by remember(work.jira.email) { mutableStateOf(work.jira.email) }
            var token by remember { mutableStateOf("") }
            SmallField(url, "https://yourcompany.atlassian.net") { url = it }
            SmallField(email, "Your work email") { email = it }
            SmallField(token, if (work.jira.tokenHint.isNotEmpty()) "Paste a new API token to replace ${work.jira.tokenHint}"
                else "Atlassian API token", secret = true) { token = it }
            SoftButton("Save", Modifier.fillMaxWidth()) {
                onSave(buildJsonObject {
                    putJsonObject("jira") {
                        put("baseUrl", url.trim())
                        put("email", email.trim())
                        if (token.isNotBlank()) put("token", token.trim())
                    }
                })
                token = ""
            }
        }
        listOf("slack" to "Slack", "teams" to "Microsoft Teams").forEach { (kind, name) ->
            val hook = if (kind == "slack") work.slack else work.teams
            Service(name, if (hook.configured) "Webhook ${hook.urlHint}" else "Post finished tasks to a channel (incoming webhook)",
                hook.configured, tests[kind], onTest = { onTest(kind) }) {
                var url by remember { mutableStateOf("") }
                SmallField(url, if (hook.configured) "Paste a new webhook URL to replace it" else "https://… incoming webhook URL", secret = true) { url = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Save", Modifier.weight(1f)) {
                        onSave(buildJsonObject { putJsonObject(kind) { put("url", url.trim()) } })
                        url = ""
                    }
                    if (hook.configured) SoftButton("Remove", Modifier.weight(1f), tint = c.danger) {
                        onSave(buildJsonObject { putJsonObject(kind) { put("url", "") } })
                    }
                }
            }
        }
    }
}

@Composable
private fun Service(name: String, status: String, configured: Boolean, test: String?, onTest: () -> Unit,
                    edit: @Composable ColumnScope.() -> Unit) {
    val c = Hypurr.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.bg.copy(alpha = 0.5f)).padding(12.dp)
        .animateContentSize(Motion.spatialDefault()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().pressable("Set up $name") { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = c.text, style = MaterialTheme.typography.titleSmall)
                Text(status, color = c.secondary, style = MaterialTheme.typography.bodySmall)
            }
            com.ragul84.hypurr.ui.Pill(if (configured) "Connected" else "Set up", if (configured) c.success else c.accent)
        }
        if (test != null) Text(test, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        androidx.compose.animation.AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                edit()
                if (configured) SoftButton("Test", Modifier.fillMaxWidth(), tint = c.secondary, onClick = onTest)
            }
        }
    }
}

/** The cost tracker: what tasks cost, from what the agents report (or an estimate). */
@Composable
private fun Spending(costs: TaskCosts) {
    val c = Hypurr.colors
    val est = costs.total.estimated
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Section("Spending") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today" to costs.today, "7 days" to costs.week, "All time" to costs.total.cost).forEach { (label, v) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.bg.copy(alpha = 0.5f)).padding(10.dp)) {
                    Text(costLabel(v, est), color = c.text, style = MaterialTheme.typography.titleMedium)
                    Text(label, color = c.secondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (costs.tasks.isEmpty()) {
            Text("Costs show up here once a task's agent reports its usage.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        costs.tasks.sortedByDescending { it.usage.cost }.take(5).forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${"%,d".format(t.usage.totalTokens)} tokens", color = c.tertiary, style = MaterialTheme.typography.labelSmall)
                }
                Text(t.usage.label, color = c.text, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (est) Text("≈ means an estimate from list prices; the agent didn't report its exact cost.", color = c.tertiary,
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))
        Text("Hypurr gateway", color = c.text, style = MaterialTheme.typography.titleSmall)
        Text(
            "Free allowance and paid credits for Hypurr Agent. Team admin spending limits also apply.",
            color = c.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Free remaining (today)", color = c.secondary, style = MaterialTheme.typography.bodySmall)
            Text(costs.gatewayFreeRemaining ?: "—", color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Credits balance", color = c.secondary, style = MaterialTheme.typography.bodySmall)
            Text(costs.gatewayCreditsLabel ?: "—", color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        SoftButton("Buy credits", Modifier.fillMaxWidth()) {
            val url = costs.buyCreditsUrl ?: "https://checkout.stripe.com/c/pay/cs_test_placeholder"
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) { }
        }
    }
}
