package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr

/**
 * Why Hypurr, in three lines. Remote control of a coding agent is free elsewhere; Hypurr leads with
 * what the others don't do: safe for non-experts, proof it works, and free AI built in.
 */
data class Pillar(val icon: Int, val title: String, val body: String)

object Pitch {
    const val Headline = "Coding agents, safe for anyone"
    const val Subhead = "Ask in plain words. Hypurr keeps it safe, proves it works, and comes with free AI."
    val Pillars: List<Pillar> get() = listOf(
        Pillar(SignalIcons.Shield, "Safe for non-experts",
            "Every approval in plain words. It stays in one folder, never touches production, and one tap rolls it back."),
        Pillar(SignalIcons.Trace, "Proof it works",
            "Show me it works runs it and brings back screenshots. Trace a click and Page map show what calls what."),
        Pillar(SignalIcons.Credits, "Free AI built in",
            "No AI account? Hypurr Free gives you 10 messages to try. For trying out, not for company code."),
    )
}

/** The three pillars as rows of icon + title + one line. [compact] drops the body text. */
@Composable
fun PitchPillars(modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Hypurr.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        Pitch.Pillars.forEach { p ->
            Row(verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                    Icon(signalIcon(p.icon), null, tint = c.text, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, color = c.text, style = MaterialTheme.typography.titleSmall)
                    if (!compact) Text(p.body, color = c.secondary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
