package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.TicketPlan
import com.ragul84.hypurr.model.TicketReview
import com.ragul84.hypurr.model.TicketSetup
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalChip
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

enum class TicketStep { Input, Plan, Running, Review, Setup }

/** Ticket to PR. Nothing is uploaded or posted without the person's tap. */
data class TicketUiState(
    val step: TicketStep = TicketStep.Input,
    val input: String = "",
    val projects: List<Project> = emptyList(),
    val project: String? = null,
    val ticket: Issue? = null,
    /** `jira` or `github`: the tracker needs a token on the computer first. */
    val needsSetup: String? = null,
    val plan: TicketPlan? = null,
    val planBy: String = "agent",
    /** Acceptance checks, one per line, as the person edits them. */
    val checksText: String = "",
    val taskId: String? = null,
    val botId: String? = null,
    val branch: String? = null,
    /** working | needsInput | idle */
    val agentStatus: String = "working",
    val review: TicketReview? = null,
    val prTitle: String = "",
    val prBody: String = "",
    val confirmPush: Boolean = false,
    val setup: TicketSetup? = null,
    val jiraUrl: String = "",
    val jiraEmail: String = "",
    val jiraToken: String = "",
    val githubToken: String = "",
    val setupNote: String? = null,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val error: String? = null,
    val copied: String? = null,
) {
    val projectName: String? get() = projects.firstOrNull { it.path == project }?.name ?: project?.substringAfterLast('/')

    /** The plan with the person's edits. */
    fun editedPlan(): TicketPlan? = plan?.copy(checks = checksText.lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() })
}

/** "via gh", "via glab", … for the publish line. */
fun publishWords(provider: String, via: String): String {
    val where = when (provider) {
        "github" -> "GitHub"
        "gitlab" -> "GitLab"
        "azure" -> "Azure DevOps"
        else -> "your git server"
    }
    return if (via.isEmpty()) "Upload the branch to $where, then copy the description into a new pull request"
    else "Upload the branch and open a pull request on $where (signed in with $via on the computer)"
}

@Composable
fun TicketScreen(
    state: TicketUiState,
    onBack: () -> Unit = {},
    onChange: (TicketUiState) -> Unit = {},
    onPaste: () -> Unit = {},
    onRead: () -> Unit = {},
    onSetup: () -> Unit = {},
    onApprovePlan: () -> Unit = {},
    onRedoPlan: () -> Unit = {},
    onOpenTask: () -> Unit = {},
    onReview: () -> Unit = {},
    onApprovePush: () -> Unit = {},
    onCopy: (label: String, text: String) -> Unit = { _, _ -> },
    onShare: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onSaveSetup: () -> Unit = {},
    onTest: (String) -> Unit = {},
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (state.step == TicketStep.Input) IconTile(signalIcon(SignalIcons.Settings), "Tracker setup", onClick = onSetup)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
            val title = when (state.step) {
                TicketStep.Input -> "Ticket to PR"
                TicketStep.Plan -> "The plan"
                TicketStep.Running -> "Working on it"
                TicketStep.Review -> "Ready for review"
                TicketStep.Setup -> "Trackers"
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.text)
            Spacer(Modifier.height(6.dp))
            val sub = when (state.step) {
                TicketStep.Setup -> "Tokens stay on your computer"
                else -> listOfNotNull(state.ticket?.key?.takeIf { it.isNotEmpty() }, state.projectName).joinToString(" · ").ifEmpty { "Pick a project folder" }
            }
            Eyebrow(sub, color = c.tertiary)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.step) {
                TicketStep.Input -> {
                    item { InputCard(state, onChange, onPaste) }
                    if (state.projects.size > 1) item { ProjectsCard(state, onChange) }
                    state.needsSetup?.let { s -> item { NeedsSetupCard(s, onSetup) } }
                }
                TicketStep.Plan -> state.plan?.let { p ->
                    state.ticket?.let { t -> item { TicketHeader(t) } }
                    item { PlanUnderstood(state, onChange) }
                    item { PlanFiles(p, state, onChange) }
                    item { PlanChecks(state, onChange) }
                    if (p.tests.isNotEmpty() || p.testCommand.isNotEmpty()) item { PlanTests(p) }
                    if (state.planBy != "agent") item { Note("The agent wasn't reachable, so this plan only repeats the ticket. Edit it, or try again.") }
                }
                TicketStep.Running -> {
                    state.ticket?.let { t -> item { TicketHeader(t) } }
                    item { RunningCard(state) }
                }
                TicketStep.Review -> state.review?.let { r ->
                    item { SummaryCard(r) }
                    if (r.files.isNotEmpty()) item { FilesCard(r) }
                    item { PrCard(state, r, onChange, onOpenUrl) }
                    item { CommentCard(r) }
                }
                TicketStep.Setup -> {
                    item { GitHubSetup(state, onChange, onTest) }
                    item { JiraSetup(state, onChange, onTest) }
                    item { OtherSetup(state) }
                    state.setupNote?.let { n -> item { Note(n) } }
                }
            }
            if (state.busy) item { BusyCard(state.busyLabel) }
            state.error?.let { e -> item { ErrorCard(e) } }
        }
        val r = state.review
        if (state.confirmPush && r != null && !state.busy) {
            Box(Modifier.background(c.bg).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) { PushApproval(state, r, onChange, onApprovePush) }
        } else {
            BottomActions(state, onRead, onApprovePlan, onRedoPlan, onOpenTask, onReview, onChange, onCopy, onShare, onSaveSetup)
        }
    }
}

