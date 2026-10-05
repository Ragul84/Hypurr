package com.ragul84.hypurr.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.FlowOrb
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.theme.ColorFlow
import com.ragul84.hypurr.ui.motion.NeonStatus
import com.ragul84.hypurr.ui.motion.CatAssemble
import com.ragul84.hypurr.ui.theme.Hypurr

data class PairingUiState(val link: String = "", val busy: Boolean = false, val error: String? = null, val computerName: String? = null)

/** The colour-flow glow behind onboarding and empty states. */
@Composable
fun FlowBackdrop(modifier: Modifier = Modifier) {
    val alpha = if (Hypurr.colors.dark) 0.22f else 0.14f
    fun glow(color: androidx.compose.ui.graphics.Color, a: Float) = Brush.radialGradient(listOf(color.copy(alpha = a), color.copy(alpha = 0f)))
    Box(modifier.fillMaxSize()) {
        Box(Modifier.size(420.dp).offset((-150).dp, (-130).dp).background(glow(ColorFlow.cream, alpha * 0.9f), CircleShape))
        Box(Modifier.size(380.dp).align(Alignment.TopEnd).offset(150.dp, 60.dp).background(glow(ColorFlow.signal, alpha * 0.55f), CircleShape))
        Box(Modifier.size(420.dp).align(Alignment.BottomCenter).offset(60.dp, 170.dp).background(glow(ColorFlow.sunflower, alpha * 0.4f), CircleShape))
    }
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    onLinkChange: (String) -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onPair: () -> Unit,
) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        FlowBackdrop()
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            FlowOrb(104.dp, animate = state.busy)
            Spacer(Modifier.height(28.dp))
            Text("Hypurr", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold), color = c.accent)
            Spacer(Modifier.height(8.dp))
            Text("Your coding bots, on your phone.", style = MaterialTheme.typography.titleMedium, color = c.secondary,
                textAlign = TextAlign.Center)
            state.computerName?.let { name ->
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    NeonStatus(on = true)
                    Spacer(Modifier.width(8.dp))
                    Text("$name · Direct", color = c.accent, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(36.dp))
            AnimatedContent(state.busy, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pairing") { busy ->
                if (busy) {
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), c.glass).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Pairing with ${state.computerName ?: "your computer"}…", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Spacer(Modifier.height(6.dp))
                        Text("Proving the one-time code over an end-to-end encrypted channel.", color = c.secondary,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), c.glass).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Step(1, "On your computer, open Hypurr and choose Pair phone, or run", "hypurr-host pair")
                            Step(2, "Scan the QR code it shows, or paste its pairing link below.", null)
                        }
                        Spacer(Modifier.height(24.dp))
                        FlowButton("Scan pairing code", Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner, onClick = onScan)
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(50), c.surface, highlight = false)
                            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Link, null, tint = c.tertiary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (state.link.isEmpty()) Text("hypurr://pair?…", color = c.secondary, style = MaterialTheme.typography.bodyLarge)
                                BasicTextField(state.link, onLinkChange, singleLine = true, cursorBrush = SolidColor(c.accent),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), modifier = Modifier.fillMaxWidth())
                            }
                            IconBubble(Icons.Rounded.ContentPaste, "Paste pairing link", fill = c.bg, size = 40.dp, onClick = onPaste)
                        }
                        AnimatedVisibility(state.link.isNotBlank(), enter = fadeIn() + slideInVertically()) {
                            SoftButton("Pair", Modifier.padding(top = 14.dp).fillMaxWidth(), onClick = onPair)
                        }
                    }
                }
            }
            AnimatedVisibility(state.error != null) {
                Text(state.error.orEmpty(), color = c.danger, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Pairing is local and end-to-end encrypted. Messages go directly over your network or Tailscale, " +
                "or through the cloud relay, which only sees ciphertext.", color = c.tertiary,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: Int, text: String, code: String?) {
    val c = Hypurr.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Hypurr.colors.accent), contentAlignment = Alignment.Center) {
            Text("$n", color = Hypurr.colors.onAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, color = c.text, style = MaterialTheme.typography.bodyMedium)
            if (code != null) {
                Text(code, color = c.accent, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}
