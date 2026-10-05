package com.ragul84.hypurr.ui.screens
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.ragul84.hypurr.ui.motion.CatPeek
import com.ragul84.hypurr.ui.sunfieldVector
import com.ragul84.hypurr.ui.SunfieldIcons

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ragul84.hypurr.ui.SoftButton
import androidx.compose.foundation.layout.navigationBarsPadding
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.Pill
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
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
import com.ragul84.hypurr.ui.ApprovalActions
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.CatFace
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowOrb
import com.ragul84.hypurr.ui.WorkingPhase
import com.ragul84.hypurr.ui.motion.RainShimmer
import com.ragul84.hypurr.ui.motion.LaunchFlicker
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.LinkPill
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion
import com.ragul84.hypurr.model.PermissionOption
import androidx.compose.ui.graphics.vector.ImageVector

/** Roster order: pinned first, then the latest activity. Hidden bots stay off the list. */
fun rosterOrder(bots: Collection<Bot>): List<Bot> =
    bots.filter { !it.hidden }.sortedWith(compareByDescending<Bot> { it.pinned }.thenByDescending { it.lastAt })

/** Pending approval surfaced on the bots list for one-tap Allow / Always / Deny. */
data class NeedsYouAsk(
    val title: String,
    val meta: String = "",
    val options: List<PermissionOption> = listOf(
        PermissionOption("allow", "Allow once", "allow_once"),
        PermissionOption("always", "Always", "allow_always"),
        PermissionOption("reject", "Deny", "reject_once"),
    ),
    val entryId: String? = null,
)

enum class HomeTab { Bots, Tasks, Spend, You }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    /** Pending asks keyed by bot id — powers one-tap approve on Needs-you cards. */
    asks: Map<String, NeedsYouAsk> = emptyMap(),
    onRespondAsk: (botId: String, entryId: String?, optionId: String?) -> Unit = { _, _, _ -> },
    onSpend: () -> Unit = {},
    onTasks: () -> Unit = {},
    showTabBar: Boolean = true,
) {
    val c = Hypurr.colors
    var menu by remember { mutableStateOf(initialMenuOpen) }
    var refreshing by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val needsYou = bots.filter { it.needsInput }
    val rest = bots.filter { !it.needsInput }
    Box(Modifier.fillMaxSize().background(c.bg)) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bots", style = MaterialTheme.typography.headlineLarge, color = c.text, fontWeight = FontWeight.ExtraBold)
                val quiet = bots.count { !it.needsInput }
                val need = needsYou.size
                if (need > 0) {
                    Text(
                        if (need == 1) "One needs you · $quiet quiet" else "$need need you · $quiet quiet",
                        style = MaterialTheme.typography.bodyMedium, color = c.secondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (synced && bots.isEmpty()) {
                    Text(
                        "Not connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(computerName, style = MaterialTheme.typography.labelMedium, color = c.tertiary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        Spacer(Modifier.width(8.dp))
                        LinkPill(link)
                    }
                }
            }
            if (link is LinkState.Failed || link is LinkState.HostOffline) {
                IconBubble(sunfieldVector(SunfieldIcons.Refresh), "Reconnect", onClick = onRetry)
                Spacer(Modifier.width(8.dp))
            }
            if (onScreen != null) {
                IconBubble(sunfieldVector(SunfieldIcons.Host), "Computer screen", onClick = onScreen)
                Spacer(Modifier.width(8.dp))
            }
            if (canCreate) {
                IconBubble(if (menu) sunfieldVector(SunfieldIcons.Close) else sunfieldVector(SunfieldIcons.Plus), if (menu) "Close" else "New bot or group") { menu = !menu }
                Spacer(Modifier.width(8.dp))
            }
            IconBubble(sunfieldVector(SunfieldIcons.Settings), "Settings", onClick = onSettings)
        }
        AnimatedVisibility(menu) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("New bot", Modifier.weight(1f), icon = sunfieldVector(SunfieldIcons.Bots)) { menu = false; onNewBot() }
                SoftButton("New group", Modifier.weight(1f), icon = sunfieldVector(SunfieldIcons.Team)) { menu = false; onNewGroup() }
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
            EmptyRoster(onNewTask, onPair = onSettings, onInstall = onInstallBuiltin)
            return@Column
        }
        if (!synced && bots.isEmpty()) {
            RainShimmer(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FlowOrb(56.dp) }
            }
            return@Column
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    delay(900)
                    refreshing = false
                    onRetry()
                }
            },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                CatPeek(
                    progress = when {
                        refreshing -> 1f
                        else -> (pullState.distanceFraction).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                builtinInstall?.let { prompt ->
                    item(key = "builtin-install") {
                        BuiltinInstallCard(prompt, onInstallBuiltin, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                    }
                }
                if (needsYou.isNotEmpty()) {
                    item(key = "needs-header") { SectionLabel("Needs you", c.accent) }
                    items(needsYou, key = { "need-${it.id}" }) { bot ->
                        NeedsYouCard(
                            bot = bot,
                            ask = asks[bot.id] ?: NeedsYouAsk(
                                title = plainAsk(bot.activity),
                                meta = "${bot.name} · $computerName",
                            ),
                            modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 6.dp),
                            onOpen = { onOpen(bot) },
                            onChoose = { optionId ->
                                val ask = asks[bot.id]
                                if (ask?.entryId != null) onRespondAsk(bot.id, ask.entryId, optionId)
                                else onOpen(bot)
                            },
                        )
                    }
                    item(key = "all-header") { SectionLabel("Your bots", c.tertiary) }
                }
                items(rest, key = { it.id }) { bot -> BotRow(bot, now, Modifier.animateItem(), byId) { onOpen(bot) } }
            }
        }
    }
    if (showTabBar) {
        HypurrTabBar(
            selected = HomeTab.Bots,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            onSelect = { tab ->
                when (tab) {
                    HomeTab.Bots -> Unit
                    HomeTab.Tasks -> onTasks()
                    HomeTab.Spend -> onSpend()
                    HomeTab.You -> onSettings()
                }
            },
        )
    } else if (!(synced && bots.isEmpty())) {
        FlowButton("New task", Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), icon = sunfieldVector(SunfieldIcons.Plus),
            onClick = onNewTask)
    }
    }
}

