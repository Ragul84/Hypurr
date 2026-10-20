package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.signalIcon

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Activity
import com.ragul84.hypurr.model.Actor
import com.ragul84.hypurr.model.AuditEntry
import com.ragul84.hypurr.model.AuditLog
import com.ragul84.hypurr.model.PolicyInfo
import com.ragul84.hypurr.model.SpendByPerson
import com.ragul84.hypurr.model.rupees
import com.ragul84.hypurr.model.TeamInfo
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.riskColor
import com.ragul84.hypurr.ui.riskLabel
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

enum class AdminTab(val label: String) { Activity("Activity"), People("People"), Rules("Rules"), Spend("₹ Spend"), Audit("Audit") }

data class TeamAdminUiState(
    val you: Actor? = null,
    val team: TeamInfo? = null,
    val policies: PolicyInfo? = null,
    val activity: Activity? = null,
    val audit: AuditLog? = null,
    val spend: SpendByPerson? = null,
    val tab: AdminTab = AdminTab.Activity,
    /** After an export: "Shared 42 records" or why it failed. */
    val exportNote: String? = null,
    /** The last save error, in plain words. */
    val error: String? = null,
    val now: Long = System.currentTimeMillis(),
) {
    val isAdmin get() = you?.isAdmin != false
}

/**
 * Team admin: what everyone's agents did, the team's rules, who may do what, and the
 * append-only audit log. Everything is enforced on the computer; this screen only shows and edits.
 */
