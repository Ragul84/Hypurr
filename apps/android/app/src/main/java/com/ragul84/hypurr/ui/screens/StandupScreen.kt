package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.StandupReminder
import com.ragul84.hypurr.model.StandupResult
import com.ragul84.hypurr.model.TeamStandup
import com.ragul84.hypurr.model.TeamStandupPerson
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.DotFigure
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.Segmented
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

/** One-tap daily standup: written on the computer, edited here, shared by the person. */
data class StandupUiState(
    val loading: Boolean = false,
    val result: StandupResult? = null,
    val yesterday: String = "",
    val today: String = "",
    val blockers: String = "",
    val error: String? = null,
    val reminder: StandupReminder? = null,
    /** `null`: this phone isn't an admin, so no team view. */
    val team: TeamStandup? = null,
    val showTeam: Boolean = false,
    val projectName: String? = null,
    val copied: Boolean = false,
) {
    val hasDraft: Boolean get() = result != null

    /** Plain text for Teams, Slack or WhatsApp. Empty sections say so honestly. */
    fun shareText(): String {
        fun section(title: String, body: String, empty: String): String {
            val lines = body.lines().map { it.trim().trimStart('•', '-', '*').trim() }.filter { it.isNotEmpty() }
            return title + "\n" + (lines.ifEmpty { listOf(empty) }).joinToString("\n") { "• $it" }
        }
        val head = listOfNotNull("Standup", result?.date?.takeIf { it.isNotBlank() }).joinToString(" · ")
        return listOf(
            head,
            section("Yesterday", yesterday, "Nothing logged"),
            section("Today", today, "Planning the next task"),
            section("Blockers", blockers, "None"),
        ).joinToString("\n\n")
    }

    companion object {
        /** One "• " line per item, so wrapped items still read as separate points while editing. */
        fun bullets(items: List<String>) = items.joinToString("\n") { "• $it" }

        fun from(result: StandupResult, base: StandupUiState = StandupUiState()) = base.copy(
            loading = false,
            result = result,
            yesterday = bullets(result.standup.yesterday),
            today = bullets(result.standup.today),
            blockers = bullets(result.standup.blockers),
            error = null,
            copied = false,
        )
    }
}

