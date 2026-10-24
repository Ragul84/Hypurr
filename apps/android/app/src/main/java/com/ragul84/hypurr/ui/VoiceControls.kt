package com.ragul84.hypurr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion
import com.ragul84.hypurr.voice.VoiceLanguage

/** The mic: an ink circle while listening, a soft one otherwise (red stays for "needs you"). */
@Composable
fun MicButton(listening: Boolean, language: VoiceLanguage, modifier: Modifier = Modifier, size: Dp = 44.dp, onClick: () -> Unit) {
    val c = Hypurr.colors
    val fill by animateColorAsState(if (listening) c.accent else c.surface2, Motion.effects(), label = "mic")
    Box(modifier.size(size).clip(CircleShape).background(fill)
        .pressable(if (listening) "Stop listening" else "Speak in ${language.label}", onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(signalIcon(SignalIcons.Mic), null, tint = if (listening) c.onAccent else c.text, modifier = Modifier.size(size * 0.5f))
    }
}

/** A one-line hint under a mic: what it's listening in, live words, or a friendly error. */
@Composable
fun VoiceHint(listening: Boolean, partial: String, error: String?, language: VoiceLanguage, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    val text = when {
        error != null -> error
        listening && partial.isNotBlank() -> "“$partial”"
        listening -> "Listening in ${language.label}…"
        else -> null
    } ?: return
    Text(text, color = if (error != null) c.secondary else c.text, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

/** Language chips: English (India) and seven Indian languages, each in its own script. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceLanguagePicker(selected: VoiceLanguage, modifier: Modifier = Modifier, onPick: (VoiceLanguage) -> Unit) {
    val c = Hypurr.colors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceLanguage.entries.forEach { l ->
            val on = l == selected
            Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else c.surface)
                .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(50))
                .pressable(l.label, role = Role.RadioButton) { onPick(l) }
                .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.native, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge)
                if (!l.isEnglish) {
                    Spacer(Modifier.width(6.dp))
                    Text(l.label, color = if (on) c.onAccent.copy(alpha = 0.75f) else c.tertiary, style = MaterialTheme.typography.labelSmall)
                } else {
                    Spacer(Modifier.width(6.dp))
                    Text("India", color = if (on) c.onAccent.copy(alpha = 0.75f) else c.tertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

