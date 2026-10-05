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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ragul84.hypurr.ui.SoftButton
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
import com.ragul84.hypurr.ui.motion.ThinkingScan
import com.ragul84.hypurr.ui.motion.RainShimmer
import com.ragul84.hypurr.ui.motion.LaunchFlicker
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.LinkPill
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.relativeTime
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
    /** Every bot by id: group rows show their members. */
    byId: Map<String, Bot> = emptyMap(),
    onNewBot: () -> Unit = {},
    onNewGroup: () -> Unit = {},
    /** Viewers only look: no new bots or groups. */
    canCreate: Boolean = true,
    initialMenuOpen: Boolean = false,
    /** Opens the computer's screen; null when the computer doesn't offer it. */
    onScreen: (() -> Unit)? = null,
    /** When set, show the built-in agent install card (Hypurr Agent free models). */
    builtinInstall: BuiltinInstallPrompt? = null,
    onInstallBuiltin: () -> Unit = {},
) {
    val c = Hypurr.colors
    var menu by remember { mutableStateOf(initialMenuOpen) }
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
            if (onScreen != null) {
                IconBubble(Icons.Rounded.DesktopWindows, "Computer screen", onClick = onScreen)
                Spacer(Modifier.width(8.dp))
            }
            if (canCreate) {
                IconBubble(if (menu) Icons.Rounded.Close else Icons.Rounded.Add, if (menu) "Close" else "New bot or group") { menu = !menu }
                Spacer(Modifier.width(8.dp))
            }
            IconBubble(Icons.Rounded.Settings, "Settings", onClick = onSettings)
        }
        AnimatedVisibility(menu) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("New bot", Modifier.weight(1f), icon = Icons.Rounded.SmartToy) { menu = false; onNewBot() }
                SoftButton("New group", Modifier.weight(1f), icon = Icons.Rounded.Groups) { menu = false; onNewGroup() }
            }
        }
        AnimatedVisibility(link is LinkState.HostOffline || link is LinkState.Unauthorized) {
            val text = when (link) {
                is LinkState.Unauthorized -> link.message
                else -> "Your computer is offline. Messages you send are queued in the relay and run when it's back."
            }
            Text(text, color = c.warning, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)).background(c.warning.copy(alpha = 0.1f)).padding(14.dp))
        }
        if (synced && bots.isEmpty()) {
            builtinInstall?.let { BuiltinInstallCard(it, onInstallBuiltin, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            EmptyRoster(onNewTask)
            return@Column
        }
        if (!synced && bots.isEmpty()) {
            RainShimmer(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlowOrb(56.dp) }
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            builtinInstall?.let { prompt ->
                item(key = "builtin-install") {
                    BuiltinInstallCard(prompt, onInstallBuiltin, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
            if (needsYou.isNotEmpty()) {
                item(key = "needs-header") { SectionLabel("Needs you", c.warning) }
                item(key = "needs") {
                    Column(Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .padding(vertical = 4.dp)) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(c.accent))
                        needsYou.forEach { BotRow(it, now, byId = byId) { onOpen(it) } }
                    }
                }
                item(key = "all-header") { SectionLabel("All bots", c.tertiary) }
            }
            items(rest, key = { it.id }) { bot -> BotRow(bot, now, Modifier.animateItem(), byId) { onOpen(bot) } }
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
    Text(text, color = color, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun EmptyRoster(onNewTask: () -> Unit) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize()) {
        FlowBackdrop()
        Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LaunchFlicker(play = true) { FlowOrb(72.dp, animate = false) }
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
fun BotRow(bot: Bot, now: Long, modifier: Modifier = Modifier, byId: Map<String, Bot> = emptyMap(), onClick: () -> Unit) {
    val c = Hypurr.colors
    Row(
        modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .pressable(bot.name, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize(Motion.spatialDefault()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bot.isGroup) GroupAvatar(bot, byId) else BotAvatar(bot)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (bot.isGroup) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Groups, "Group", tint = c.tertiary, modifier = Modifier.size(15.dp))
                }
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
            val activity = bot.activity.ifEmpty { "Working…" }
            ThinkingScan(
                phases = listOf("Reading files", "Planning", activity),
                animate = !LocalInspectionMode.current,
            )
        }
        bot.failed -> Text("Failed · ${bot.lastMessage.orEmpty()}", color = c.danger, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> Text(bot.lastMessage ?: bot.description.ifEmpty { bot.folderName }, color = c.secondary,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Offer to install Hypurr's built-in Hypurr Agent (free Zen models). */
data class BuiltinInstallPrompt(
    val title: String = "Hypurr Agent agent",
    val body: String = "Install Hypurr Agent into ~/.hypurr/agents/hypurr-agent and start with free models via the Hypurr gateway. No other AI account needed.",
    val consent: String = "Free models use the Hypurr gateway with a daily allowance; paid models use credits. Bring-your-own-key is also supported.",
    val busy: Boolean = false,
    val error: String? = null,
)

@Composable
private fun BuiltinInstallCard(prompt: BuiltinInstallPrompt, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prompt.title, color = c.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill("Free", c.success)
        }
        Text(prompt.body, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Text(prompt.consent, color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        prompt.error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
        FlowButton(if (prompt.busy) "Installing…" else "Install Hypurr Agent", enabled = !prompt.busy, onClick = onInstall)
    }
}