@Composable
fun StandupScreen(
    state: StandupUiState,
    onBack: () -> Unit = {},
    onChange: (StandupUiState) -> Unit = {},
    onRewrite: () -> Unit = {},
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onReminder: (Boolean) -> Unit = {},
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
            Text("Daily standup", style = MaterialTheme.typography.headlineLarge, color = c.text)
            Spacer(Modifier.height(6.dp))
            val sub = listOfNotNull(state.result?.date?.takeIf { it.isNotBlank() }, state.projectName ?: "All projects").joinToString(" · ")
            Eyebrow(sub, color = c.tertiary)
        }
        if (state.team?.available == true) {
            Segmented(listOf("Mine", "Team"), if (state.showTeam) 1 else 0,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) { onChange(state.copy(showTeam = it == 1)) }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.showTeam && state.team != null) {
                state.team.people.forEach { p -> item(key = "p-${p.key}") { PersonCard(p) } }
                return@LazyColumn
            }
            when {
                state.loading && !state.hasDraft -> item { WritingCard() }
                state.error != null && !state.hasDraft -> item { ErrorCard(state.error, onRewrite) }
                else -> {
                    state.result?.let { r -> item { Counts(r, state) } }
                    item {
                        Section("Yesterday", state.yesterday, "What you finished. One line each.") { onChange(state.copy(yesterday = it, copied = false)) }
                    }
                    item {
                        Section("Today", state.today, "What you'll work on.") { onChange(state.copy(today = it, copied = false)) }
                    }
                    item {
                        Section("Blockers", state.blockers, "None", needsYou = state.blockers.isNotBlank()) {
                            onChange(state.copy(blockers = it, copied = false))
                        }
                    }
                    state.result?.let { r ->
                        item {
                            val n = r.facts.commits.size
                            val t = r.facts.tasks.size
                            val from = "From $n commit${if (n == 1) "" else "s"} and $t task${if (t == 1) "" else "s"}" +
                                if (r.by == "agent") " · written by your agent" else " · plain draft"
                            Text(from, color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    item { ReminderCard(state.reminder, onReminder) }
                }
            }
        }
        if (!state.showTeam && state.hasDraft) {
            Column(Modifier.background(c.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(if (state.copied) "Copied" else "Copy", Modifier.weight(1f),
                        icon = signalIcon(if (state.copied) SignalIcons.Check else SignalIcons.Paste), onClick = onCopy)
                    SoftButton(if (state.loading) "Writing…" else "Rewrite", Modifier.weight(1f), icon = signalIcon(SignalIcons.Refresh), onClick = onRewrite)
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Share", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Send), onClick = onShare)
                Spacer(Modifier.height(6.dp))
                Text("Nothing is sent until you share it.", color = c.tertiary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun Counts(r: StandupResult, state: StandupUiState) {
    val c = Hypurr.colors
    val blockers = state.blockers.lines().count { it.isNotBlank() }
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Count("Commits", r.facts.commits.size, Modifier.weight(1f))
            Count("Tasks", r.facts.tasks.size, Modifier.weight(1f))
            Count("Blockers", blockers, Modifier.weight(1f), red = blockers > 0)
        }
    }
}

@Composable
private fun Count(label: String, n: Int, modifier: Modifier, red: Boolean = false) {
    val c = Hypurr.colors
    Column(modifier) {
        DotFigure(n.toString(), SignalType.counter, if (red) c.needsText else c.text)
        Spacer(Modifier.height(6.dp))
        Eyebrow(label, color = if (red) c.needsText else c.tertiary)
    }
}

@Composable
private fun Section(title: String, text: String, placeholder: String, needsYou: Boolean = false, onEdit: (String) -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth(), emphasis = needsYou) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Eyebrow(title, color = if (needsYou) c.needsText else c.tertiary, dot = if (needsYou) c.needs else null)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (text.isEmpty()) Text(placeholder, color = c.tertiary, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    text, onEdit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.text),
                )
            }
        }
    }
}

@Composable
private fun WritingCard() {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DotCat(c.text, 72.dp, off = c.dotOff)
            Spacer(Modifier.height(18.dp))
            Text("Writing your standup", color = c.text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Reading your commits since the last workday and the tasks Hypurr ran.", color = c.secondary,
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth(), emphasis = true) {
        Column(Modifier.padding(18.dp)) {
            Eyebrow("Couldn't write it", color = c.needsText, dot = c.needs)
            Spacer(Modifier.height(8.dp))
            Text(message, color = c.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            SoftButton("Try again", icon = signalIcon(SignalIcons.Refresh), onClick = onRetry)
        }
    }
}

@Composable
private fun ReminderCard(reminder: StandupReminder?, onToggle: (Boolean) -> Unit) {
    val c = Hypurr.colors
    val on = reminder?.enabled == true
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                Icon(signalIcon(SignalIcons.Clock), null, tint = c.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) {
                ToggleRow("Remind me on weekdays", "Ready at ${reminder?.time ?: "09:30"} IST, before standup", on, true, onToggle)
            }
        }
    }
}

@Composable
private fun PersonCard(p: TeamStandupPerson) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name ?: "Someone", color = c.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                DotFigure(p.finished.toString(), SignalType.figureSmall, c.text)
                Spacer(Modifier.width(4.dp))
                Text("done", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
                DotFigure(p.open.toString(), SignalType.figureSmall, c.text)
                Spacer(Modifier.width(4.dp))
                Text("open", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
            }
            if (p.yesterday.isNotEmpty() || p.today.isNotEmpty()) Spacer(Modifier.height(10.dp))
            listOf("Yesterday" to p.yesterday, "Today" to p.today).filter { it.second.isNotEmpty() }.forEachIndexed { i, (title, items) ->
                if (i > 0) HairlineDivider(Modifier.padding(vertical = 10.dp))
                Eyebrow(title)
                Spacer(Modifier.height(6.dp))
                items.forEach { Text("• $it", color = c.secondary, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
