package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.signalIcon

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.TaskTemplate
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.ui.BotAvatar
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.SectionHeader
import com.ragul84.hypurr.ui.StatusLabel
import com.ragul84.hypurr.ui.signalState
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.relativeTime
import com.ragul84.hypurr.ui.templateIcon
import com.ragul84.hypurr.ui.theme.Hypurr

/** Tasks tab: templates + recent task bots + New task CTA. */
@Composable
fun TasksScreen(
    computerName: String,
    link: LinkState,
    templates: List<TaskTemplate>,
    recent: List<Bot>,
    now: Long = System.currentTimeMillis(),
    onNewTask: () -> Unit = {},
    onStandup: () -> Unit = {},
    onOpenTemplate: (TaskTemplate) -> Unit = {},
    onOpenBot: (Bot) -> Unit = {},
    onTab: (HomeTab) -> Unit = {},
) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
                Text("Tasks", style = MaterialTheme.typography.headlineLarge, color = c.text)
                Spacer(Modifier.height(6.dp))
                Eyebrow(computerName, dot = if (link is LinkState.Ready) c.text else c.needs, color = c.secondary)
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    PrimaryButton("New task", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Plus), onClick = onNewTask)
                }
                item {
                    SignalCard(Modifier.fillMaxWidth().pressable("Daily standup", onClick = onStandup)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                                Icon(signalIcon(SignalIcons.Clock), null, tint = c.text, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Daily standup", color = c.text, style = MaterialTheme.typography.titleSmall)
                                Text("Yesterday, today and blockers, written for you", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                if (templates.isNotEmpty()) {
                    item { SectionHeader("Start from a template", Modifier.padding(start = 4.dp)) }
                    item {
                        SignalCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                                templates.take(6).forEachIndexed { i, tpl ->
                                    if (i > 0) HairlineDivider(Modifier.padding(start = 54.dp))
                                    Row(Modifier.fillMaxWidth().pressable(tpl.title) { onOpenTemplate(tpl) }.padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2),
                                            contentAlignment = Alignment.Center) {
                                            Icon(templateIcon(tpl.icon), null, tint = c.text, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(tpl.title, color = c.text, style = MaterialTheme.typography.titleSmall)
                                            if (tpl.summary.isNotBlank()) {
                                                Text(tpl.summary, color = c.tertiary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                            }
                                        }
                                        Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                val taskBots = recent.filter { it.task != null }
                if (taskBots.isNotEmpty()) {
                    item { SectionHeader("Recent", Modifier.padding(start = 4.dp)) }
                    items(taskBots, key = { it.id }) { bot ->
                        SignalCard(Modifier.fillMaxWidth().pressable(bot.name) { onOpenBot(bot) }, emphasis = bot.needsInput) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                BotAvatar(bot, 40.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bot.name, color = c.text, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text(bot.activity.ifEmpty { bot.lastMessage ?: "Task" }, color = c.tertiary,
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                if (bot.needsInput || bot.task?.isActive == false || bot.status == "working") StatusLabel(bot.signalState())
                                else Text(relativeTime(bot.lastAt, now), color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        HypurrTabBar(
            selected = HomeTab.Tasks,
            modifier = Modifier.align(Alignment.BottomCenter),
            needsYou = recent.count { it.needsInput },
            onSelect = onTab,
        )
    }
}
