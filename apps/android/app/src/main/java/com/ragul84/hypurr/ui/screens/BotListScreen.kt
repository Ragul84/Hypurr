package com.ragul84.hypurr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.foundation.layout.navigationBarsPadding
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.Pill
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.FlowOrb
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.LinkPill
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.theme.ColorFlow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

/** Roster order: pinned first, then the latest activity. Hidden bots stay off the list. */
fun rosterOrder(bots: Collection<Bot>): List<Bot> =
    bots.filter { !it.hidden }.sortedWith(compareByDescending<Bot> { it.pinned }.thenByDescending { it.lastAt })

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotListScreen(
    computerName: String,
    link: LinkState,
    bots: List<Bot>,
    synced: Boolean,
    onOpen: (Bot) -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onNewTask: () -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    val c = Hypurr.colors
    val needsYou = bots.filter { it.needsInput }
    val rest = bots.filter { !it.needsInput }
    Box(Modifier.fillMaxSize().background(c.bg)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bots", style = MaterialTheme.typography.headlineLarge, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(computerName, style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    Spacer(Modifier.width(8.dp))
                    LinkPill(link)
                }
            }
            if (link is LinkState.Failed || link is LinkState.HostOffline) {
                IconBubble(Icons.Rounded.Refresh, "Reconnect", onClick = onRetry)
                Spacer(Modifier.width(8.dp))
            }
            IconBubble(Icons.Rounded.Settings, "Settings", onClick = onSettings)
        }
        AnimatedVisibility(link is LinkState.HostOffline || link is LinkState.Unauthorized) {
            val text = when (link) {
                is LinkState.Unauthorized -> link.message
                else -> "Your computer is offline. Messages you send are queued in the relay and run when it's back."
            }
            Text(text, color = c.warning, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp), c.warning.copy(alpha = 0.1f), highlight = false).padding(14.dp))
        }
        if (synced && bots.isEmpty()) {
            EmptyRoster(onNewTask)
            return@Column
        }
        if (!synced && bots.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlowOrb(56.dp) }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (needsYou.isNotEmpty()) {
                item(key = "needs-header") { SectionLabel("Needs you", c.warning) }
                item(key = "needs") {
                    Column(Modifier.fillMaxWidth().animateItem().glass(RoundedCornerShape(24.dp), c.warning.copy(alpha = if (c.dark) 0.12f else 0.08f))
                        .padding(vertical = 4.dp)) {
                        needsYou.forEach { BotRow(it, now) { onOpen(it) } }
                    }
                }
                item(key = "all-header") { SectionLabel("All bots", c.tertiary) }
            }
            items(rest, key = { it.id }) { bot -> BotRow(bot, now, Modifier.animateItem()) { onOpen(bot) } }
        }
    }
    // Plain-language tasks: the main way in for someone new to agents.
    if (!(synced && bots.isEmpty())) {
        FlowButton("New task", Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), icon = Icons.Rounded.Add,
            onClick = onNewTask)
    }
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text.uppercase(), color = color, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun EmptyRoster(onNewTask: () -> Unit) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize()) {
        FlowBackdrop()
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowOrb(72.dp, animate = false)
            Spacer(Modifier.height(20.dp))
            Text("Start your first task", style = MaterialTheme.typography.titleLarge, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text("Say what you need in plain words, like “write tests for the login form”. Hypurr picks the agent and keeps your code safe on its own branch.",
                color = c.secondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            FlowButton("New task", icon = Icons.Rounded.Add, onClick = onNewTask)
        }
    }
}

@Composable
fun BotRow(bot: Bot, now: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).pressable(bot.name, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp).animateContentSize(Motion.spatialDefault()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(bot)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (bot.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.PushPin, "Pinned", tint = c.tertiary, modifier = Modifier.size(14.dp))
                }
                bot.task?.let { task ->
                    Spacer(Modifier.width(6.dp))
                    when {
                        !task.isActive -> Pill("Done", c.tertiary, icon = Icons.Rounded.Check)
                        task.hasSafetyNet -> Pill("Task", c.success, icon = Icons.Rounded.Shield)
                        else -> Pill("Task", c.warning)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(relativeTime(bot.lastAt, now), style = MaterialTheme.typography.labelMedium, color = c.tertiary)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusLine(bot) }
                if (bot.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(CircleShape).background(c.accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("${bot.unread}", color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Working shimmers in the colour flow; needs you and failed take their tokens. */
@Composable
fun StatusLine(bot: Bot) {
    val c = Hypurr.colors
    when {
        bot.needsInput -> Text("Needs you · ${bot.activity.ifEmpty { "waiting for your answer" }}", color = c.warning,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        bot.status == "working" -> {
            val still = LocalInspectionMode.current
            val pulse by rememberInfiniteTransition(label = "work").animateFloat(0.55f, 1f,
                infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
            Text(bot.activity.ifEmpty { "Working…" }, style = MaterialTheme.typography.bodyMedium.copy(brush = ColorFlow.linear()),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(if (still) 1f else pulse))
        }
        bot.failed -> Text("Failed · ${bot.lastMessage.orEmpty()}", color = c.danger, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> Text(bot.lastMessage ?: bot.description.ifEmpty { bot.folderName }, color = c.secondary,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