private fun plainAsk(activity: String): String {
    val a = activity.trim()
    if (a.isEmpty()) return "Needs your OK"
    if (a.endsWith("?")) return a
    // "wants to run cargo test --all" → "Run cargo test --all?"
    val stripped = a.removePrefix("wants to ").removePrefix("Needs approval: ").trim()
    return stripped.replaceFirstChar { it.uppercase() }.let { if (it.endsWith("?")) it else "$it?" }
}

@Composable
private fun NeedsYouCard(
    bot: Bot,
    ask: NeedsYouAsk,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onChoose: (String?) -> Unit,
) {
    val c = Hypurr.colors
    CreamPlate(modifier.fillMaxWidth().pressable(bot.name, onClick = onOpen)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Pill("Needs you", c.accent)
            Text(ask.title, color = c.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 10.dp))
            if (ask.meta.isNotBlank()) {
                Text(ask.meta, color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            ApprovalActions(ask.options, Modifier.padding(top = 14.dp), onChoose = onChoose)
        }
    }
}

@Composable
fun HypurrTabBar(selected: HomeTab, modifier: Modifier = Modifier, onSelect: (HomeTab) -> Unit) {
    val c = Hypurr.colors
    val fill = if (c.dark) Color(0xFF0A2E24) else Color(0xFF15130F)
    val h = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(fill).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(HomeTab.Bots, "Bots", sunfieldVector(SunfieldIcons.Bots)),
            Triple(HomeTab.Tasks, "Tasks", sunfieldVector(SunfieldIcons.Tasks)),
            Triple(HomeTab.Spend, "Spend", sunfieldVector(SunfieldIcons.Spend)),
            Triple(HomeTab.You, "You", sunfieldVector(SunfieldIcons.You)),
        ).forEach { (tab, label, icon) ->
            TabItem(label, icon, selected == tab) {
                h.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onSelect(tab)
            }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val c = Hypurr.colors
    val color = if (active) Color(0xFFFFF8E8) else Color(0x99FFF8E8)
    Column(
        Modifier.pressable(label, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun EmptyRoster(onNewTask: () -> Unit, onPair: () -> Unit = {}, onInstall: () -> Unit = {}) {
    val c = Hypurr.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Chunky ink cat tile with press shadow (Sunfield hero)
        Box(
            Modifier
                .padding(bottom = 5.dp)
                .drawBehind {
                    val r = 28.dp.toPx()
                    drawRoundRect(
                        color = c.press.copy(alpha = if (c.dark) 0.5f else 0.25f),
                        topLeft = Offset(0f, 5.dp.toPx()),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
                .size(92.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(if (c.dark) c.surface else androidx.compose.ui.graphics.Color(0xFF15130F)),
            contentAlignment = Alignment.Center,
        ) {
            CatFace(if (c.dark) c.text else androidx.compose.ui.graphics.Color(0xFFFFF8E8), 56.dp)
        }
        Spacer(Modifier.height(22.dp))
        Text("Meet Hypurr", style = MaterialTheme.typography.headlineMedium, color = c.text, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "AI coding agents, safe enough for anyone.",
            color = c.secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(18.dp))
        CreamPlate(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    "Install Hypurr Agent on your computer",
                    "Scan the QR, or paste the pairing link",
                    "Run your first task",
                ).forEachIndexed { i, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(line, color = c.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        FlowButton("Install Hypurr Agent", Modifier.fillMaxWidth(), onClick = onInstall)
        Spacer(Modifier.height(10.dp))
        SoftButton("Pair with QR", Modifier.fillMaxWidth(), tint = c.text, onClick = onPair)
        Spacer(Modifier.height(10.dp))
        SoftButton("Or start a task", Modifier.fillMaxWidth(), tint = c.accent, icon = sunfieldVector(SunfieldIcons.Plus), onClick = onNewTask)
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
                    Icon(sunfieldVector(SunfieldIcons.Team), "Group", tint = c.tertiary, modifier = Modifier.size(15.dp))
                }
                if (bot.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(sunfieldVector(SunfieldIcons.NeedsYou), "Pinned", tint = c.tertiary, modifier = Modifier.size(14.dp))
                }
                bot.task?.let { task ->
                    Spacer(Modifier.width(6.dp))
                    when {
                        !task.isActive -> Pill("Done", c.tertiary, icon = sunfieldVector(SunfieldIcons.Check))
                        task.hasSafetyNet -> Pill("Task", c.success, icon = sunfieldVector(SunfieldIcons.Shield))
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
        bot.needsInput -> Text("Waiting on you", color = c.accent,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        bot.status == "working" -> WorkingPhase(bot.activity.ifEmpty { "Working…" }, animate = !LocalInspectionMode.current)
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
