package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.LearnCard
import com.ragul84.hypurr.model.Quiz
import com.ragul84.hypurr.model.TaskInfo
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr

/** "Teach me: async code – it waits for the server" lines the agent wrote in learning mode. */
fun teachLines(entries: List<Entry>): List<LearnCard> = entries
    .filter { it.kind == "agent" }
    .flatMap { e -> e.data.text.orEmpty().lines() }
    .map { it.trim().removePrefix("- ").removePrefix("* ").replace("`", "").replace("**", "") }
    .filter { it.startsWith("Teach me:", ignoreCase = true) }
    .map { it.substringAfter(':').trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .take(6)
    .map { line ->
        val name = line.split(" – ", " - ", ": ", " — ").first().trim().take(40)
        LearnCard(id = "agent:$name", concept = name.replaceFirstChar { it.uppercase() }, explain = line)
    }

/**
 * Learning mode in a task's chat: "Teach me this" cards while it runs (ideas from the user's
 * own changed code, plus ones the agent named), then a three-question check once it's done.
 * Everything here is optional: it never holds the task up, and approvals stay as they are.
 */
@Composable
fun LearnPanel(
    task: TaskInfo,
    cards: List<LearnCard>,
    quiz: Quiz?,
    onLoadQuiz: () -> Unit = {},
    onHide: () -> Unit = {},
    initialOpen: String? = null,
    initialPicks: Map<Int, Int> = emptyMap(),
) {
    val c = Hypurr.colors
    var open by remember { mutableStateOf(initialOpen) }
    var picks by remember { mutableStateOf(initialPicks) }
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon(SignalIcons.Explain), null, tint = c.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Eyebrow("Learning mode", Modifier.weight(1f))
                Text("Hide", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).pressable("Hide learning", onClick = onHide).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (cards.isNotEmpty()) {
                Text("Teach me this", color = c.text, style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.forEach { card ->
                        val on = open == card.id
                        Text(card.concept, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else c.surface2)
                                .pressable(card.concept, role = Role.Button) { open = if (on) null else card.id }
                                .padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                cards.firstOrNull { it.id == open }?.let { TeachCard(it) }
            } else if (task.isActive) {
                Text("Your agent explains each step in simple words as it works. Ideas worth learning show up here.",
                    color = c.secondary, style = MaterialTheme.typography.bodySmall)
            }
            if (!task.isActive) {
                if (quiz == null) {
                    SoftButton("Check yourself: 3 questions", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Tests), onClick = onLoadQuiz)
                } else {
                    val score = quiz.questions.indices.count { picks[it] == quiz.questions[it].answer }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Check yourself", color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (picks.size == quiz.questions.size) Text("$score of ${quiz.questions.size}", color = c.text,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    quiz.questions.forEachIndexed { i, q ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${i + 1}. ${q.q}", color = c.text, style = MaterialTheme.typography.bodyMedium)
                            val picked = picks[i]
                            q.options.forEachIndexed { j, o ->
                                val right = j == q.answer
                                val shown = picked != null
                                val mine = picked == j
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(if (shown && right) c.surface2 else c.surface)
                                    .border(1.dp, if (mine) c.text else c.border, RoundedCornerShape(14.dp))
                                    .pressable(o, role = Role.RadioButton, enabled = !shown) { picks = picks + (i to j) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(o, color = if (shown && !right && !mine) c.tertiary else c.text, style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (shown && right) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                    if (shown && (right || mine)) Icon(signalIcon(if (right) SignalIcons.Check else SignalIcons.Close), null,
                                        tint = if (right) c.text else c.tertiary, modifier = Modifier.size(16.dp))
                                }
                            }
                            if (picked != null) Text(q.why, color = c.secondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeachCard(card: LearnCard) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(card.concept, color = c.text, style = MaterialTheme.typography.titleSmall)
        Text(card.explain.replace("`", ""), color = c.secondary, style = MaterialTheme.typography.bodySmall)
        if (card.example.isNotBlank()) {
            Text("In your code", color = c.tertiary, style = MaterialTheme.typography.labelSmall)
            Text(card.example, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(10.dp).horizontalScroll(rememberScrollState()))
            Text("${card.file}:${card.line}", color = c.tertiary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
