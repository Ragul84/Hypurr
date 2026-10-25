package com.ragul84.hypurr.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.billing.Credits
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.rupees
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.costLabel
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.DotBars
import com.ragul84.hypurr.ui.DotFigure
import com.ragul84.hypurr.ui.DotProgress
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SectionHeader
import com.ragul84.hypurr.ui.Segmented
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

private const val DAY = 86_400_000L

/**
 * Spend tab (mockup panel 3): the credits balance as a big dot-matrix figure, Add credits / Limits,
 * this week's spend as dot bars with today in red, the free daily allowance as a dotted line, and
 * spend by bot in one card.
 */
@Composable
fun SpendScreen(
    computerName: String,
    link: LinkState,
    costs: TaskCosts?,
    bots: Map<String, Bot> = emptyMap(),
    onTab: (HomeTab) -> Unit = {},
    onBuyCredits: ((String) -> Unit)? = null,
    onLimits: () -> Unit = { onTab(HomeTab.You) },
    /** Plans in rupees from the computer; null hides the card. */
    pricing: com.ragul84.hypurr.model.Pricing? = null,
    /** Day the chart ends on; tests pin it. */
    now: Long = System.currentTimeMillis(),
    onDisconnectOwnFree: (() -> Unit)? = null,
) {
    val c = Hypurr.colors
    val ctx = LocalContext.current
    var range by remember { mutableIntStateOf(0) }
    var showPacks by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf<String?>(null) }
    var buyNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val data = costs ?: TaskCosts(
        gatewayCreditsLabel = "$0.00",
        freeResetLabel = "",
    )
    // Free messages exist only when the computer has Hypurr's gateway.
    val freeOn = data.freeTurnsLimit != null
    val left = data.freeTurnsLeft ?: 0
    val limit = (data.freeTurnsLimit ?: 10).coerceAtLeast(1)
    val used = (limit - left).coerceIn(0, limit)
    val byBot = data.tasks
        .groupBy { it.botId.ifEmpty { it.title } }
        .map { (id, rows) ->
            Triple(bots[id] ?: Bot(id = id, name = rows.firstOrNull()?.title?.ifEmpty { id } ?: id), rows.sumOf { it.usage.cost }, rows.size)
        }
        .sortedByDescending { it.second }
    // Last 7 days ending today, from the task rows; today is the last bar.
    val today0 = now - Math.floorMod(now + java.util.TimeZone.getDefault().getOffset(now), DAY) // local midnight
    val days = (6 downTo 0).map { back -> today0 - back * DAY }
    val perDay = days.map { start -> data.tasks.filter { it.createdAt in start until start + DAY }.sumOf { it.usage.cost }.toFloat() }
    val labels = days.map { java.text.SimpleDateFormat("EEEEE", java.util.Locale.ENGLISH).format(java.util.Date(it + 1)).take(1) }
    val spent = if (range == 0) data.week else data.total.cost

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Spend", style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.weight(1f))
                Segmented(listOf("Week", "Month"), range) { range = it }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        Eyebrow("Credits left")
                        Spacer(Modifier.height(6.dp))
                        DotFigure(data.gatewayCreditsLabel ?: "$0.00", SignalType.hero, c.text)
                        Spacer(Modifier.height(8.dp))
                        if (freeOn) com.ragul84.hypurr.ui.FreeMessagesNote(left, limit, onAddCredits = { showPacks = true }, ownFree = data.ownFree)
                        else Text(paceLine(data, left), color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Add credits", Modifier.weight(1f), icon = signalIcon(SignalIcons.Plus)) {
                                val url = data.buyCreditsUrl
                                when {
                                    url != null && onBuyCredits != null -> onBuyCredits(url)
                                    url != null -> try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
                                    else -> showPacks = !showPacks
                                }
                            }
                            SoftButton("Limits", Modifier.weight(1f).height(48.dp), icon = signalIcon(SignalIcons.Limit), onClick = onLimits)
                        }
                    }
                }
                if (showPacks) item {
                    val packs = pricing?.creditPacks?.packs.orEmpty()
                    val appUser = data.gatewayAppUserId
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Credits keep Hypurr's models going after your free messages, paid through Google Play.", color = c.secondary,
                                style = MaterialTheme.typography.bodyMedium)
                            if (!Credits.canBuy(appUser) || packs.isEmpty()) {
                                Text(
                                    if (!Credits.configured) "Buying credits isn't switched on in this build yet."
                                    else "Credits open once Hypurr's free models are live on your computer.",
                                    color = c.tertiary, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            packs.forEach { pack ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(rupees(pack.priceInr), color = c.text, style = MaterialTheme.typography.titleMedium)
                                        Text(pack.approxMessages?.let { "About $it messages" } ?: pack.label, color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    PrimaryButton(
                                        if (buying == pack.productId) "Opening…" else "Buy",
                                        Modifier.width(112.dp),
                                        enabled = Credits.canBuy(appUser) && buying == null,
                                    ) {
                                        val activity = ctx as? android.app.Activity ?: return@PrimaryButton
                                        val user = appUser ?: return@PrimaryButton
                                        buying = pack.productId
                                        buyNote = null
                                        scope.launch {
                                            buyNote = when (val r = Credits.buy(activity, user, pack.productId)) {
                                                Credits.Outcome.Bought -> "Thanks! Your credits appear here within a minute."
                                                Credits.Outcome.Cancelled -> null
                                                is Credits.Outcome.Failed -> r.message
                                            }
                                            buying = null
                                        }
                                    }
                                }
                            }
                            buyNote?.let { Text(it, color = c.secondary, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                item { HypurrFreeCard(freeOn, left, used, limit, data.ownFree, onDisconnectOwnFree) }
                item {
                    SignalCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    DotFigure(costLabel(spent, data.total.estimated).removePrefix("≈ "), SignalType.figure, c.text)
                                    Text(if (range == 0) "Spent this week" else "Spent in total", color = c.tertiary,
                                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                                }
                                Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface2).padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(signalIcon(SignalIcons.Trend), null, tint = c.text, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("${data.total.tasks} tasks", color = c.text, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            DotBars(perDay, labels, highlight = 6, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                }
                pricing?.takeIf { it.plans.isNotEmpty() }?.let { pr ->
                    item { PlansCard(pr, modifier = Modifier.padding(top = 0.dp)) }
                }
                if (byBot.isNotEmpty()) {
                    item { SectionHeader("By bot", Modifier.padding(start = 4.dp), action = "See all", trailingIcon = signalIcon(SignalIcons.Chevron)) { onTab(HomeTab.Tasks) } }
                    item {
                        SignalCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                                byBot.forEachIndexed { i, (bot, spend, tasks) ->
                                    if (i > 0) HairlineDivider(Modifier.padding(start = 50.dp))
                                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        BotAvatar(bot.copy(status = "idle"), 36.dp)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(bot.name, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                            Text(if (tasks == 1) "1 task" else "$tasks tasks", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                                        }
                                        DotFigure(costLabel(spend, false), SignalType.figureSmall, c.text)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text("Using Claude Code or Codex you already have? Hypurr adds nothing to your bill.", color = c.tertiary,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
        }
        HypurrTabBar(
            selected = HomeTab.Spend,
            modifier = Modifier.align(Alignment.BottomCenter),
            needsYou = bots.values.count { it.needsInput },
            onSelect = onTab,
        )
    }
}

private fun paceLine(data: TaskCosts, freeLeft: Int): String {
    val credits = data.gatewayCreditsLabel?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull() ?: 0.0
    val perDay = data.week / 7.0
    return when {
        credits <= 0.0 && freeLeft > 0 -> "$freeLeft free messages left"
        perDay <= 0.0 -> "No spend this week"
        else -> {
            val days = (credits / perDay).toInt()
            if (days <= 1) "About a day at your usual pace" else "About $days days at your usual pace"
        }
    }
}

/** Hypurr Free, said plainly: what it is, how much, and what not to use it for. */
@Composable
private fun HypurrFreeCard(on: Boolean, left: Int, used: Int, limit: Int, ownFree: Boolean = false, onDisconnectOwnFree: (() -> Unit)? = null) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.ragul84.hypurr.ui.DotCat(c.text, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Hypurr Free", color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                com.ragul84.hypurr.ui.Pill(if (on) "$left of $limit left" else "$limit messages", c.text)
            }
            Text("Free AI built in, for people without Claude Code or Codex. For trying out, not for company code.",
                color = c.secondary, style = MaterialTheme.typography.bodySmall)
            if (on) DotProgress(used.toFloat() / limit, Modifier.padding(top = 6.dp, bottom = 2.dp))
            Text("Claude Code and Codex you already have work as they are, at no extra cost.",
                color = c.tertiary, style = MaterialTheme.typography.bodySmall)
            // Optional: the user's own free account (the only place OpenRouter is named).
            val keepGoing = com.ragul84.hypurr.ui.LocalKeepGoingFree.current
            if (ownFree) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Your own OpenRouter account is connected", color = c.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (onDisconnectOwnFree != null) SoftButton("Disconnect", onClick = onDisconnectOwnFree)
                }
            } else if (keepGoing != null) {
                SoftButton("Keep going free with your own account", Modifier.fillMaxWidth().padding(top = 6.dp), onClick = keepGoing)
            }
        }
    }
}
