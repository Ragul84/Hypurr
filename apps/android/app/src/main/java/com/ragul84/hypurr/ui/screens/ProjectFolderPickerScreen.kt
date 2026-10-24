package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
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
 * Full-screen browser of the computer's folders. Top level: recent projects, Home and the drives.
 * One level loads per tap (fast over the relay). Listing is read-only on the computer.
 */
@Composable
fun ProjectFolderPickerScreen(
    projects: List<Project>,
    browsing: DirListing?,
    selectedPath: String?,
    title: String = "Choose folder",
    subtitle: String = "",
    onBack: () -> Unit,
    onBrowse: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    /** Shown when picking can wait (right after pairing). */
    onLater: (() -> Unit)? = null,
    confirmLabel: String? = null,
) {
    val c = Hypurr.colors
    val atTop = browsing == null || browsing.path.isEmpty()
    // Picking a whole drive asks once more.
    var rootWarned by remember(selectedPath) { mutableStateOf(false) }
    val pickedRoot = selectedPath != null && isDriveRoot(selectedPath)
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(signalIcon(SignalIcons.Back), "Back", onClick = {
                if (!atTop) onBrowse(browsing?.parent ?: "") else onBack()
            })
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text)
        }
        if (subtitle.isNotEmpty() && atTop) {
            Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        // Breadcrumb: This PC › C: › work › shop
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Crumb("This PC", atTop) { onBrowse("") }
            breadcrumbs(browsing?.path.orEmpty()).forEach { (name, path) ->
                Text(" › ", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                Crumb(name, path == browsing?.path) { onBrowse(path) }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (atTop && projects.isNotEmpty()) {
                item { com.ragul84.hypurr.ui.Eyebrow("Recent projects", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) }
                items(projects, key = { "p:" + it.path }) { p ->
                    FolderRow(p.name, p.path, project = true, selected = p.path == selectedPath, onOpen = { onBrowse(p.path) }) { onSelect(p.path) }
                }
                item { com.ragul84.hypurr.ui.Eyebrow("This PC", Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)) }
            }
            when {
                browsing == null -> item { Text("Loading…", color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                browsing.dirs.isEmpty() -> item { Text("No folders here.", color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                else -> items(browsing.dirs, key = { "d:" + it.path }) { d ->
                    FolderRow(d.name, null, project = d.isProject, selected = d.path == selectedPath, onOpen = { onBrowse(d.path) },
                        onPick = if (atTop) null else ({ onSelect(d.path) }))
                }
            }
        }
        if (pickedRoot && rootWarned) {
            Text("The agent can see and change everything on this drive.", color = c.needsText,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (!atTop && browsing != null && selectedPath != browsing.path) {
            SoftButton("Use this folder", Modifier.fillMaxWidth().padding(bottom = 8.dp), icon = signalIcon(SignalIcons.Check), tint = c.text) {
                onSelect(browsing.path)
            }
        }
        PrimaryButton(
            when {
                selectedPath == null -> "Pick a folder first"
                pickedRoot && !rootWarned -> "Use whole drive ${folderName(selectedPath)}"
                pickedRoot -> "Yes, use the whole drive"
                else -> confirmLabel ?: "Use ${folderName(selectedPath)}"
            },
            Modifier.fillMaxWidth().padding(bottom = if (onLater != null) 10.dp else 16.dp),
            enabled = selectedPath != null,
            onClick = { if (pickedRoot && !rootWarned) rootWarned = true else onConfirm() },
        )
        if (onLater != null) {
            SoftButton("Later", Modifier.fillMaxWidth().padding(bottom = 16.dp), tint = c.text, onClick = onLater)
        }
    }
}

@Composable
private fun Crumb(name: String, current: Boolean, onClick: () -> Unit) {
    val c = Hypurr.colors
    Text(name, color = if (current) c.text else c.accent, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(name, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp))
}

/** Tap the row to go in; tap the circle to pick it. */
@Composable
private fun FolderRow(name: String, path: String?, project: Boolean, selected: Boolean, onOpen: () -> Unit, onPick: (() -> Unit)?) {
    val c = Hypurr.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(name, onClick = onOpen).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(signalIcon(SignalIcons.Folder), null, tint = c.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (path != null) Text(path, color = c.tertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (project) Text("Project", color = c.success, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
        if (onPick != null) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).pressable(if (selected) "Selected" else "Pick $name", onClick = onPick),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(signalIcon(SignalIcons.Check), null, tint = c.accent, modifier = Modifier.size(18.dp))
                else Box(Modifier.size(16.dp).border(1.5.dp, c.tertiary, RoundedCornerShape(50)))
            }
        } else {
            Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** `C:\`, `D:`, `/`: the whole drive. */
fun isDriveRoot(path: String): Boolean = path == "/" || Regex("""^[A-Za-z]:[\\/]?$""").matches(path)

/** `C:\work\shop` → (C:, C:\), (work, C:\work), (shop, C:\work\shop); `/home/a` → (/, /), (home, /home), (a, /home/a). */
fun breadcrumbs(path: String): List<Pair<String, String>> {
    if (path.isEmpty()) return emptyList()
    val win = Regex("^[A-Za-z]:").containsMatchIn(path)
    val sep = if (win) "\\" else "/"
    val parts = path.split('\\', '/').filter { it.isNotEmpty() }
    val out = mutableListOf<Pair<String, String>>()
    var acc = ""
    if (!win) {
        out += "/" to "/"
        acc = "/"
    }
    parts.forEachIndexed { i, part ->
        acc = when {
            win && i == 0 -> part + sep
            acc.endsWith(sep) -> acc + part
            else -> acc + sep + part
        }
        out += part to acc
    }
    return out
}

fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

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