@Composable
private fun BottomActions(
    state: TicketUiState, onRead: () -> Unit, onApprovePlan: () -> Unit, onRedoPlan: () -> Unit, onOpenTask: () -> Unit,
    onReview: () -> Unit, onChange: (TicketUiState) -> Unit, onCopy: (String, String) -> Unit, onShare: (String) -> Unit,
    onSaveSetup: () -> Unit,
) {
    val c = Hypurr.colors
    if (state.busy || state.confirmPush) return
    Column(Modifier.background(c.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        when (state.step) {
            TicketStep.Input -> PrimaryButton("Read the ticket", Modifier.fillMaxWidth(), enabled = state.input.isNotBlank() && state.project != null,
                icon = signalIcon(SignalIcons.Docs), onClick = onRead)
            TicketStep.Plan -> {
                PrimaryButton("Approve and start", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Allow), onClick = onApprovePlan)
                Spacer(Modifier.height(8.dp))
                SoftButton("Make a new plan", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Refresh), onClick = onRedoPlan)
                Spacer(Modifier.height(6.dp))
                Hint("Runs on a safe branch. You approve risky steps and can undo.")
            }
            TicketStep.Running -> {
                PrimaryButton("Open the task", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Tasks), onClick = onOpenTask)
                Spacer(Modifier.height(8.dp))
                SoftButton("Review the changes", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Review), onClick = onReview)
            }
            TicketStep.Review -> {
                val r = state.review
                val pr = r?.pr
                if (pr == null || !pr.pushed) {
                    PrimaryButton(if (r?.publish?.via.isNullOrEmpty()) "Upload branch" else "Open pull request", Modifier.fillMaxWidth(),
                        enabled = r != null && !r.publish.protected && r.publish.provider != "none" && r.files.isNotEmpty(),
                        icon = signalIcon(SignalIcons.Branch)) { onChange(state.copy(confirmPush = true, error = null)) }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(if (state.copied == "pr") "Copied" else "Copy PR text", Modifier.weight(1f),
                            icon = signalIcon(if (state.copied == "pr") SignalIcons.Check else SignalIcons.Paste)) { onCopy("pr", "${state.prTitle}\n\n${state.prBody}") }
                        SoftButton("Back to task", Modifier.weight(1f), icon = signalIcon(SignalIcons.Tasks), onClick = onOpenTask)
                    }
                } else if (pr.url == null) {
                    PrimaryButton(if (state.copied == "pr") "Copied" else "Copy PR text", Modifier.fillMaxWidth(),
                        icon = signalIcon(if (state.copied == "pr") SignalIcons.Check else SignalIcons.Paste)) { onCopy("pr", "${state.prTitle}\n\n${state.prBody}") }
                    Spacer(Modifier.height(8.dp))
                    SoftButton(if (state.copied == "comment") "Copied" else "Copy ticket comment", Modifier.fillMaxWidth(),
                        icon = signalIcon(if (state.copied == "comment") SignalIcons.Check else SignalIcons.Paste)) { onCopy("comment", r.comment) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(if (state.copied == "comment") "Copied" else "Copy comment", Modifier.weight(1f),
                            icon = signalIcon(if (state.copied == "comment") SignalIcons.Check else SignalIcons.Paste)) { onCopy("comment", r.comment) }
                        PrimaryButton("Share comment", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send)) { onShare(r.comment) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Hint("Hypurr never posts to the ticket. You paste or share the comment.")
                }
            }
            TicketStep.Setup -> {
                PrimaryButton("Save on the computer", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Lock), onClick = onSaveSetup)
                Spacer(Modifier.height(6.dp))
                Hint("Tokens are sent once to your computer and never stored on this phone.")
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    val c = Hypurr.colors
    Text(text, color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun Card(eyebrow: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
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
private fun Editor(value: String, onValue: (String) -> Unit, placeholder: String, minHeight: Int = 0, mono: Boolean = false,
                   secret: Boolean = false, singleLine: Boolean = false) {
    val c = Hypurr.colors
    val style = (if (mono) SignalType.mono else MaterialTheme.typography.bodyLarge).copy(color = c.text)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(placeholder, color = c.tertiary, style = style.copy(color = c.tertiary))
        BasicTextField(value, onValue, Modifier.fillMaxWidth(), textStyle = style, cursorBrush = SolidColor(c.text), singleLine = singleLine,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
    }
}

@Composable
private fun InputCard(state: TicketUiState, onChange: (TicketUiState) -> Unit, onPaste: () -> Unit) {
    val c = Hypurr.colors
    Card("Ticket") {
        Editor(state.input, { onChange(state.copy(input = it, needsSetup = null, error = null)) },
            "A Jira or GitHub link, a key like SHOP-142 or #12, or paste the ticket's text from any tracker.", minHeight = 150)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Paste", Modifier.weight(1f), icon = signalIcon(SignalIcons.Paste), onClick = onPaste)
        }
        Spacer(Modifier.height(12.dp))
        HairlineDivider()
        Spacer(Modifier.height(10.dp))
        Text("Azure DevOps, ServiceNow or anything else: paste the title and description. Keys like AB#4521 or INC0012345 go in the PR title.",
            color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectsCard(state: TicketUiState, onChange: (TicketUiState) -> Unit) {
    Card("Project folder") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.projects.forEach { p ->
                SignalChip(signalIcon(if (p.path == state.project) SignalIcons.Check else SignalIcons.Folder), p.name,
                    quiet = p.path != state.project) { onChange(state.copy(project = p.path)) }
            }
        }
    }
}

@Composable
private fun NeedsSetupCard(kind: String, onSetup: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Eyebrow(if (kind == "jira") "Jira isn't set up" else "GitHub isn't set up", dot = c.needs, color = c.needsText)
            Spacer(Modifier.height(8.dp))
            Text(if (kind == "jira") "Add your Jira address and an API token on the computer so Hypurr can read the ticket."
                else "Sign in with gh on the computer, or add a GitHub token, so Hypurr can read the issue.",
                color = c.secondary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            SoftButton("Set up", icon = signalIcon(SignalIcons.Settings), onClick = onSetup)
            Spacer(Modifier.height(6.dp))
            Text("Or paste the ticket's text instead. That works without any setup.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TicketHeader(t: Issue) {
    val c = Hypurr.colors
    val src = when (t.source) { "jira" -> "Jira"; "github" -> "GitHub"; else -> "Pasted ticket" }
    Card(listOfNotNull(src, t.key.takeIf { it.isNotEmpty() }).joinToString(" · ")) {
        Text(t.title.ifEmpty { "Untitled ticket" }, color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlanUnderstood(state: TicketUiState, onChange: (TicketUiState) -> Unit) {
    val p = state.plan ?: return
    Card("What I understood") {
        Editor(p.understood, { onChange(state.copy(plan = p.copy(understood = it))) }, "What the ticket asks for")
    }
}

@Composable
private fun PlanFiles(p: TicketPlan, state: TicketUiState, onChange: (TicketUiState) -> Unit) {
    val c = Hypurr.colors
    Card("Files I'll touch") {
        if (p.files.isEmpty()) Text("The agent will find the right files as it works.", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
        p.files.forEachIndexed { i, f ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.path.substringAfterLast('/'), color = c.text, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (!f.exists) {
                            Spacer(Modifier.width(6.dp))
                            Text("NEW", color = c.tertiary, style = SignalType.eyebrow)
                        }
                    }
                    val folder = f.path.substringBeforeLast('/', "")
                    if (folder.isNotEmpty()) Text(folder, color = c.tertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (f.why.isNotBlank()) Text(f.why, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                }
                IconTile(signalIcon(SignalIcons.Close), "Remove ${f.path}", size = 32.dp, tint = c.tertiary) {
                    onChange(state.copy(plan = p.copy(files = p.files - f)))
                }
            }
        }
    }
}

@Composable
private fun PlanChecks(state: TicketUiState, onChange: (TicketUiState) -> Unit) {
    val c = Hypurr.colors
    Card("Done when") {
        Editor(state.checksText, { onChange(state.copy(checksText = it)) }, "One check per line")
        Spacer(Modifier.height(6.dp))
        Text("One check per line. You can edit them.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlanTests(p: TicketPlan) {
    val c = Hypurr.colors
    Card("Tests") {
        p.tests.forEach { t -> Bullet(t) }
        if (p.testCommand.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Runs ", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                Text(p.testCommand, color = c.text, style = SignalType.mono)
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val c = Hypurr.colors
    Row(Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.padding(top = 9.dp, end = 10.dp).size(4.dp).clip(CircleShape).background(c.tertiary))
        Text(text, color = c.text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RunningCard(state: TicketUiState) {
    val c = Hypurr.colors
    val needs = state.agentStatus == "needsInput"
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            when (state.agentStatus) {
                "needsInput" -> Eyebrow("Needs you", dot = c.needs, color = c.needsText, pulse = true)
                "working" -> Eyebrow("Agent is working", dot = c.text, color = c.secondary)
                else -> Eyebrow("Agent is done for now", color = c.secondary)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    needs -> "The agent is waiting for your OK on a step. Open the task to approve or deny it."
                    state.agentStatus == "working" -> "It's following the plan, writing tests and running them. You'll get a notification when it needs you."
                    else -> "Check what it said in the task, then review the changes to open a pull request."
                },
                color = c.text, style = MaterialTheme.typography.bodyLarge,
            )
            state.branch?.let {
                Spacer(Modifier.height(12.dp))
                SignalChip(signalIcon(SignalIcons.Branch), it, quiet = true)
            }
        }
    }
}

@Composable
private fun SummaryCard(r: TicketReview) {
    val c = Hypurr.colors
    val added = r.files.sumOf { it.added }
    val removed = r.files.sumOf { it.removed }
    Card("What changed") {
        Text(r.summary, color = c.text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Stat("${r.files.size}", if (r.files.size == 1) "file" else "files")
            Stat("+$added", "added")
            Stat("−$removed", "removed")
        }
        if (r.testCommand.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Tests were run with ${r.testCommand}. The task shows the results.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val c = Hypurr.colors
    Column {
        Text(value, color = c.text, style = SignalType.figureSmall)
        Text(label, color = c.tertiary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FilesCard(r: TicketReview) {
    val c = Hypurr.colors
    Card("Files") {
        r.files.take(12).forEachIndexed { i, f ->
            if (i > 0) HairlineDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.path.substringAfterLast('/'), color = c.text, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val folder = f.path.substringBeforeLast('/', "")
                    if (folder.isNotEmpty()) Text(folder, color = c.tertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("+${f.added} −${f.removed}", color = c.secondary, style = SignalType.mono)
            }
        }
        if (r.files.size > 12) Text("and ${r.files.size - 12} more", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PrCard(state: TicketUiState, r: TicketReview, onChange: (TicketUiState) -> Unit, onOpenUrl: (String) -> Unit) {
    val c = Hypurr.colors
    val pr = r.pr
    Card("Pull request") {
        when {
            pr?.url != null -> {
                Text("Opened for review.", color = c.text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                SignalChip(signalIcon(SignalIcons.Link), pr.number?.let { "#$it" } ?: "Open pull request") { onOpenUrl(pr.url) }
            }
            pr?.pushed == true -> {
                Text("Branch uploaded.", color = c.text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(pr.error ?: "No pull request tool is signed in on the computer. Copy the text below into a new pull request for ${pr.branch}.",
                    color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Editor(state.prTitle, { onChange(state.copy(prTitle = it)) }, "Title", singleLine = true)
                Spacer(Modifier.height(8.dp))
                Editor(state.prBody, { onChange(state.copy(prBody = it)) }, "Description", mono = true)
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        r.files.isEmpty() -> "Nothing changed yet, so there's nothing to upload."
                        r.publish.protected -> "This task isn't on a safe branch, so Hypurr won't upload it."
                        r.publish.provider == "none" -> "This folder has no remote. Copy the text and push it yourself."
                        else -> publishWords(r.publish.provider, r.publish.via) + "."
                    },
                    color = c.tertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The Needs-you approval for the upload: the only way the branch leaves the computer. */
@Composable
private fun PushApproval(state: TicketUiState, r: TicketReview, onChange: (TicketUiState) -> Unit, onApprove: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth(), emphasis = true) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Eyebrow("Needs you", dot = c.needs, color = c.needsText, pulse = true)
            Spacer(Modifier.height(10.dp))
            Text("Upload ${r.publish.branch} to your team's server?", color = c.text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("${publishWords(r.publish.provider, r.publish.via)}. Your teammates will see it. Nothing is merged.",
                color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Not now", Modifier.weight(1f), icon = signalIcon(SignalIcons.Deny)) { onChange(state.copy(confirmPush = false)) }
                PrimaryButton("Upload", Modifier.weight(1f), icon = signalIcon(SignalIcons.Allow), onClick = onApprove)
            }
        }
    }
}

@Composable
private fun CommentCard(r: TicketReview) {
    val c = Hypurr.colors
    Card("Comment for the ticket · draft") {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(r.comment, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text("Hypurr doesn't post this. Copy or share it when you're ready.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    val c = Hypurr.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) c.text else c.dotOff))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (ok) c.text else c.secondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Steps(vararg lines: String) {
    val c = Hypurr.colors
    lines.forEachIndexed { i, l ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text("${i + 1}", color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(18.dp))
            Text(l, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GitHubSetup(state: TicketUiState, onChange: (TicketUiState) -> Unit, onTest: (String) -> Unit) {
    val gh = state.setup?.integrations?.github
    Card("GitHub", trailing = { if (gh?.configured == true) SoftButton("Test") { onTest("github") } }) {
        StatusLine(gh?.configured == true, when {
            gh?.usesCli == true -> "Signed in with gh on the computer"
            gh?.configured == true -> "Token saved ${gh.tokenHint}"
            else -> "Not set up"
        })
        Spacer(Modifier.height(10.dp))
        Steps("Easiest: on the computer, run  gh auth login  in a terminal.", "Or create a token at github.com → Settings → Developer settings → Fine-grained tokens, with Issues and Pull requests access, and paste it here.")
        Spacer(Modifier.height(10.dp))
        Editor(state.githubToken, { onChange(state.copy(githubToken = it)) }, if (gh?.tokenHint.isNullOrEmpty()) "github_pat_…" else "Replace token ${gh?.tokenHint}", secret = true, singleLine = true)
    }
}

@Composable
private fun JiraSetup(state: TicketUiState, onChange: (TicketUiState) -> Unit, onTest: (String) -> Unit) {
    val j = state.setup?.integrations?.jira
    Card("Jira", trailing = { if (j?.configured == true) SoftButton("Test") { onTest("jira") } }) {
        StatusLine(j?.configured == true, if (j?.configured == true) "${j.email} · ${j.baseUrl.removePrefix("https://")}" else "Not set up")
        Spacer(Modifier.height(10.dp))
        Steps("Open id.atlassian.com/manage-profile/security/api-tokens and create an API token.", "Enter your Jira address, your login email and the token.")
        Spacer(Modifier.height(10.dp))
        Editor(state.jiraUrl, { onChange(state.copy(jiraUrl = it)) }, "https://yourcompany.atlassian.net", singleLine = true)
        Spacer(Modifier.height(8.dp))
        Editor(state.jiraEmail, { onChange(state.copy(jiraEmail = it)) }, "you@company.com", singleLine = true)
        Spacer(Modifier.height(8.dp))
        Editor(state.jiraToken, { onChange(state.copy(jiraToken = it)) }, if (j?.tokenHint.isNullOrEmpty()) "API token" else "Replace token ${j?.tokenHint}", secret = true, singleLine = true)
    }
}

@Composable
private fun OtherSetup(state: TicketUiState) {
    val cli = state.setup?.cli
    Card("Pull requests elsewhere") {
        StatusLine(cli?.glab == true, if (cli?.glab == true) "GitLab: signed in with glab" else "GitLab: run  glab auth login  on the computer")
        Spacer(Modifier.height(6.dp))
        StatusLine(cli?.az == true, if (cli?.az == true) "Azure DevOps: signed in with az" else "Azure DevOps: run  az login  on the computer")
        Spacer(Modifier.height(10.dp))
        Text("Azure DevOps boards, ServiceNow and other trackers: paste the ticket text. No token needed. Without a signed-in tool, Hypurr uploads the branch (after you approve) and gives you the PR text to copy.",
            color = Hypurr.colors.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BusyCard(label: String) {
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
private fun Note(text: String) {
    val c = Hypurr.colors
    Text(text, color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun ErrorCard(text: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(signalIcon(SignalIcons.NeedsYou), null, tint = c.needs, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
