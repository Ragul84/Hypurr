package com.ragul84.hypurr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Checkpoint
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.TaskInfo
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.riskColor
import com.ragul84.hypurr.ui.riskLabel
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.FlowOrb
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.theme.ColorFlow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    bot: Bot,
    entries: List<Entry>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRespond: (Entry, String?) -> Unit,
    onUndo: (Entry) -> Unit = {},
    onRollback: (Checkpoint) -> Unit = {},
    onFinishTask: () -> Unit = {},
    onSaveCheckpoint: () -> Unit = {},
    initialCheckpointsOpen: Boolean = false,
    now: Long = System.currentTimeMillis(),
) {
    val c = Hypurr.colors
    val chat = entries.filter { it.isChat }.reversed()
    val list = rememberLazyListState()
    val task = bot.task
    var checkpointsOpen by remember { mutableStateOf(initialCheckpointsOpen) }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = if (task != null) 168.dp else 112.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (bot.status == "working") item(key = "working") { WorkingRow(bot, Modifier.animateItem()) }
            items(chat, key = { it.data.clientNonce ?: it.id }) { entry ->
                Box(Modifier.animateItem(fadeInSpec = Motion.effects(), placementSpec = Motion.offset)) {
                    when (entry.kind) {
                        "user" -> UserBubble(entry)
                        "agent" -> AgentBubble(entry)
                        "permission" -> PermissionCard(entry, task, onRespond, onUndo)
                        else -> Notice(entry)
                    }
                }
            }
            if (chat.isEmpty()) item(key = "empty") { EmptyChat(bot) }
        }
        // Header: glass, floating over the transcript.
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), c.glass).statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, "Back", fill = c.surface.copy(alpha = 0.6f), onClick = onBack)
            Spacer(Modifier.width(10.dp))
            BotAvatar(bot, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box { StatusLine(bot) }
            }
            AnimatedVisibility(bot.isWorking, enter = scaleIn(Motion.spatialFast()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                IconBubble(Icons.Rounded.Stop, "Stop", tint = c.danger, fill = c.danger.copy(alpha = 0.12f), onClick = onStop)
            }
        }
        if (task != null) TaskStrip(task) { checkpointsOpen = true }
        }
        Composer(draft, onDraftChange, onSend, Modifier.align(Alignment.BottomCenter))
        if (task != null) {
            CheckpointSheet(checkpointsOpen, task, now, onClose = { checkpointsOpen = false },
                onRollback = { checkpointsOpen = false; onRollback(it) },
                onFinish = { checkpointsOpen = false; onFinishTask() }, onSave = onSaveCheckpoint)
        }
    }
}

/** The task's safety net at a glance: its branch and checkpoints; tap for the checkpoint list. */
@Composable
private fun TaskStrip(task: TaskInfo, onOpen: () -> Unit) {
    val c = Hypurr.colors
    val safe = task.hasSafetyNet
    val tint = if (safe) c.success else c.warning
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = 0.10f))
        .pressable("Checkpoints", onClick = onOpen).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (safe) Icons.Rounded.Shield else Icons.Rounded.ErrorOutline, null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(if (safe) "Safe branch · ${task.branch}" else "No safety net · not a git project", color = c.text,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = task.checkpoints.size
            Text(when {
                !task.isActive -> "Finished · kept for review"
                safe -> "$n checkpoint${if (n == 1) "" else "s"} · ${task.projectName}"
                else -> task.projectName
            }, color = c.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (safe) Icon(Icons.Rounded.History, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

/** One-tap rollback: every checkpoint, newest first. Going back asks once, inline. */
@Composable
private fun CheckpointSheet(open: Boolean, task: TaskInfo, now: Long, onClose: () -> Unit, onRollback: (Checkpoint) -> Unit,
                            onFinish: () -> Unit, onSave: () -> Unit) {
    val c = Hypurr.colors
    var confirm by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects())) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)).pressable("Close", onClick = onClose))
        }
        AnimatedVisibility(open, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.offset) { it } + fadeIn(), exit = slideOutVertically(Motion.offset) { it } + fadeOut()) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), c.surface)
                .navigationBarsPadding().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Checkpoints", style = MaterialTheme.typography.titleLarge, color = c.text)
                        Text("Go back to any point. What's there now is saved first, so nothing is lost.", color = c.secondary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconBubble(Icons.Rounded.Close, "Close", onClick = onClose)
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val list = task.checkpoints.reversed()
                    items(list, key = { it.id }) { cp ->
                        val latest = cp == list.first()
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.bg.copy(alpha = 0.5f)).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cp.label, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val files = if (cp.files.isEmpty()) "" else " · ${cp.files.size} file${if (cp.files.size == 1) "" else "s"}"
                                    Text("${relativeTime(cp.at, now)}$files", color = c.tertiary, style = MaterialTheme.typography.labelSmall)
                                }
                                if (latest) Pill("Now", c.success)
                                else if (task.isActive) Text("Go back", color = c.accent, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).pressable("Go back to ${cp.label}") { confirm = cp.id }
                                        .padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                            AnimatedVisibility(confirm == cp.id) {
                                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SoftButton("Go back here", tint = c.warning) { confirm = null; onRollback(cp) }
                                    SoftButton("Cancel", tint = c.secondary) { confirm = null }
                                }
                            }
                        }
                    }
                }
                if (task.isActive) {
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Save now", Modifier.weight(1f), icon = Icons.Rounded.Bookmark, onClick = onSave)
                        SoftButton("Finish task", Modifier.weight(1f), icon = Icons.Rounded.Check, tint = c.success, onClick = onFinish)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(bot: Bot) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BotAvatar(bot, 72.dp)
        Text(bot.name, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.padding(top = 12.dp))
        Text(bot.description.ifEmpty { bot.folderName }, color = c.secondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun UserBubble(entry: Entry) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SelectionContainer {
            Text(entry.data.text.orEmpty(), color = c.text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 56.dp).clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(c.bubbleUser).padding(horizontal = 16.dp, vertical = 11.dp))
        }
        when (entry.data.status) {
            "queued" -> Meta(Icons.Rounded.Schedule, "Queued", c.tertiary)
            "failed" -> Meta(Icons.Rounded.ErrorOutline, "Not sent", c.danger)
            "cancelled" -> Meta(Icons.Rounded.ErrorOutline, "Cancelled", c.tertiary)
        }
    }
}

