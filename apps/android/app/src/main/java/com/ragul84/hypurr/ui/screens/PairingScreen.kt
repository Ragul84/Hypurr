package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.signalIcon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.motion.PairingSuccess
import com.ragul84.hypurr.ui.theme.Hypurr

data class PairingUiState(
    val link: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val computerName: String? = null,
    val success: Boolean = false,
)

@Composable
fun FlowBackdrop(modifier: Modifier = Modifier) {
    // Flat Signal ground.
    Box(modifier.fillMaxSize().background(Hypurr.colors.bg))
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    onLinkChange: (String) -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onPair: () -> Unit,
    onInstallAgent: () -> Unit = {},
) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            PairingSuccess(success = state.success) {
                // Dot cat on a white tile; pairing success fills its eyes (a happy squint).
                Box(Modifier.size(104.dp).clip(RoundedCornerShape(30.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(30.dp)),
                    contentAlignment = Alignment.Center) {
                    DotCat(c.text, 62.dp, off = c.dotOff, blink = if (state.success) 1f else 0f)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Meet Hypurr", style = MaterialTheme.typography.headlineLarge, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text(
                com.ragul84.hypurr.ui.Pitch.Subhead,
                color = c.secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            if (state.success && state.computerName != null) {
                Spacer(Modifier.height(16.dp))
                Eyebrow("Connected to ${state.computerName}", dot = c.text, color = c.text)
            }
            Spacer(Modifier.height(28.dp))
            AnimatedContent(state.busy, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pairing") { busy ->
                if (busy) {
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Pairing with ${state.computerName ?: "your computer"}…", style = MaterialTheme.typography.titleMedium, color = c.text)
                            Spacer(Modifier.height(6.dp))
                            Text("Proving the one-time code over an end-to-end encrypted channel.", color = c.secondary,
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!state.success) {
                            SignalCard(Modifier.fillMaxWidth()) { com.ragul84.hypurr.ui.PitchPillars(Modifier.padding(16.dp)) }
                            Spacer(Modifier.height(14.dp))
                        }
                        SignalCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Step(1, "Start Hypurr on your PC (hypurr-host or the start script)")
                                Step(2, "Scan the QR it shows, or paste the pairing link")
                                Step(3, "Run your first task from this phone")
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton("Pair with your PC", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Qr), onClick = onScan)
                        Spacer(Modifier.height(12.dp))
                        SoftButton("How to set up your PC", Modifier.fillMaxWidth(), tint = c.text, onClick = onInstallAgent)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(50))
                            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(signalIcon(SignalIcons.Link), null, tint = c.tertiary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (state.link.isEmpty()) Text("hypurr://pair?…", color = c.secondary, style = MaterialTheme.typography.bodyLarge)
                                BasicTextField(state.link, onLinkChange, singleLine = true, cursorBrush = SolidColor(c.accent),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), modifier = Modifier.fillMaxWidth())
                            }
                            IconBubble(signalIcon(SignalIcons.Paste), "Paste pairing link", fill = c.surface2, size = 40.dp, onClick = onPaste)
                        }
                        AnimatedVisibility(state.link.isNotBlank(), enter = fadeIn() + slideInVertically()) {
                            PrimaryButton("Pair", Modifier.padding(top = 14.dp).fillMaxWidth(), onClick = onPair)
                        }
                    }
                }
            }
            AnimatedVisibility(state.error != null) {
                Text(state.error.orEmpty(), color = c.needsText, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Pairing is local and end-to-end encrypted. Messages go directly over your network or Tailscale, or through the cloud relay, which only sees ciphertext.",
                color = c.secondary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val c = Hypurr.colors
    Row(verticalAlignment = Alignment.Top) {
        com.ragul84.hypurr.ui.DotFigure("$n", com.ragul84.hypurr.ui.theme.SignalType.figureSmall, c.text, Modifier.width(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 1.dp))
    }
}
