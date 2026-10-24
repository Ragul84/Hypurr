package com.ragul84.hypurr.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.FileChange
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion
import com.ragul84.hypurr.ui.theme.SignalType

/** A diff line's kind, for colouring. */
enum class DiffLineKind { Added, Removed, Hunk, Context }

fun diffLineKind(line: String): DiffLineKind = when {
    line.startsWith("@@") -> DiffLineKind.Hunk
    line.startsWith("+") -> DiffLineKind.Added
    line.startsWith("-") -> DiffLineKind.Removed
    else -> DiffLineKind.Context
}

/** "3 files changed · +44 −2" for the header. */
fun whatChangedLine(files: List<FileChange>): String {
    val a = files.sumOf { it.added }
    val r = files.sumOf { it.removed }
    val n = files.size
    return "$n file${if (n == 1) "" else "s"} changed · +$a −$r"
}

/**
 * What changed, file by file: path and +/− lines; tap a file to see its diff.
 * Added lines are green, removed lines grey and struck through (red stays for "needs you").
 */
@Composable
fun WhatChangedFiles(files: List<FileChange>, modifier: Modifier = Modifier, initiallyOpen: String? = null, max: Int = 12) {
    val c = Hypurr.colors
    var open by remember(files) { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(vertical = 4.dp)) {
        Text(whatChangedLine(files), color = c.secondary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        files.take(max).forEach { f ->
            val isOpen = open == f.path
            val canOpen = f.patch.isNotBlank()
            Row(Modifier.fillMaxWidth()
                .then(if (canOpen) Modifier.pressable(if (isOpen) "Hide diff for ${f.path}" else "Show diff for ${f.path}") {
                    open = if (isOpen) null else f.path
                } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.path, color = c.text, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("+${f.added}", color = c.success, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text("−${f.removed}", color = c.secondary, style = MaterialTheme.typography.labelMedium)
                if (canOpen) {
                    Spacer(Modifier.width(6.dp))
                    Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary,
                        modifier = Modifier.size(16.dp).rotate(if (isOpen) 90f else 0f))
                }
            }
            AnimatedVisibility(isOpen, enter = expandVertically(Motion.spatialDefault()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                DiffText(f.patch, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
            }
        }
        if (files.size > max) Text("and ${files.size - max} more", color = c.tertiary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun DiffText(patch: String, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
        .horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        patch.trimEnd('\n').lines().forEach { line ->
            val kind = diffLineKind(line)
            val (ink, fill) = when (kind) {
                DiffLineKind.Added -> c.text to c.success.copy(alpha = 0.12f)
                DiffLineKind.Removed -> c.secondary to c.surface2
                DiffLineKind.Hunk -> c.tertiary to Color.Transparent
                DiffLineKind.Context -> c.secondary to Color.Transparent
            }
            Text(line.ifEmpty { " " }, color = ink, maxLines = 1, softWrap = false,
                style = SignalType.mono.copy(textDecoration = if (kind == DiffLineKind.Removed)
                    androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                modifier = Modifier.background(fill).padding(horizontal = 10.dp, vertical = 1.dp))
        }
    }
}