@Composable
fun TeamAdminScreen(
    state: TeamAdminUiState,
    onBack: () -> Unit,
    onTab: (AdminTab) -> Unit,
    onPolicies: (JsonObject) -> Unit = {},
    onRole: (key: String, role: String?) -> Unit = { _, _ -> },
    onDefaultRole: (String) -> Unit = {},
    onExportAudit: () -> Unit = {},
) {
    val c = Hypurr.colors
    val tabs = if (state.isAdmin) AdminTab.entries else listOf(AdminTab.Rules, AdminTab.People)
    val tab = state.tab.takeIf { it in tabs } ?: tabs.first()
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Team admin", style = MaterialTheme.typography.headlineMedium, color = c.text)
                state.you?.let { Text("You're ${article(it.role)} ${it.role} on this computer", color = c.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Segmented(tabs.map { it.name to it.label }, tab.name, true) { name -> onTab(AdminTab.valueOf(name)) }
        state.error?.let {
            Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp, start = 8.dp))
        }
        when (tab) {
            AdminTab.Activity -> state.activity?.let { ActivityTab(it, state.now, state.policies?.policies?.usdToInr ?: 84.0) } ?: Loading()
            AdminTab.Rules -> state.policies?.let { RulesTab(it, state.isAdmin, onPolicies) } ?: Loading()
            AdminTab.People -> state.team?.let { PeopleTab(it, state.isAdmin, state.now, onRole, onDefaultRole) } ?: Loading()
            AdminTab.Spend -> state.spend?.let { s -> SpendTab(s, state.policies?.policies ?: com.ragul84.hypurr.model.Policies(), state.isAdmin, onPolicies) } ?: Loading()
            AdminTab.Audit -> state.audit?.let { AuditTab(it, state.now, state.exportNote, onExportAudit) } ?: Loading()
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun article(role: String) = if (role.firstOrNull() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

@Composable
private fun Loading() {
    Text("Loading…", color = Hypurr.colors.secondary, modifier = Modifier.padding(24.dp))
}

/** A row of choices; the selected one fills with the accent. */
@Composable
internal fun Segmented(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surface).padding(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val fill by animateColorAsState(if (on) c.accent else c.accent.copy(alpha = 0f), Motion.effects(), label = "seg")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(fill)
                .pressable(label, role = Role.RadioButton, enabled = enabled) { onSelect(value) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center) {
                Text(label, color = if (on) c.onAccent else if (enabled) c.secondary else c.tertiary, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, tint: Color = Hypurr.colors.text) {
    val c = Hypurr.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(10.dp)) {
        Text(value, color = tint, style = MaterialTheme.typography.titleMedium)
        Text(label, color = c.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private fun roleColor(role: String, c: com.ragul84.hypurr.ui.theme.HypurrColors) = when (role) {
    "admin" -> c.accent
    "viewer" -> c.tertiary
    "system" -> c.warning
    else -> c.success
}

@Composable
private fun ActivityTab(a: Activity, now: Long, rate: Double) {
    val c = Hypurr.colors
    Section("Last ${a.days} days") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(rupees(a.totals.todaySpend * rate), "Spent today", Modifier.weight(1f))
            Stat("${a.totals.todayTasks}", "Tasks today", Modifier.weight(1f))
            Stat("${a.totals.approvals}", "Approvals", Modifier.weight(1f))
            Stat("${a.totals.blocked}", "Blocked", Modifier.weight(1f), if (a.totals.blocked > 0) c.danger else c.text)
        }
    }
    Section("People") {
        a.people.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (p.key == "local") signalIcon(SignalIcons.Host) else signalIcon(SignalIcons.You), null, tint = c.secondary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = c.text, style = MaterialTheme.typography.titleSmall)
                    val parts = buildList {
                        add("${p.tasks} task${if (p.tasks == 1) "" else "s"}")
                        if (p.spend > 0) add(rupees(p.spend * rate))
                        if (p.approvals > 0) add("${p.approvals} approved")
                        if (p.blocked > 0) add("${p.blocked} blocked")
                    }
                    Text(parts.joinToString(" · "), color = c.secondary, style = MaterialTheme.typography.bodySmall)
                }
                Pill(p.role, roleColor(p.role, c))
            }
        }
    }
    Section("Recent tasks") {
        if (a.tasks.isEmpty()) Text("No tasks in this period.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        a.tasks.take(8).forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(t.startedBy.name, t.projectName, t.backend, relativeTime(t.createdAt, now)).filter { it.isNotBlank() }
                        .joinToString(" · "), color = c.tertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    t.usage?.let { Text(it.label, color = c.text, style = MaterialTheme.typography.titleSmall) }
                    Text(when {
                        t.pr != null -> "PR #${t.pr.number}"
                        t.status == "finished" -> "Finished"
                        else -> "Working"
                    }, color = if (t.status == "finished") c.success else c.accent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (a.events.isNotEmpty()) Section("Latest") { a.events.take(5).forEach { AuditRow(it, now) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesTab(info: PolicyInfo, admin: Boolean, onSave: (JsonObject) -> Unit) {
    val c = Hypurr.colors
    val p = info.policies
    if (!admin) {
        Text("Only admins can change these. They apply to everyone, on every device.", color = c.secondary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 12.dp))
    }
    val rate = p.usdToInr.takeIf { it > 0 } ?: 84.0
    Section("Team spending limits") {
        var daily by remember(p.dailyLimit) { mutableStateOf(if (p.dailyLimit > 0) whole(p.dailyLimit * rate) else "") }
        var perTask by remember(p.taskLimit) { mutableStateOf(if (p.taskLimit > 0) whole(p.taskLimit * rate) else "") }
        val spent = rupees(info.spentToday * rate)
        val dailyText = if (p.dailyLimit > 0) "$spent of ${rupees(p.dailyLimit * rate)} spent today" else "$spent spent today · no daily limit"
        Text(dailyText, color = c.text, style = MaterialTheme.typography.bodyMedium)
        if (p.dailyLimit > 0) Meter((info.spentToday / p.dailyLimit).toFloat())
        LimitRow("Per day", "Across all tasks. New tasks are refused once it's reached.", daily, admin) { daily = it }
        LimitRow("Per task", "A task over its limit is stopped.", perTask, admin) { perTask = it }
        if (admin) SoftButton("Save limits", Modifier.fillMaxWidth()) {
            onSave(buildJsonObject {
                put("dailyLimit", (daily.toDoubleOrNull() ?: 0.0) / rate)
                put("taskLimit", (perTask.toDoubleOrNull() ?: 0.0) / rate)
            })
        }
        Text("Agents bill in dollars; shown at ₹${whole(rate)} per \$1 from the pricing config. Per-person limits are under ₹ Spend.",
            color = c.tertiary, style = MaterialTheme.typography.labelSmall)
    }
    Section("Folder rules") {
        var folders by remember(p.allowedFolders) { mutableStateOf(p.allowedFolders.joinToString("\n")) }
        Text(if (p.allowedFolders.isEmpty()) "Tasks and bots may work in any folder on this computer."
            else "Tasks and bots may only work inside these folders.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        if (admin) {
            SmallField(folders, "/Users/asha/work (one per line; empty: any folder)", minLines = 2) { folders = it }
            SoftButton("Save folders", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Folder)) {
                onSave(buildJsonObject { putJsonArray("allowedFolders") { lines(folders).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
            }
        } else p.allowedFolders.forEach { Detail("Folder", it) }
    }
    Section("Local-only mode") {
        ToggleRow("Local-only", "No Hypurr cloud or relay: phones pair on this network only. Agents start only with approved model endpoints.",
            p.localOnly, admin) { onSave(buildJsonObject { put("localOnly", it) }) }
        var eps by remember(p.modelEndpoints) { mutableStateOf(p.modelEndpoints.joinToString("\n")) }
        Text("Approved model endpoints", color = c.text, style = MaterialTheme.typography.titleSmall)
        if (admin) {
            SmallField(eps, "https://llm.yourcompany.in/v1 or http://localhost:11434", minLines = 2) { eps = it }
            SoftButton("Save endpoints", Modifier.fillMaxWidth()) {
                onSave(buildJsonObject { putJsonArray("modelEndpoints") { lines(eps).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
            }
        } else if (p.modelEndpoints.isEmpty()) Detail("Endpoints", "None") else p.modelEndpoints.forEach { Detail("Endpoint", it) }
        if (p.localOnly && p.modelEndpoints.isEmpty()) {
            Text("No endpoint approved yet, so agents won't start.", color = c.needsText, style = MaterialTheme.typography.bodySmall)
        }
    }
    Section("Allowed agents") {
        val any = p.allowedAgents.isEmpty()
        ToggleRow("Any installed agent", "Turn off to choose which agents tasks and bots may use", any, admin) { on ->
            onSave(buildJsonObject {
                putJsonArray("allowedAgents") { if (!on) info.agents.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.id)) } }
            })
        }
        if (!any) {
            val ids = (info.agents.map { it.id to it.name } + p.allowedAgents.filter { id -> info.agents.none { it.id == id } }.map { it to it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ids.forEach { (id, name) ->
                    val on = id in p.allowedAgents
                    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) c.surface2 else c.surface2)
                        .pressable(name, role = Role.Checkbox, enabled = admin) {
                            val next = if (on) p.allowedAgents - id else p.allowedAgents + id
                            if (next.isNotEmpty()) onSave(buildJsonObject {
                                putJsonArray("allowedAgents") { next.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                            })
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (on) {
                            Icon(signalIcon(SignalIcons.Check), null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(name, color = if (on) c.accent else c.secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    Section("Approvals") {
        Text("Always ask, even for bots set to approve automatically", color = c.text, style = MaterialTheme.typography.titleSmall)
        Segmented(listOf("low" to "Any risk", "medium" to "Medium+", "high" to "High", "never" to "Never"), p.askFrom, admin) {
            onSave(buildJsonObject { put("askFrom", it) })
        }
        Text("Only an admin can approve", color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Segmented(listOf("off" to "Anyone", "medium" to "Medium+", "high" to "High"), p.adminApprovesFrom.let { if (it == "low") "medium" else it }, admin) {
            onSave(buildJsonObject { put("adminApprovesFrom", it) })
        }
        Text(when (p.adminApprovesFrom) {
            "off" -> "Members can approve anything their agents ask."
            "high" -> "Members see high-risk requests but an admin answers them."
            else -> "Members see medium and high-risk requests but an admin answers them."
        }, color = c.secondary, style = MaterialTheme.typography.bodySmall)
    }
    Section("Protected branches") {
        var branches by remember(info.safety.protectedBranches) { mutableStateOf(info.safety.protectedBranches.joinToString(", ")) }
        if (admin) {
            SmallField(branches, "main, release/*") { branches = it }
            SoftButton("Save branches", Modifier.fillMaxWidth()) {
                onSave(buildJsonObject {
                    putJsonArray("protectedBranches") {
                        branches.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                })
            }
        } else {
            Detail("Protected", info.safety.protectedBranches.joinToString(", ").ifEmpty { "None" })
        }
        ToggleRow("Block pushes to them", "Pushes and force-pushes are refused without asking", info.safety.blockProtected, admin) {
            onSave(buildJsonObject { put("blockProtected", it) })
        }
        ToggleRow("Only git projects", "Refuse tasks where Hypurr can't save checkpoints", info.safety.requireGit, admin) {
            onSave(buildJsonObject { put("requireGit", it) })
        }
    }
}

private fun platformName(p: String) = when (p) {
    "ios" -> "iPhone"
    "android" -> "Android"
    "macos" -> "Mac"
    "linux" -> "Linux"
    else -> p.replaceFirstChar(Char::uppercase)
}

private fun whole(x: Double) = Math.round(x).toString()

private fun lines(s: String) = s.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun Meter(fraction: Float) {
    val c = Hypurr.colors
    val f = fraction.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(c.border)) {
        Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(50)).background(if (f >= 0.9f) c.danger else c.text))
    }
}

@Composable
private fun SpendTab(s: SpendByPerson, p: com.ragul84.hypurr.model.Policies, admin: Boolean, onSave: (JsonObject) -> Unit) {
    val c = Hypurr.colors
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            com.ragul84.hypurr.ui.DotFigure(rupees(s.teamMonthInr), com.ragul84.hypurr.ui.theme.SignalType.figure, c.text)
            Text(if (s.teamMonthlyInr > 0) "this month, of ${rupees(s.teamMonthlyInr)} for the team" else "this month · no team limit",
                color = c.tertiary, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (s.teamMonthlyInr > 0) Box(Modifier.padding(top = 10.dp)) { Meter((s.teamMonthInr / s.teamMonthlyInr).toFloat()) }
    Section("Limits for each person") {
        var day by remember(p.personDailyInr) { mutableStateOf(if (p.personDailyInr > 0) whole(p.personDailyInr) else "") }
        var month by remember(p.personMonthlyInr) { mutableStateOf(if (p.personMonthlyInr > 0) whole(p.personMonthlyInr) else "") }
        var team by remember(p.teamMonthlyInr) { mutableStateOf(if (p.teamMonthlyInr > 0) whole(p.teamMonthlyInr) else "") }
        LimitRow("Per person, per day", "Their new tasks wait until tomorrow.", day, admin) { day = it }
        LimitRow("Per person, per month", "Counts from the 1st.", month, admin) { month = it }
        LimitRow("Whole team, per month", "Everyone's tasks together.", team, admin) { team = it }
        if (admin) SoftButton("Save limits", Modifier.fillMaxWidth()) {
            onSave(buildJsonObject {
                put("personDailyInr", day.toDoubleOrNull() ?: 0.0)
                put("personMonthlyInr", month.toDoubleOrNull() ?: 0.0)
                put("teamMonthlyInr", team.toDoubleOrNull() ?: 0.0)
            })
        }
    }
    Section("People") {
        if (s.people.isEmpty()) Text("No spend yet this month.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        s.people.forEach { person ->
            val over = (person.dailyInr > 0 && person.todayInr >= person.dailyInr) || (person.monthlyInr > 0 && person.monthInr >= person.monthlyInr)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(person.name, color = c.text, style = MaterialTheme.typography.titleSmall)
                        Text(buildString {
                            append("${rupees(person.todayInr)} today")
                            if (person.dailyInr > 0) append(" of ${rupees(person.dailyInr)}")
                            if (person.custom) append(" · own limit")
                        }, color = if (over) c.needsText else c.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                    com.ragul84.hypurr.ui.DotFigure(rupees(person.monthInr), com.ragul84.hypurr.ui.theme.SignalType.figureSmall, if (over) c.danger else c.text)
                }
                if (person.monthlyInr > 0) Meter((person.monthInr / person.monthlyInr).toFloat())
                Text(if (person.monthlyInr > 0) "this month, of ${rupees(person.monthlyInr)}" else "this month · no monthly limit",
                    color = c.tertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text("Limits are checked on the computer before each task and while it runs. Spend is converted at ₹${whole(s.usdToInr)} per \$1.",
        color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp))
}

@Composable
private fun LimitRow(title: String, subtitle: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val c = Hypurr.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(10.dp))
        Row(Modifier.width(112.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("₹", color = c.secondary)
            Spacer(Modifier.width(4.dp))
            Box {
                if (value.isEmpty()) Text("No limit", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.text.BasicTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
                    enabled = enabled, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PeopleTab(team: TeamInfo, admin: Boolean, now: Long, onRole: (String, String?) -> Unit, onDefault: (String) -> Unit) {
    val c = Hypurr.colors
    val roles = listOf("admin" to "Admin", "member" to "Member", "viewer" to "Viewer")
    Section("New devices join as") {
        Segmented(roles, team.defaultRole, admin, onDefault)
        Text(when (team.defaultRole) {
            "admin" -> "Every paired phone can change everything. Pick Member to make this a team."
            "member" -> "New phones run tasks within the rules; an admin promotes them."
            else -> "New phones can only look until an admin gives them a role."
        }, color = c.secondary, style = MaterialTheme.typography.bodySmall)
    }
    Section("People") {
        team.people.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (p.fixed) signalIcon(SignalIcons.Host) else signalIcon(SignalIcons.You), null, tint = c.secondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.you) " (you)" else "", color = c.text, style = MaterialTheme.typography.titleSmall)
                        Text(when {
                            p.fixed -> "The computer itself is always an admin"
                            p.lastSeenAt != null -> "${platformName(p.platform)} · seen ${relativeTime(p.lastSeenAt, now)}".replace("seen now", "online now")
                            else -> platformName(p.platform)
                        } + if (!p.fixed && !p.ownRole) " · default role" else "", color = c.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.fixed || !admin) Pill(p.role, roleColor(p.role, c))
                }
                if (!p.fixed && admin) Segmented(roles, p.role, true) { onRole(p.key, it) }
            }
        }
    }
    Row(Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp)) {
        Icon(signalIcon(SignalIcons.Shield), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Each person pairs their own phone with the QR code on the computer. Single sign-on (SSO) with your company account is planned.",
            color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AuditTab(log: AuditLog, now: Long, note: String?, onExport: () -> Unit) {
    val c = Hypurr.colors
    val ok = log.intact
    Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) signalIcon(SignalIcons.Shield) else signalIcon(SignalIcons.Deny), null, tint = if (ok) c.success else c.danger)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (ok) "Intact: nothing edited or removed" else "Changed at record ${log.brokenAt}", color = c.text,
                style = MaterialTheme.typography.titleSmall)
            Text("${log.count} records · append-only, each one sealed to the one before", color = c.secondary,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftButton("Export CSV", Modifier.weight(1f), icon = signalIcon(SignalIcons.File), onClick = onExport)
    }
    Text(note ?: "Opens the share sheet with every record, oldest first. Nothing is sent until you pick an app.",
        color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
    Section("Newest first") {
        if (log.entries.isEmpty()) Text("Nothing yet.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        log.entries.forEach { AuditRow(it, now) }
    }
}

@Composable
private fun AuditRow(e: AuditEntry, now: Long) {
    val c = Hypurr.colors
    val bad = e.action == "blocked" || e.action == "approval.blocked" || e.action == "policy.limit"
    Row(verticalAlignment = Alignment.Top) {
        Icon(if (bad) signalIcon(SignalIcons.Deny) else signalIcon(SignalIcons.Check), null, tint = if (bad) c.danger else c.success,
            modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${e.actorName} ${e.summary}", color = c.text, style = MaterialTheme.typography.bodyMedium)
            val target = e.targetLabel
            if (target.isNotBlank()) Text(target, color = c.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            e.reason?.let { Text(it, color = c.tertiary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(relativeTime(e.at, now), color = c.tertiary, style = MaterialTheme.typography.labelSmall)
            e.risk?.let { Pill(riskLabel(it), riskColor(it), Modifier.padding(top = 4.dp)) }
        }
    }
}
