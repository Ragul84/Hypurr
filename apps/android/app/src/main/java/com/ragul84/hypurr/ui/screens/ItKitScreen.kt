package com.ragul84.hypurr.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.ItKit
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.MarkdownText
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr

/**
 * Share with IT: the approval kit (data flow, security FAQ, allowlist, signed installer,
 * local-only mode, audit export) with this computer's live values on top. Share hands the
 * text to the share sheet; nothing is sent by Hypurr.
 */
@Composable
fun ItKitScreen(
    kit: ItKit?,
    onBack: () -> Unit,
    onShare: (ItKit) -> Unit = {},
    onCopy: (ItKit) -> Unit = {},
    copied: Boolean = false,
    initiallyOpen: String? = null,
) {
    val c = Hypurr.colors
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Share with IT", style = MaterialTheme.typography.headlineMedium, color = c.text)
                        Text("Everything your IT team asks before approving Hypurr", color = c.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (kit == null) {
                    Text("Loading from your computer…", color = c.secondary, modifier = Modifier.padding(16.dp))
                    return@Column
                }
                SignalCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("This computer")
                        kit.facts.forEach { f ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(signalIcon(SignalIcons.Check), null, tint = c.tertiary, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(f, color = c.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SignalCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        kit.pages.forEachIndexed { i, page ->
                            if (i > 0) HairlineDivider()
                            val isOpen = open == page.id
                            Row(Modifier.fillMaxWidth().pressable(page.title, role = Role.Button) { open = if (isOpen) null else page.id }
                                .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(signalIcon(SignalIcons.Docs), null, tint = c.text, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(page.title, color = c.text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(18.dp))
                            }
                            if (isOpen) {
                                MarkdownText(page.markdown.substringAfter('\n').trim(), Modifier.padding(bottom = 14.dp))
                            }
                        }
                    }
                }
                Text("Share opens your phone's share sheet. You choose the app and who gets it.", color = c.tertiary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            }
            if (kit != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Share", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send)) { onShare(kit) }
                    SoftButton(if (copied) "Copied" else "Copy", Modifier.weight(1f).height(48.dp),
                        icon = signalIcon(if (copied) SignalIcons.Check else SignalIcons.Paste)) { onCopy(kit) }
                }
            }
        }
    }
}
