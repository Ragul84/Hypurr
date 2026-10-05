package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SunfieldIcons
import com.ragul84.hypurr.ui.sunfieldVector

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
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.LinkPill
import com.ragul84.hypurr.ui.Pill
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
    onOpenTemplate: (TaskTemplate) -> Unit = {},
    onOpenBot: (Bot) -> Unit = {},
    onTab: (HomeTab) -> Unit = {},
) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
                Text("Tasks", style = MaterialTheme.typography.headlineLarge, color = c.text, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(computerName, style = MaterialTheme.typography.labelMedium, color = c.tertiary, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    LinkPill(link)
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    FlowButton("New task", Modifier.fillMaxWidth(), icon = sunfieldVector(SunfieldIcons.Plus), onClick = onNewTask)
                }
                if (templates.isNotEmpty()) {
                    item {
                        Text("Templates", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                    }
                    items(templates.take(6), key = { it.id }) { tpl ->
                        CreamPlate(Modifier.fillMaxWidth().pressable(tpl.title) { onOpenTemplate(tpl) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent),
                                    contentAlignment = Alignment.Center) {
                                    Icon(templateIcon(tpl.icon), null, tint = c.onAccent, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(tpl.title, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (tpl.summary.isNotBlank()) {
                                        Text(tpl.summary, color = c.secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }
                val taskBots = recent.filter { it.task != null }
                if (taskBots.isNotEmpty()) {
                    item {
                        Text("Recent", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                    }
                    items(taskBots, key = { it.id }) { bot ->
                        CreamPlate(Modifier.fillMaxWidth().pressable(bot.name) { onOpenBot(bot) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                BotAvatar(bot.copy(status = "idle"), 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(bot.name, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(bot.activity.ifEmpty { bot.lastMessage ?: "Task" }, color = c.secondary,
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                }
                                when {
                                    bot.needsInput -> Pill("Needs you", c.accent)
                                    bot.task?.isActive == false -> Pill("Done", c.tertiary)
                                    else -> Text(relativeTime(bot.lastAt, now), color = c.tertiary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        HypurrTabBar(
            selected = HomeTab.Tasks,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            onSelect = onTab,
        )
    }
}
