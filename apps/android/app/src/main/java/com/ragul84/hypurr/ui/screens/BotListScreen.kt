package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.PermissionOption
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.ApprovalActions
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.DotFigure
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SectionHeader
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.StatusLabel
import com.ragul84.hypurr.ui.WorkingPhase
import com.ragul84.hypurr.ui.motion.CatPeek
import com.ragul84.hypurr.ui.motion.DotCatMark
import com.ragul84.hypurr.ui.motion.DotShimmer
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.signalState
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion
import com.ragul84.hypurr.ui.theme.SignalType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /** The paired computer's OS ("windows", "linux", "macos"). */
    hostOs: String = "",
    /** What's already set up (paired, agent ready), so the empty-state checklist never asks twice. */
    setupProgress: SetupProgress = SetupProgress(),
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
        // Brand row: dot cat + Doto wordmark, quiet icon actions, You avatar.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            DotCat(c.text, 20.dp)
            Spacer(Modifier.width(8.dp))
            Text("Hypurr", style = SignalType.wordmark, color = c.text, modifier = Modifier.weight(1f))
            if (link is LinkState.Failed || link is LinkState.HostOffline) {
                IconBubble(signalIcon(SignalIcons.Refresh), "Reconnect", onClick = onRetry)
            }
            if (onScreen != null) IconBubble(signalIcon(SignalIcons.Host), "Computer screen", onClick = onScreen)
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(c.surface2).border(1.dp, c.border, CircleShape)
                    .pressable("You and settings", onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(signalIcon(SignalIcons.You), null, tint = c.text, modifier = Modifier.size(18.dp)) }
        }
        if (synced && bots.isEmpty()) {
            Greeting(computerName, link, now, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            builtinInstall?.let { BuiltinInstallCard(it, onInstallBuiltin, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            EmptyRoster(onNewTask, onPair = onSettings, onInstall = onInstallBuiltin, windows = isWindows(hostOs) && builtinInstall == null,
                progress = setupProgress, computerName = computerName)
            return@Column
        }
        if (!synced && bots.isEmpty()) {
            DotShimmer(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotCatMark(56.dp) }
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
                    progress = if (refreshing) 1f else pullState.distanceFraction.coerceIn(0f, 1f),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "hello") { Greeting(computerName, link, now, Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) }
                item(key = "summary") { Summary(bots, now, Modifier.padding(top = 6.dp)) }
                item(key = "offline") {
                    androidx.compose.animation.AnimatedVisibility(link is LinkState.HostOffline || link is LinkState.Unauthorized) {
                        val text = when (link) {
                            is LinkState.Unauthorized -> link.message
                            else -> "Your computer is offline. Messages you send wait in the relay and run when it's back."
                        }
                        SignalCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(signalIcon(SignalIcons.NeedsYou), null, tint = c.needsText, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(text, color = c.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                item(key = "header") {
                    Column {
                        SectionHeader("Your bots", Modifier.padding(start = 4.dp), action = if (canCreate) (if (menu) "Close" else "New bot") else null,
                            actionIcon = if (canCreate) signalIcon(if (menu) SignalIcons.Close else SignalIcons.Plus) else null) { menu = !menu }
                        AnimatedVisibility(menu) {
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SoftButton("New bot", Modifier.weight(1f), icon = signalIcon(SignalIcons.Bots)) { menu = false; onNewBot() }
                                SoftButton("New group", Modifier.weight(1f), icon = signalIcon(SignalIcons.Team)) { menu = false; onNewGroup() }
                            }
                        }
                    }
                }
                builtinInstall?.let { prompt ->
                    item(key = "builtin-install") { BuiltinInstallCard(prompt, onInstallBuiltin, Modifier) }
                }
                items(needsYou, key = { "need-${it.id}" }) { bot ->
                    NeedsYouCard(
                        bot = bot,
                        ask = asks[bot.id],
                        byId = byId,
                        modifier = Modifier.animateItem(),
                        onOpen = { onOpen(bot) },
                        onChoose = { optionId ->
                            val ask = asks[bot.id]
                            if (ask?.entryId != null) onRespondAsk(bot.id, ask.entryId, optionId) else onOpen(bot)
                        },
                    )
                }
                items(rest, key = { it.id }) { bot -> BotRow(bot, now, Modifier.animateItem(), byId) { onOpen(bot) } }
            }
        }
    }
    if (showTabBar) {
        HypurrTabBar(
            selected = HomeTab.Bots,
            modifier = Modifier.align(Alignment.BottomCenter),
            needsYou = needsYou.size,
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
        PrimaryButton("New task", Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp), icon = signalIcon(SignalIcons.Plus),
            onClick = onNewTask)
    }
    }
}

private fun greetingFor(now: Long): String {
    val h = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** "● KEVIN'S MAC STUDIO IS ONLINE" + the big greeting. */
@Composable
private fun Greeting(computerName: String, link: LinkState, now: Long, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val (line, dot) = when (link) {
        is LinkState.Ready -> ("$computerName is online" + (if (link.route == com.ragul84.hypurr.net.Route.Relay) " · relay" else "")) to c.text
        LinkState.Connecting -> "Connecting to $computerName" to c.tertiary
        is LinkState.HostOffline -> "$computerName is offline" to c.needs
        is LinkState.Unauthorized -> "Not allowed on $computerName" to c.needs
        is LinkState.Failed -> "Can't reach $computerName" to c.needs
    }
    Column(modifier) {
        Eyebrow(line, dot = dot, color = if (dot == c.needs) c.needsText else c.secondary)
        Spacer(Modifier.height(8.dp))
        Text(greetingFor(now), style = MaterialTheme.typography.headlineLarge, color = c.text)
    }
}

/** Three widget cards: Needs you (red figure when > 0), Running, Done today. */
@Composable
private fun Summary(bots: List<Bot>, now: Long, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val need = bots.count { it.needsInput }
    val running = bots.count { it.status == "working" }
    val done = bots.count { !it.isWorking && !it.failed && it.lastAt > 0 && now - it.lastAt < 24 * 3_600_000L }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple(need, "Needs you", need > 0),
            Triple(running, "Running", false),
            Triple(done, "Done today", false),
        ).forEach { (n, label, red) ->
            SignalCard(Modifier.weight(1f)) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 13.dp)) {
                    DotFigure("$n", SignalType.counter, if (red) c.needs else c.text)
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = if (red) c.text else c.tertiary, style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (red) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
                }
            }
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

