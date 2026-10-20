package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.AskAnswer
import com.ragul84.hypurr.model.GuideDoc
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.MarkdownText
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.Segmented
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.theme.Hypurr

enum class GuideTab(val label: String, val kind: String) { Guide("Project guide", "guide"), Handover("Handover", "handover"), Ask("Ask", "ask") }

data class GuideUiState(
    val folder: String? = null,
    val tab: GuideTab = GuideTab.Guide,
    val docs: Map<String, GuideDoc> = emptyMap(),
    /** The user's edits per kind; shared and saved instead of the original. */
    val edits: Map<String, String> = emptyMap(),
    val editing: Boolean = false,
    /** "guide", "handover" or "ask" while the computer works on it. */
    val loading: String? = null,
    val error: String? = null,
    val question: String = "",
    val answer: AskAnswer? = null,
    val asked: String? = null,
    val confirmSave: Boolean = false,
    val copied: Boolean = false,
) {
    fun text(kind: String) = edits[kind] ?: docs[kind]?.markdown.orEmpty()
}

/**
 * Codebase onboarding: a plain-words Project guide or a Handover / KT doc for one folder,
 * and "Ask about this project". The computer reads the folder and its git history; nothing
 * in the folder changes. Every file path is checked against the folder. Docs are editable
 * and leave the phone only through Share / Copy, or as docs/HYPURR_GUIDE.md through a
 * normal safe-branch task after the user confirms.
 */
@Composable
fun ProjectGuideScreen(
    state: GuideUiState,
    onBack: () -> Unit,
    onPickFolder: () -> Unit = {},
    onTab: (GuideTab) -> Unit = {},
    onGenerate: (String) -> Unit = {},
    onEdit: (String, String) -> Unit = { _, _ -> },
    onToggleEdit: () -> Unit = {},
    onShareMarkdown: (String) -> Unit = {},
    onSharePdf: (String) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onAskSave: () -> Unit = {},
    onConfirmSave: () -> Unit = {},
    onCancelSave: () -> Unit = {},
    onQuestion: (String) -> Unit = {},
    onAsk: () -> Unit = {},
) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Learn this project", style = MaterialTheme.typography.headlineMedium, color = c.text)
                    Text("Read-only: nothing in the folder changes", color = c.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.folder != null) FolderChip(state.folder, onClick = onPickFolder)
                else SoftButton("Pick a project folder", icon = signalIcon(SignalIcons.Folder), onClick = onPickFolder)
            }
            Segmented(GuideTab.entries.map { it.label }, state.tab.ordinal, Modifier.padding(horizontal = 16.dp)) { onTab(GuideTab.entries[it]) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.error?.let { Text(it, color = c.needsText, style = MaterialTheme.typography.bodySmall) }
                if (state.tab == GuideTab.Ask) AskPane(state, onQuestion, onAsk) else DocPane(state, state.tab.kind, onGenerate, onEdit)
            }
            if (state.tab != GuideTab.Ask && state.docs[state.tab.kind] != null) {
                val kind = state.tab.kind
                Column(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SoftButton(if (state.editing) "Done" else "Edit", Modifier.weight(1f)) { onToggleEdit() }
                        SoftButton(if (state.copied) "Copied" else "Copy", Modifier.weight(1f)) { onCopy(kind) }
                        SoftButton(".md", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send)) { onShareMarkdown(kind) }
                        SoftButton("PDF", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send)) { onSharePdf(kind) }
                    }
                    if (kind == "guide") PrimaryButton("Save to project…", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Branch), onClick = onAskSave)
                }
            }
        }
        if (state.confirmSave) SaveSheet(onConfirmSave, onCancelSave)
    }
}

@Composable
private fun DocPane(state: GuideUiState, kind: String, onGenerate: (String) -> Unit, onEdit: (String, String) -> Unit) {
    val c = Hypurr.colors
    val doc = state.docs[kind]
    val busy = state.loading == kind
    if (doc == null) {
        SignalCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (kind == "guide") "What it does, how to run and test it, a map of the code, key files, where to start, gotchas and who knows what."
                    else "Recent work, open branches, pending tasks, risky areas, how to deploy and who to ask.",
                    color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(if (busy) "Reading the folder…" else if (kind == "guide") "Write the project guide" else "Write the handover doc",
                    Modifier.fillMaxWidth(), enabled = !busy && state.folder != null, icon = signalIcon(SignalIcons.Docs)) { onGenerate(kind) }
                Text(if (state.folder == null) "Pick a project folder first."
                    else "Hypurr reads the folder and its git history, then writes it for your role and experience. Nothing in the folder changes.",
                    color = c.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(if (doc.by == "agent") "Written by your agent · paths checked" else "From the folder and git history", Modifier.weight(1f))
        if (!busy) Text("Rewrite", color = c.text, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(50)).pressable("Rewrite") { onGenerate(kind) }.padding(horizontal = 8.dp, vertical = 4.dp))
        else Text("Rewriting…", color = c.tertiary, style = MaterialTheme.typography.labelLarge)
    }
    if (doc.dropped.isNotEmpty()) {
        Text("Removed ${doc.dropped.size} file path${if (doc.dropped.size == 1) "" else "s"} that aren't in this folder.",
            color = c.tertiary, style = MaterialTheme.typography.labelSmall)
    }
    SignalCard(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(16.dp)) {
            if (state.editing) {
                BasicTextField(state.text(kind), { onEdit(kind, it) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            } else {
                MarkdownText(state.text(kind))
            }
        }
    }
}

@Composable
private fun AskPane(state: GuideUiState, onQuestion: (String) -> Unit, onAsk: () -> Unit) {
    val c = Hypurr.colors
    val busy = state.loading == "ask"
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(14.dp)) {
        if (state.question.isEmpty()) Text("Where is login handled? How do I add a screen?", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
        BasicTextField(state.question, onQuestion, minLines = 2, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
    PrimaryButton(if (busy) "Looking through the code…" else "Ask", Modifier.fillMaxWidth(),
        enabled = !busy && state.question.isNotBlank() && state.folder != null, icon = signalIcon(SignalIcons.Explain), onClick = onAsk)
    val a = state.answer ?: return
    state.asked?.let { Text(it, color = c.secondary, style = MaterialTheme.typography.titleSmall) }
    SignalCard(Modifier.fillMaxWidth()) { Box(Modifier.padding(16.dp)) { MarkdownText(a.answer) } }
    if (a.sources.isNotEmpty()) {
        Eyebrow("Sources in this folder")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            a.sources.distinctBy { it.path to it.line }.take(6).forEach { s ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(signalIcon(SignalIcons.File), null, tint = c.secondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.path + (s.line?.let { ":$it" } ?: ""), color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.text.isNotBlank()) Text(s.text, color = c.tertiary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
        }
    }
}

@Composable
private fun SaveSheet(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = Hypurr.colors
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)
            .safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Save as docs/HYPURR_GUIDE.md?", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text("Hypurr starts a task on its own safe branch that writes only this file. Your main branch isn't touched; you review the change and decide whether to keep it, like any task.",
                color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Start the task", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Branch), onClick = onConfirm)
            SoftButton("Not now", Modifier.fillMaxWidth(), onClick = onCancel)
        }
    }
}