@Composable
private fun Meta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(top = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AgentBubble(entry: Entry) {
    val c = Hypurr.colors
    SelectionContainer {
        Text(entry.data.text.orEmpty(), color = c.text, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 40.dp).clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                .background(c.bubbleAgent).padding(horizontal = 16.dp, vertical = 11.dp))
    }
}

/**
 * An explained approval card: one plain sentence, a risk level from the host's rules, the technical detail
 * folded away. Choices are text (an icon would be ambiguous). A task's card offers Undo after approving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionCard(entry: Entry, task: TaskInfo?, onRespond: (Entry, String?) -> Unit, onUndo: (Entry) -> Unit) {
    val c = Hypurr.colors
    val d = entry.data
    val pending = d.status == null || d.status == "pending"
    val blocked = d.blocked != null
    val risk = riskColor(d.risk)
    val tint by animateColorAsState(when {
        blocked -> c.danger
        pending -> risk
        else -> c.tertiary
    }, Motion.effects(), label = "perm")
    var details by remember(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(end = 24.dp).glass(RoundedCornerShape(24.dp), tint.copy(alpha = 0.10f)).padding(16.dp)
        .animateContentSize(Motion.spatialDefault())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (blocked) Icons.Rounded.Block else Icons.Rounded.Shield, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(when {
                blocked -> "Blocked by the safety net"
                pending -> "Needs you"
                else -> "Answered"
            }, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (d.risk != null) Pill(riskLabel(d.risk), risk)
        }
        Text(d.explain ?: d.title ?: "Allow this action?", color = c.text, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp))
        val why = d.blocked ?: d.riskReasons?.firstOrNull()
        if (why != null) Text(why, color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        val detail = d.command ?: d.detail
        if (d.explain != null) {
            Text(if (details) "Hide details" else "Show details", color = c.accent, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).pressable(if (details) "Hide details" else "Show details") { details = !details }
                    .padding(vertical = 4.dp))
        }
        if ((details || d.explain == null) && (!detail.isNullOrBlank() || d.title != null)) {
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg.copy(alpha = 0.6f)).padding(10.dp)) {
                if (d.explain != null && d.title != null) Text(d.title, color = c.text, style = MaterialTheme.typography.labelLarge)
                if (!detail.isNullOrBlank()) Text(detail, color = c.secondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 8,
                    overflow = TextOverflow.Ellipsis)
                d.riskReasons?.drop(1)?.forEach { Text("• $it", color = c.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (pending) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.options.orEmpty().forEach { option ->
                    val reject = option.kind.startsWith("reject")
                    SoftButton(option.name, tint = if (reject) c.danger else c.accent) { onRespond(entry, option.optionId) }
                }
            }
        } else if (!blocked) {
            val option = d.options?.firstOrNull { it.optionId == d.selected }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(option?.name ?: d.status.orEmpty().replaceFirstChar(Char::uppercase), color = c.secondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                val approved = option != null && !option.kind.startsWith("reject")
                if (approved && d.checkpoint != null && task?.hasSafetyNet == true && task.isActive) {
                    SoftButton("Undo", icon = Icons.AutoMirrored.Rounded.Undo, tint = c.warning) { onUndo(entry) }
                }
            }
        }
    }
}

@Composable
private fun Notice(entry: Entry) {
    val c = Hypurr.colors
    val error = entry.data.style == "error"
    Text(entry.data.text.orEmpty(), color = if (error) c.danger else c.tertiary, style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp))
}

@Composable
private fun WorkingRow(bot: Bot, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    Row(modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowOrb(22.dp)
        Spacer(Modifier.width(10.dp))
        Text(bot.activity.ifEmpty { "Thinking…" }, style = MaterialTheme.typography.bodyMedium.copy(brush = ColorFlow.linear()),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier) {
    val c = Hypurr.colors
    val canSend = draft.isNotBlank()
    val sendScale by animateFloatAsState(if (canSend) 1f else 0.85f, Motion.bouncy(), label = "send")
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp)
            .glass(RoundedCornerShape(30.dp), c.glass).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (draft.isEmpty()) Text("Message", color = c.tertiary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(draft, onDraftChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent), maxLines = 6, modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(44.dp).scale(sendScale).clip(CircleShape)
                .background(if (canSend) ColorFlow.linear() else SolidColor(c.border))
                .pressable("Send", enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowUpward, null, tint = if (canSend) ColorFlow.FlowInk else c.tertiary, modifier = Modifier.size(22.dp))
        }
    }
}

