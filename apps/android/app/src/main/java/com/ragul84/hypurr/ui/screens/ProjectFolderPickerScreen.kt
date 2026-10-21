package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr

/**
 * Pick the project folder the agent may work in.
 * Saved projects first; otherwise browse folders on the computer.
 */
@Composable
fun ProjectFolderPickerScreen(
    projects: List<Project>,
    browsing: DirListing?,
    selectedPath: String?,
    title: String = "Project folder",
    subtitle: String = "Pick the folder this work is about.",
    onBack: () -> Unit,
    onBrowse: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    /** Shown when picking can wait (right after pairing). */
    onLater: (() -> Unit)? = null,
    confirmLabel: String? = null,
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
        }
        Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        ScopeRules()
        Spacer(Modifier.height(20.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (projects.isNotEmpty()) {
                item {
                    com.ragul84.hypurr.ui.Eyebrow("Saved projects", Modifier.padding(start = 4.dp))
                }
                items(projects, key = { it.path }) { p ->
                    val on = p.path == selectedPath
                    SignalCard(Modifier.fillMaxWidth().pressable(p.name) { onSelect(p.path) }, emphasis = on) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(signalIcon(SignalIcons.Folder), null, tint = c.text, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = c.text, style = MaterialTheme.typography.titleMedium)
                                Text(p.path, color = c.tertiary, style = com.ragul84.hypurr.ui.theme.SignalType.mono, maxLines = 1)
                            }
                            if (on) Icon(signalIcon(SignalIcons.Check), null, tint = c.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item {
                com.ragul84.hypurr.ui.Eyebrow("Browse computer", Modifier.padding(start = 4.dp, top = 8.dp))
            }
            item {
                SoftButton(
                    if (browsing == null) "Open folders…" else "Refresh",
                    Modifier.fillMaxWidth(),
                    icon = signalIcon(SignalIcons.Folder),
                ) { onBrowse(browsing?.path) }
            }
            browsing?.let { listing ->
                item {
                    SignalCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                listing.parent?.let {
                                    IconBubble(signalIcon(SignalIcons.Back), "Up", size = 34.dp) { onBrowse(it) }
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(listing.path.ifEmpty { "This PC" }, color = c.text, style = com.ragul84.hypurr.ui.theme.SignalType.mono, maxLines = 2,
                                    modifier = Modifier.weight(1f))
                            }
                            if (listing.path.isNotEmpty()) SoftButton(
                                if (selectedPath == listing.path) "Selected" else "Use this folder",
                                Modifier.fillMaxWidth(),
                                icon = signalIcon(SignalIcons.Check),
                                tint = if (selectedPath == listing.path) c.accent else c.text,
                            ) { onSelect(listing.path) }
                            if (listing.dirs.isEmpty()) {
                                Text("No subfolders here.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                            } else {
                                listing.dirs.forEach { d ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .pressable(d.name) { onBrowse(d.path) }
                                            .padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(signalIcon(SignalIcons.Folder), null, tint = c.text, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(d.name, color = c.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        PrimaryButton(
            if (selectedPath != null) confirmLabel ?: "Use ${folderName(selectedPath)}" else "Pick a folder first",
            Modifier.fillMaxWidth().padding(bottom = if (onLater != null) 10.dp else 16.dp),
            enabled = selectedPath != null,
            onClick = onConfirm,
        )
        if (onLater != null) {
            SoftButton("Later", Modifier.fillMaxWidth().padding(bottom = 16.dp), tint = c.text, onClick = onLater)
        }
    }
}

fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** What the folder means for the agent: three short lines in one card. Red marks the line that asks you. */
@Composable
private fun ScopeRules() {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(SignalIcons.Review, "Reads inside it freely", false),
                Triple(SignalIcons.Branch, "Edits go to a safe branch, one-tap undo", false),
                Triple(SignalIcons.NeedsYou, "Outside it, deletes, pushes: asks you first", true),
            ).forEach { (icon, text, needs) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(signalIcon(icon), null, tint = if (needs) c.needsText else c.text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(text, color = c.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The task's project folder as a mono chip (chat header, new task, settings), like the mockup's ~/work/shop-web. */
@Composable
fun FolderChip(path: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    com.ragul84.hypurr.ui.SignalChip(signalIcon(SignalIcons.Folder), shortPath(path), modifier, onClick = onClick)
}

/** /Users/arjun/work/shop-web → ~/work/shop-web; long paths keep their last two parts. */
fun shortPath(path: String): String {
    val home = Regex("^/(Users|home)/[^/]+").find(path)?.value
    val p = if (home != null) "~" + path.removePrefix(home) else path
    val parts = p.trimEnd('/').split('/')
    return if (parts.size > 4) "…/" + parts.takeLast(2).joinToString("/") else p.trimEnd('/')
}
