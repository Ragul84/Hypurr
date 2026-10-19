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
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.SunfieldIcons
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.sunfieldVector
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
            IconBubble(sunfieldVector(SunfieldIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text, fontWeight = FontWeight.ExtraBold)
        }
        Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        ScopeRules()
        Spacer(Modifier.height(20.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (projects.isNotEmpty()) {
                item {
                    Text("Saved projects", color = c.tertiary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                items(projects, key = { it.path }) { p ->
                    val on = p.path == selectedPath
                    CreamPlate(Modifier.fillMaxWidth().pressable(p.name) { onSelect(p.path) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(sunfieldVector(SunfieldIcons.Folder), null, tint = if (on) c.accent else c.secondary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = c.text, fontWeight = FontWeight.Bold)
                                Text(p.path, color = c.tertiary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                            if (on) Icon(sunfieldVector(SunfieldIcons.Check), null, tint = c.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item {
                Text("Browse computer", color = c.tertiary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp))
            }
            item {
                SoftButton(
                    if (browsing == null) "Open folders…" else "Refresh",
                    Modifier.fillMaxWidth(),
                    icon = sunfieldVector(SunfieldIcons.Folder),
                ) { onBrowse(browsing?.path) }
            }
            browsing?.let { listing ->
                item {
                    CreamPlate(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                listing.parent?.let {
                                    IconBubble(sunfieldVector(SunfieldIcons.Back), "Up", size = 34.dp) { onBrowse(it) }
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(listing.path, color = c.text, fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
                            }
                            SoftButton(
                                if (selectedPath == listing.path) "Selected" else "Use this folder",
                                Modifier.fillMaxWidth(),
                                icon = sunfieldVector(SunfieldIcons.Check),
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
                                        Icon(sunfieldVector(SunfieldIcons.Folder), null, tint = c.accent, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(d.name, color = c.text, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
        FlowButton(
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

/** What the folder means for the agent, in three short lines. */
@Composable
private fun ScopeRules() {
    val c = Hypurr.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            SunfieldIcons.Explain to "Reads inside it freely",
            SunfieldIcons.Shield to "Edits go to a safe branch, one-tap undo",
            SunfieldIcons.NeedsYou to "Outside it, deletes, pushes: asks you first",
        ).forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(sunfieldVector(icon), null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, color = c.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The task's project folder as a small tappable chip (chat header, new task, settings). */
@Composable
fun FolderChip(path: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Hypurr.colors
    Row(
        modifier.clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.pressable("Project folder ${folderName(path)}", onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sunfieldVector(SunfieldIcons.Folder), null, tint = c.accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(folderName(path), color = c.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text("Change", color = c.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}
