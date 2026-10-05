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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.costLabel
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.LinkPill
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.theme.Hypurr

/**
 * Dedicated Spend tab — free allowance gauge, credits + Buy, by-bot spend.
 * Matches /workspace/hypurr-shots/sunfield/spending.png.
 */
@Composable
fun SpendScreen(
    computerName: String,
    link: LinkState,
    costs: TaskCosts?,
    bots: Map<String, Bot> = emptyMap(),
    onTab: (HomeTab) -> Unit = {},
    onBuyCredits: ((String) -> Unit)? = null,
) {
    val c = Hypurr.colors
    val ctx = LocalContext.current
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
            val bot = bots[id]
            Triple(
                bot ?: Bot(id = id, name = rows.firstOrNull()?.title?.ifEmpty { id } ?: id),
                rows.sumOf { it.usage.cost },
                rows.sumOf { it.usage.turns },
            )
        }
        .sortedByDescending { it.second }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (c.dark) c.surface else androidx.compose.ui.graphics.Color(0xFF15130F))
                        .padding(horizontal = 10.dp, vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(c.success))
                            Spacer(Modifier.width(6.dp))
                            Text(computerName, color = if (c.dark) c.text else androidx.compose.ui.graphics.Color(0xFFFFF8E8),
                                style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    LinkPill(link)
                }
                Text("Spend", style = MaterialTheme.typography.headlineLarge, color = c.text, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(top = 14.dp))
                Text("Free allowance · credits", style = MaterialTheme.typography.bodyMedium, color = c.secondary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CreamPlate(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Pill("Free today", c.accent)
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                                Text("$left", color = c.text, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.displaySmall)
                                Text(" / $limit turns", color = c.secondary, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
                            }
                            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
                                .background(c.border.copy(alpha = 0.45f))) {
                                val frac = used.toFloat() / limit.toFloat()
                                Box(Modifier.fillMaxWidth(frac.coerceIn(0.02f, 1f)).height(10.dp)
                                    .clip(RoundedCornerShape(50)).background(c.accent))
                            }
                            Text(data.freeResetLabel ?: "Resets daily", color = c.secondary,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                item {
                    CreamPlate(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Credits", color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${data.gatewayCreditsLabel ?: "$0.00"} left", color = c.secondary,
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                            }
                            FlowButton("Buy credits") {
                                val url = data.buyCreditsUrl ?: "https://checkout.stripe.com/c/pay/cs_test_placeholder"
                                if (onBuyCredits != null) onBuyCredits(url)
                                else try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                } catch (_: Exception) { }
                            }
                        }
                    }
                }
                if (byBot.isNotEmpty()) {
                    item {
                        Text("By bot", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                    items(byBot, key = { it.first.id }) { (bot, spend, turns) ->
                        CreamPlate(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                BotAvatar(bot.copy(status = "idle"), 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bot.name, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${costLabel(spend, false)} today", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text("$turns turns", color = c.tertiary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                item {
                    Text("Using your own Claude/OpenAI key? Free.", color = c.secondary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                }
            }
        }
        HypurrTabBar(
            selected = HomeTab.Spend,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            onSelect = onTab,
        )
    }
}
