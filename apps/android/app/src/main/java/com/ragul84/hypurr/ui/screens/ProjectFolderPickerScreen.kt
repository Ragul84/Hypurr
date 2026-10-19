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
    subtitle: String = "The agent reads freely here. Edits stay on a safe branch. Outside this folder, deletes, pushes, and risky commands need your OK.",
    onBack: () -> Unit,
    onBrowse: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(sunfieldVector(SunfieldIcons.Back), "Back", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text, fontWeight = FontWeight.ExtraBold)
        }
        Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
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
            if (selectedPath != null) "Continue with ${selectedPath.substringAfterLast('/')}" else "Pick a folder first",
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            enabled = selectedPath != null,
            onClick = onConfirm,
        )
    }
}
