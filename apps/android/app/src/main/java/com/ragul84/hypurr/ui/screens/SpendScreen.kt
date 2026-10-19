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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Bot
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
) {
    val c = Hypurr.colors
    val ctx = LocalContext.current
    var range by remember { mutableIntStateOf(0) }
    val data = costs ?: TaskCosts(
        gatewayCreditsLabel = "$0.00",
        freeTurnsLeft = 40,
        freeTurnsLimit = 40,
        freeResetLabel = "Resets at midnight",
        gatewayFreeRemaining = "40 / 40 turns",
    )
    val left = data.freeTurnsLeft ?: 0
    val limit = (data.freeTurnsLimit ?: 40).coerceAtLeast(1)
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
                        Text(paceLine(data, left), color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Add credits", Modifier.weight(1f), icon = signalIcon(SignalIcons.Plus)) {
                                val url = data.buyCreditsUrl ?: "https://checkout.stripe.com/c/pay/cs_test_placeholder"
                                if (onBuyCredits != null) onBuyCredits(url)
                                else try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
                            }
                            SoftButton("Limits", Modifier.weight(1f).height(48.dp), icon = signalIcon(SignalIcons.Limit), onClick = onLimits)
                        }
                    }
                }
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
                item {
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(signalIcon(SignalIcons.Shield), null, tint = c.text, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Free turns today · ${data.freeResetLabel ?: "resets daily"}", color = c.secondary,
                                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                DotFigure("$used", SignalType.figureSmall, c.text)
                                Text(" / $limit", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                            }
                            DotProgress(used.toFloat() / limit, Modifier.padding(top = 12.dp))
                        }
                    }
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
                pricing?.takeIf { it.plans.isNotEmpty() }?.let { pr ->
                    item { PlansCard(pr, modifier = Modifier.padding(top = 8.dp)) }
                }
                item {
                    Text("Using your own Claude or OpenAI key? That's free here.", color = c.tertiary,
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
        credits <= 0.0 && freeLeft > 0 -> "$freeLeft free turns left today"
        perDay <= 0.0 -> "No spend this week"
        else -> {
            val days = (credits / perDay).toInt()
            if (days <= 1) "About a day at your usual pace" else "About $days days at your usual pace"
        }
    }
}
