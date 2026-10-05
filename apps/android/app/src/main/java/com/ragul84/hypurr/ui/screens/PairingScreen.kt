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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.ui.CatFace
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.motion.PairingSuccessFlip
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
    // Flat sunflower — no neon glow blobs.
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
            PairingSuccessFlip(success = state.success || state.computerName != null && state.busy.not() && state.link.isEmpty() && false) {
                // Chunky ink cat hero
                Box {
                    Box(Modifier.size(96.dp).padding(top = 5.dp).clip(RoundedCornerShape(28.dp))
                        .background(c.press.copy(alpha = if (c.dark) 0.5f else 0.22f)))
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(28.dp))
                            .background(if (c.dark) c.surface else Color(0xFF15130F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CatFace(if (c.dark) c.text else Color(0xFFFFF8E8), 58.dp)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("Meet Hypurr", style = MaterialTheme.typography.headlineMedium, color = c.text, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "AI coding agents, safe enough for anyone. Install the agent on your computer, then pair this phone.",
                color = c.secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            if (state.success && state.computerName != null) {
                Spacer(Modifier.height(16.dp))
                Text("Connected to ${state.computerName}", color = c.accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            AnimatedContent(state.busy, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "pairing") { busy ->
                if (busy) {
                    CreamPlate(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Pairing with ${state.computerName ?: "your computer"}…", style = MaterialTheme.typography.titleMedium, color = c.text)
                            Spacer(Modifier.height(6.dp))
                            Text("Proving the one-time code over an end-to-end encrypted channel.", color = c.secondary,
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CreamPlate(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Step(1, "Install Hypurr Agent on your computer")
                                Step(2, "Scan the QR it shows, or paste the pairing link")
                                Step(3, "Run your first task from this phone")
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        FlowButton("Pair with QR", Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner, onClick = onScan)
                        Spacer(Modifier.height(12.dp))
                        SoftButton("Install Hypurr Agent", Modifier.fillMaxWidth(), tint = c.text, onClick = onInstallAgent)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.surface)
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
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Text("$n", color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 3.dp))
    }
}