/** The live card: 1.5dp ink border, red cat, red status, and (when the ask is known) Deny / Allow once. */
@Composable
private fun NeedsYouCard(
    bot: Bot,
    ask: NeedsYouAsk?,
    byId: Map<String, Bot>,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onChoose: (String?) -> Unit,
) {
    SignalCard(modifier.fillMaxWidth().pressable(bot.name, onClick = onOpen), emphasis = true) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            BotCardBody(bot, byId, line = ask?.title ?: plainAsk(bot.activity))
            if (ask?.entryId != null) {
                val options = ask.options.filter { !it.kind.contains("always", true) && !it.name.contains("always", true) }
                ApprovalActions(options, Modifier.padding(top = 12.dp), onChoose = onChoose)
            }
        }
    }
}

/** Tile + name + status, a line, and "Codex · folder" meta. Shared by every bot card. */
@Composable
private fun BotCardBody(bot: Bot, byId: Map<String, Bot>, line: String? = null, meta: String? = null, now: Long? = null) {
    val c = Hypurr.colors
    Row(verticalAlignment = Alignment.Top) {
        if (bot.isGroup) GroupAvatar(bot, byId, 48.dp) else BotAvatar(bot, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                if (bot.unread > 0 && !bot.needsInput) {
                    Box(Modifier.clip(CircleShape).background(c.accent).padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text("${bot.unread}", color = c.onAccent, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                StatusLabel(bot.signalState())
            }
            Spacer(Modifier.height(3.dp))
            if (line != null) {
                Text(line, color = c.secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                StatusLine(bot)
            }
            val agent = agentName(bot.backend)
            val where = if (bot.isGroup) groupMembersLine(bot, byId) else bot.folderName.ifBlank { null }
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = meta ?: listOfNotNull(agent, now?.let { relativeTime(bot.lastAt, it) }?.takeIf { it.isNotBlank() }).joinToString(" · ")
                if (label.isNotBlank()) {
                    Text(label, color = c.tertiary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                if (where != null && meta == null) {
                    if (label.isNotBlank()) Text("  ·  ", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                    Icon(signalIcon(if (bot.isGroup) SignalIcons.Team else SignalIcons.Folder), null, tint = c.tertiary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(where, color = c.tertiary, style = if (bot.isGroup) MaterialTheme.typography.labelMedium else SignalType.mono.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Backend id → the agent's product name. */
fun agentName(backend: String): String? = when (backend.lowercase()) {
    "" -> null
    "claude", "claude-code" -> "Claude Code"
    "codex" -> "Codex"
    "gemini" -> "Gemini CLI"
    "hypurr-agent", "opencode" -> "Hypurr Agent"
    "cursor" -> "Cursor"
    else -> backend.replaceFirstChar { it.uppercase() }
}

/** Bottom bar: white with a hairline; the active tab's icon sits in a black pill; red badge on Bots. */
@Composable
fun HypurrTabBar(selected: HomeTab, modifier: Modifier = Modifier, needsYou: Int = 0, onSelect: (HomeTab) -> Unit) {
    val c = Hypurr.colors
    val h = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Triple(HomeTab.Bots, "Bots", SignalIcons.Bots),
                Triple(HomeTab.Tasks, "Tasks", SignalIcons.Tasks),
                Triple(HomeTab.Spend, "Spend", SignalIcons.Spend),
                Triple(HomeTab.You, "You", SignalIcons.You),
            ).forEach { (tab, label, icon) ->
                TabItem(label, signalIcon(icon), selected == tab, if (tab == HomeTab.Bots) needsYou else 0, Modifier.weight(1f)) {
                    h.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onSelect(tab)
                }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, active: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    val pill by androidx.compose.animation.animateColorAsState(if (active) c.accent else Color.Transparent, Motion.effects(), label = "pill")
    Column(
        modifier.pressable(label, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(Modifier.width(52.dp).height(30.dp).clip(RoundedCornerShape(50)).background(pill), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (active) c.onAccent else c.tertiary, modifier = Modifier.size(21.dp))
            }
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-3).dp).size(17.dp).clip(CircleShape).background(c.surface).padding(1.5.dp)
                        .clip(CircleShape).background(c.needs).semantics { contentDescription = "$badge need you" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$badge", color = c.onNeeds, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 10.sp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (active) c.text else c.tertiary, style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium))
    }
}

@Composable
private fun EmptyRoster(
    onNewTask: () -> Unit, onPair: () -> Unit = {}, onInstall: () -> Unit = {}, windows: Boolean = false,
    progress: SetupProgress = SetupProgress(), computerName: String = "",
) {
    val c = Hypurr.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DotCatMark(96.dp)
        Spacer(Modifier.height(22.dp))
        Text(if (progress.ready) "You're set up" else "Meet Hypurr", style = MaterialTheme.typography.headlineMedium, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(
            if (progress.ready) "Ask in plain words. It asks before anything risky and shows you it works." else com.ragul84.hypurr.ui.Pitch.Subhead,
            color = c.secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        SignalCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                setupChecklist(progress, windows, computerName).forEachIndexed { i, (line, done) ->
                    if (i > 0) HairlineDivider()
                    Row(Modifier.padding(vertical = 12.dp).semantics { contentDescription = if (done) "$line, done" else line },
                        verticalAlignment = Alignment.CenterVertically) {
                        DotFigure("${i + 1}", SignalType.figureSmall, if (done) c.tertiary else c.text, Modifier.width(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(line, color = if (done) c.secondary else c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (done) Pill("Done", c.text)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SignalCard(Modifier.fillMaxWidth()) { com.ragul84.hypurr.ui.PitchPillars(Modifier.padding(16.dp), compact = true) }
        Spacer(Modifier.height(18.dp))
        when {
            progress.ready -> PrimaryButton("Start a task", Modifier.fillMaxWidth(), onClick = onNewTask)
            !progress.paired -> PrimaryButton("Pair with QR", Modifier.fillMaxWidth(), onClick = onPair)
            else -> PrimaryButton(if (windows) "Use an agent you have" else "Install Hypurr Agent", Modifier.fillMaxWidth(), onClick = onInstall)
        }
        if (!progress.ready) {
            Spacer(Modifier.height(10.dp))
            SoftButton("Start a task", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Plus), onClick = onNewTask)
        }
    }
}

/** What's already done on the first-run checklist, read from the paired computer's hello. */
data class SetupProgress(val paired: Boolean = false, val agentReady: Boolean = false) {
    val ready get() = paired && agentReady
}

fun setupProgress(hello: com.ragul84.hypurr.model.Hello?): SetupProgress {
    val paired = agentSetupMode(hello) != AgentSetupMode.NotPaired
    val agentReady = paired && (hello?.builtinAgent?.installed == true ||
        hello?.backends.orEmpty().any { it.available && it.installed && !it.needsInstall })
    return SetupProgress(paired, agentReady)
}

/** Checklist lines (pair first: you can't install on a PC you haven't paired) with done flags. */
fun setupChecklist(p: SetupProgress, windows: Boolean = false, computerName: String = ""): List<Pair<String, Boolean>> = listOf(
    (if (p.paired) "Paired with ${computerName.ifEmpty { "your computer" }}" else "Scan the QR, or paste the pairing link") to p.paired,
    (if (p.agentReady) "Agent ready on your computer" else if (windows) "Pick an agent you already have on your PC" else "Install Hypurr Agent on your computer") to p.agentReady,
    "Run your first task" to false,
)

@Composable
fun BotRow(bot: Bot, now: Long, modifier: Modifier = Modifier, byId: Map<String, Bot> = emptyMap(), onClick: () -> Unit) {
    SignalCard(modifier.fillMaxWidth().pressable(bot.name, onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp).animateContentSize(Motion.spatialDefault())) {
            BotCardBody(bot, byId, now = now)
        }
    }
}

/** What the bot is doing in one line; the thinking row while it works. */
@Composable
fun StatusLine(bot: Bot) {
    val c = Hypurr.colors
    when {
        bot.needsInput -> Text(plainAsk(bot.activity), color = c.secondary,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        bot.status == "working" -> WorkingPhase(bot.activity.ifEmpty { "Working…" }, animate = !LocalInspectionMode.current)
        bot.failed -> Text(bot.lastMessage.orEmpty().ifEmpty { "Stopped with an error" }, color = c.needsText, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> Text(bot.lastMessage ?: bot.description.ifEmpty { bot.folderName }, color = c.secondary,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Offer to install Hypurr's built-in Hypurr Agent (Hypurr Free models). */
data class BuiltinInstallPrompt(
    val title: String = "Hypurr Agent",
    val body: String = "For people without Claude Code or Codex. Starts with Hypurr Free: 10 messages to try, no AI account needed.",
    val consent: String = "Hypurr Free is for trying out, not for company code. After 10 messages, buy credits or use your own key.",
    val busy: Boolean = false,
    val error: String? = null,
)

@Composable
private fun BuiltinInstallCard(prompt: BuiltinInstallPrompt, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    SignalCard(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotCat(c.text, 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(prompt.title, color = c.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill("Free", c.text)
        }
        Text(prompt.body, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Text(prompt.consent, color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        prompt.error?.let { Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton(if (prompt.busy) "Installing…" else "Install Hypurr Agent", Modifier.fillMaxWidth(), enabled = !prompt.busy, onClick = onInstall)
    } }
}
