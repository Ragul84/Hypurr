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
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.ragul84.hypurr.model.Entry
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
) {
    val c = Hypurr.colors
    val chat = entries.filter { it.isChat }.reversed()
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 112.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (bot.status == "working") item(key = "working") { WorkingRow(bot, Modifier.animateItem()) }
            items(chat, key = { it.data.clientNonce ?: it.id }) { entry ->
                Box(Modifier.animateItem(fadeInSpec = Motion.effects(), placementSpec = Motion.offset)) {
                    when (entry.kind) {
                        "user" -> UserBubble(entry)
                        "agent" -> AgentBubble(entry)
                        "permission" -> PermissionCard(entry, onRespond)
                        else -> Notice(entry)
                    }
                }
            }
            if (chat.isEmpty()) item(key = "empty") { EmptyChat(bot) }
        }
        // Header: glass, floating over the transcript.
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp), c.glass).statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        Composer(draft, onDraftChange, onSend, Modifier.align(Alignment.BottomCenter))
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

/** An approval card: the bot asks before it acts. Choices are text (an icon would be ambiguous). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionCard(entry: Entry, onRespond: (Entry, String?) -> Unit) {
    val c = Hypurr.colors
    val pending = entry.data.status == null || entry.data.status == "pending"
    val tint by animateColorAsState(if (pending) c.warning else c.tertiary, Motion.effects(), label = "perm")
    Column(Modifier.fillMaxWidth().padding(end = 24.dp).glass(RoundedCornerShape(24.dp), tint.copy(alpha = 0.10f)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (pending) "Needs you" else "Answered", color = tint, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
        }
        Text(entry.data.title ?: "Allow this action?", color = c.text, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp))
        val detail = entry.data.command ?: entry.data.detail
        if (!detail.isNullOrBlank()) {
            Text(detail, color = c.secondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg.copy(alpha = 0.6f)).padding(10.dp))
        }
        if (pending) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.data.options.orEmpty().forEach { option ->
                    val reject = option.kind.startsWith("reject")
                    SoftButton(option.name, tint = if (reject) c.danger else c.accent) { onRespond(entry, option.optionId) }
                }
            }
        } else {
            val chosen = entry.data.options?.firstOrNull { it.optionId == entry.data.selected }?.name
            Text(chosen ?: entry.data.status.orEmpty().replaceFirstChar(Char::uppercase), color = c.secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
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

