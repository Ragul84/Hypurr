package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.MapPage
import com.ragul84.hypurr.model.PageMapData
import com.ragul84.hypurr.model.RestructureApplied
import com.ragul84.hypurr.model.RestructurePlan
import com.ragul84.hypurr.model.Trace
import com.ragul84.hypurr.model.TraceCall
import com.ragul84.hypurr.model.TraceClick
import com.ragul84.hypurr.model.TraceExport
import com.ragul84.hypurr.model.TraceReply
import com.ragul84.hypurr.model.TraceRoute
import com.ragul84.hypurr.ui.DotFigure
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.HairlineDivider
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.Segmented
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.SignalType

/** Map tab: the page list, one page, or the restructure plan. */
enum class MapStep { Pages, Page, Plan }

data class TraceMapUiState(
    val project: String? = null,
    val projectName: String? = null,
    /** 0 = Trace a click, 1 = Page map. */
    val tab: Int = 0,
    val url: String = "http://localhost:5173/",
    /** Buttons to click by their words, one per line (empty = he clicks on the laptop). */
    val steps: String = "",
    val approval: TraceReply? = null,
    val trace: Trace? = null,
    /** Expanded call, as "click.call". */
    val open: String? = null,
    val exported: TraceExport? = null,
    val map: PageMapData? = null,
    val plan: RestructurePlan? = null,
    val applied: RestructureApplied? = null,
    val mapStep: MapStep = MapStep.Pages,
    val page: String? = null,
    val confirmApply: Boolean = false,
    val copied: Boolean = false,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val error: String? = null,
) {
    val stepList: List<String> get() = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val recording: Boolean get() = trace?.status == "recording"
    val openPage: MapPage? get() = map?.pages?.firstOrNull { it.path == page }
}

@Composable
fun TraceMapScreen(
    state: TraceMapUiState,
    onBack: () -> Unit = {},
    onChange: (TraceMapUiState) -> Unit = {},
    onStart: () -> Unit = {},
    onAllow: () -> Unit = {},
    onStop: () -> Unit = {},
    onExport: () -> Unit = {},
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onScan: () -> Unit = {},
    onApply: () -> Unit = {},
    onPublish: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val c = Hypurr.colors
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(signalIcon(SignalIcons.Back), "Back", onClick = onBack)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp)) {
            val title = when {
                state.tab == 0 && state.trace?.status == "done" -> "Trace"
                state.tab == 0 -> "Trace a click"
                state.mapStep == MapStep.Plan -> "Restructure plan"
                state.mapStep == MapStep.Page -> state.page ?: "Page"
                else -> "Page map"
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Eyebrow(state.projectName ?: "Pick a project folder", color = c.tertiary)
            if (state.mapStep == MapStep.Pages || state.tab == 0) {
                Spacer(Modifier.height(14.dp))
                Segmented(listOf("Trace a click", "Page map"), state.tab, Modifier.fillMaxWidth()) {
                    onChange(state.copy(tab = it, error = null))
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.tab == 0) traceItems(state, onChange) else mapItems(state, onChange)
            if (state.busy) item { BusyCard(state.busyLabel) }
            state.error?.let { item { NeedsYouNote(it) } }
        }
        if (!state.busy) BottomBar(state, onChange, onStart, onAllow, onStop, onExport, onCopy, onShare, onScan, onApply, onPublish, onDiscard)
    }
}

// ---------- Trace ----------

private fun androidx.compose.foundation.lazy.LazyListScope.traceItems(state: TraceMapUiState, onChange: (TraceMapUiState) -> Unit) {
    val t = state.trace
    when {
        state.approval != null -> item { ApprovalCard(state.approval) }
        t == null || t.status == "failed" -> {
            item { TraceInput(state, onChange) }
            item { Notice("Localhost and staging only. Hypurr opens Chrome or Edge on your laptop and records there; nothing is uploaded.") }
            t?.error?.let { item { NeedsYouNote(it) } }
        }
        t.status == "recording" -> item { RecordingCard(state) }
        else -> {
            item { TraceSummary(t) }
            if (t.blockedHosts.isNotEmpty()) item { Notice("Blocked: ${t.blockedHosts.joinToString()} (not allowed for this project).") }
            t.error?.let { item { Notice(it) } }
            t.clicks.forEachIndexed { i, click ->
                item(key = "chain$i") { ChainCard(i, click, t.chain.getOrNull(i).orEmpty()) }
                click.calls.forEachIndexed { j, call ->
                    item(key = "call$i.$j") {
                        CallCard(call, open = state.open == "$i.$j") { onChange(state.copy(open = if (state.open == "$i.$j") null else "$i.$j")) }
                    }
                }
            }
            state.exported?.let { item { ExportCard(it) } }
        }
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, minHeight: Int = 0, mono: Boolean = false) {
    val c = Hypurr.colors
    val style = (if (mono) SignalType.mono.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize) else MaterialTheme.typography.bodyLarge).copy(color = c.text)
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.tertiary))
        BasicTextField(value, onValue, Modifier.fillMaxWidth(), textStyle = style, cursorBrush = SolidColor(c.text))
    }
}

@Composable
private fun TCard(title: String? = null, emphasis: Boolean = false, content: @Composable () -> Unit) {
    SignalCard(Modifier.fillMaxWidth(), emphasis = emphasis) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            if (title != null) {
                Eyebrow(title)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun TraceInput(state: TraceMapUiState, onChange: (TraceMapUiState) -> Unit) {
    TCard("Page to open") {
        Field(state.url, { onChange(state.copy(url = it.trim(), error = null)) }, "http://localhost:5173/orders", mono = true)
        Spacer(Modifier.height(14.dp))
        Eyebrow("Buttons to click (optional)")
        Spacer(Modifier.height(8.dp))
        Field(state.steps, { onChange(state.copy(steps = it)) }, "Leave empty to click yourself on the laptop.\nOr one per line, e.g. Place order", minHeight = 84)
    }
}

@Composable
private fun ApprovalCard(a: TraceReply) {
    val c = Hypurr.colors
    TCard(emphasis = true) {
        Eyebrow("Needs you", color = c.needsText, dot = c.needs)
        Spacer(Modifier.height(10.dp))
        Text(
            if (a.hostKind == "other") "Allow ${a.host} for this project?" else "Let Hypurr open ${a.host} in its own browser?",
            color = c.text, style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(a.why.orEmpty(), color = c.secondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("Asked once per project. Production sites are never allowed.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecordingCard(state: TraceMapUiState) {
    val c = Hypurr.colors
    TCard {
        Eyebrow("Recording", color = c.text, dot = c.text)
        Spacer(Modifier.height(10.dp))
        Text(
            if (state.stepList.isEmpty()) "A browser window is open on your laptop. Click the buttons you want to trace, then tap Stop."
            else "Clicking ${state.stepList.joinToString { "\u201c$it\u201d" }} in a hidden browser on your laptop.",
            color = c.text, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(state.trace?.url.orEmpty(), color = c.tertiary, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Hypurr.colors
    Column(modifier) {
        DotFigure(value, SignalType.counter, c.text)
        Spacer(Modifier.height(4.dp))
        Text(label, color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TraceSummary(t: Trace) {
    val calls = t.clicks.flatMap { it.calls }
    val slow = calls.mapNotNull { it.ms }.maxOrNull()
    val failed = calls.count { (it.status ?: 0) >= 400 || it.failed != null }
    TCard(t.url.removePrefix("http://").removePrefix("https://")) {
        Row(Modifier.fillMaxWidth()) {
            Figure("${t.clicks.size}", if (t.clicks.size == 1) "click" else "clicks", Modifier.weight(1f))
            Figure("${calls.size}", "API calls", Modifier.weight(1f))
            Figure(slow?.toString() ?: "–", "slowest ms", Modifier.weight(1f))
        }
        if (failed > 0) {
            Spacer(Modifier.height(12.dp))
            Eyebrow("$failed call${if (failed == 1) "" else "s"} failed", color = Hypurr.colors.needsText, dot = Hypurr.colors.needs)
        }
    }
}

@Composable
private fun ChainCard(index: Int, click: TraceClick, chain: List<String>) {
    val c = Hypurr.colors
    TCard("Click ${index + 1} · ${click.label.ifEmpty { click.tag }}") {
        chain.forEachIndexed { i, line ->
            Row(Modifier.padding(vertical = 5.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(22.dp)) {
                    Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (i == 0) c.text else c.borderStrong))
                }
                Spacer(Modifier.width(8.dp))
                Text(line, color = if (i == 0) c.text else c.secondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun statusText(call: TraceCall): String = when {
    call.blocked -> "Blocked"
    call.failed != null -> "Failed"
    call.status != null -> "${call.status}"
    else -> "…"
}

@Composable
private fun MethodTag(method: String) {
    val c = Hypurr.colors
    Text(method, color = c.text, style = SignalType.mono,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun CodeLine(label: String, value: String) {
    val c = Hypurr.colors
    Row(Modifier.padding(top = 6.dp)) {
        Text(label, color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(78.dp))
        Text(value, color = c.text, style = SignalType.mono)
    }
}

@Composable
private fun CallCard(call: TraceCall, open: Boolean, onToggle: () -> Unit) {
    val c = Hypurr.colors
    val bad = call.failed != null || (call.status ?: 0) >= 400
    SignalCard(Modifier.fillMaxWidth().pressable(if (open) "Hide details" else "Show details", onClick = onToggle), emphasis = bad) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodTag(call.method)
                Spacer(Modifier.width(10.dp))
                Text(tracePathOf(call.url), color = c.text, style = SignalType.mono, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(statusText(call), color = if (bad) c.needsText else c.text, style = MaterialTheme.typography.titleSmall)
                call.ms?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("$it ms", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
            call.sentFrom?.place?.let { CodeLine("Sent by", it) }
            call.handledBy?.let { CodeLine("Handled by", "${it.file}:${it.line}") }
            if (call.handledBy == null && !call.blocked) CodeLine("Backend", "not in this repo")
            if (open) {
                if (call.requestBody.isNotBlank()) Body("Sent", call.requestBody)
                if (call.responseBody.isNotBlank()) Body("Got back", call.responseBody)
                if (call.requestBody.isBlank() && call.responseBody.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("No body.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Body(label: String, text: String) {
    val c = Hypurr.colors
    Spacer(Modifier.height(12.dp))
    Eyebrow(label)
    Spacer(Modifier.height(6.dp))
    Text(text.lines().take(18).joinToString("\n"), color = c.text, style = SignalType.mono,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp))
}

@Composable
private fun ExportCard(e: TraceExport) {
    val c = Hypurr.colors
    TCard("Playwright test · draft") {
        Text(e.code.lines().take(24).joinToString("\n"), color = c.text, style = SignalType.mono,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp))
        Spacer(Modifier.height(8.dp))
        Text("Saved on the laptop at ${e.path.substringAfterLast(".hypurr/")}. Review it before adding it to your tests.",
            color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}

// ---------- Page map ----------

private fun androidx.compose.foundation.lazy.LazyListScope.mapItems(state: TraceMapUiState, onChange: (TraceMapUiState) -> Unit) {
    val m = state.map
    if (m == null) {
        item {
            TCard("Page map") {
                Text("Hypurr reads the project once and lists every page, its buttons, the API calls behind them and the files that hold them.",
                    color = Hypurr.colors.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text("Nothing runs and nothing leaves the laptop. Saved in .hypurr/map/.", color = Hypurr.colors.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    when (state.mapStep) {
        MapStep.Pages -> {
            item { MapSummary(m) }
            state.plan?.takeIf { it.moves.isNotEmpty() }?.let { p ->
                item { PlanTeaser(p, state.applied) { onChange(state.copy(mapStep = MapStep.Plan)) } }
            }
            items(m.pages, key = { it.path }) { p -> PageRow(p) { onChange(state.copy(mapStep = MapStep.Page, page = p.path)) } }
            if (m.unusedRoutes.isNotEmpty()) item { UnusedRoutes(m.unusedRoutes) }
        }
        MapStep.Page -> state.openPage?.let { p ->
            item { PageFiles(p) }
            if (p.elements.isNotEmpty()) item { Elements(p) }
            item { PageCalls(p) }
        }
        MapStep.Plan -> state.plan?.let { p ->
            item { PlanSummary(p) }
            items(p.moves, key = { it.from }) { mv -> MoveRow(mv.from, mv.to, mv.why) }
            if (p.notes.isNotEmpty()) item { NotesCard(p.notes) }
            when {
                state.applied != null -> item { AppliedCard(state.applied) }
                state.confirmApply -> item { ConfirmApply(p) }
            }
        }
    }
}

@Composable
private fun MapSummary(m: PageMapData) {
    TCard(m.router.ifEmpty { "No router found" }) {
        Row(Modifier.fillMaxWidth()) {
            Figure("${m.pages.size}", "pages", Modifier.weight(1f))
            Figure("${m.pages.sumOf { it.calls.size }}", "API calls", Modifier.weight(1f))
            Figure("${m.routes.size}", "backend routes", Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlanTeaser(p: RestructurePlan, applied: RestructureApplied?, onOpen: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth().pressable("Review restructure plan", onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                Icon(signalIcon(SignalIcons.Folder), null, tint = c.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Suggested folder layout", color = c.text, style = MaterialTheme.typography.titleSmall)
                Text(when (applied?.status) {
                    "ready" -> "Branch ready, tests passed"
                    "running" -> "Moving files and running tests"
                    "testsFailed" -> "Tests failed on the branch"
                    else -> "${p.moves.size} files to move · a plan only"
                }, color = if (applied?.status == "testsFailed") c.needsText else c.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PageRow(p: MapPage, onOpen: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth().pressable("Open ${p.path}", onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.path, color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(p.file, color = c.tertiary, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val buttons = p.elements.count { it.kind == "button" || it.kind == "form" }
                Text("$buttons action${if (buttons == 1) "" else "s"} · ${p.calls.size} API call${if (p.calls.size == 1) "" else "s"}",
                    color = c.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(signalIcon(SignalIcons.Chevron), null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PageFiles(p: MapPage) {
    val c = Hypurr.colors
    TCard("Files") {
        p.files.forEach { f ->
            Text(f, color = if (f == p.file) c.text else c.secondary, style = SignalType.mono, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun Elements(p: MapPage) {
    val c = Hypurr.colors
    TCard("What you can do here") {
        p.elements.forEachIndexed { i, e ->
            if (i > 0) HairlineDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.label.ifEmpty { "(no label)" }, color = c.text, style = MaterialTheme.typography.bodyLarge)
                    Text("${e.kind}${e.handler?.let { " → $it" } ?: ""}", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                Text("${e.file.substringAfterLast('/')}:${e.line}", color = c.secondary, style = SignalType.mono)
            }
        }
    }
}

@Composable
private fun PageCalls(p: MapPage) {
    val c = Hypurr.colors
    TCard("API calls") {
        if (p.calls.isEmpty()) Text("None.", color = c.tertiary, style = MaterialTheme.typography.bodyMedium)
        p.calls.forEachIndexed { i, call ->
            if (i > 0) HairlineDivider(Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodTag(call.method)
                Spacer(Modifier.width(10.dp))
                Text(call.path, color = c.text, style = SignalType.mono, modifier = Modifier.weight(1f))
            }
            CodeLine("Sent by", "${call.via?.let { "$it · " } ?: ""}${call.file}:${call.line}")
            CodeLine("Handled by", call.handledBy?.let { "${it.file}:${it.line}" } ?: "not in this repo")
        }
    }
}

@Composable
private fun UnusedRoutes(routes: List<TraceRoute>) {
    val c = Hypurr.colors
    TCard("Backend routes no page calls") {
        routes.forEach { r ->
            Text("${r.method} ${r.path}", color = c.text, style = SignalType.mono, modifier = Modifier.padding(top = 4.dp))
            Text("${r.file}:${r.line}", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlanSummary(p: RestructurePlan) {
    val c = Hypurr.colors
    TCard("A plan only · nothing has moved") {
        Row(Modifier.fillMaxWidth()) {
            Figure("${p.moves.size}", "files to move", Modifier.weight(1f))
            Figure("${p.notes.size}", "to do by hand", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text("Inside ${p.root.ifEmpty { "the project" }}: pages go in pages/, shared pieces in components/, API calls in api/. Imports are fixed for you.",
            color = c.secondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MoveRow(from: String, to: String, why: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(from, color = c.tertiary, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(signalIcon(SignalIcons.Chevron), null, tint = c.text, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(to, color = c.text, style = SignalType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(why.replaceFirstChar { it.uppercase() }, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NotesCard(notes: List<String>) {
    val c = Hypurr.colors
    TCard("Worth doing by hand") {
        notes.forEach { Text("• $it", color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

@Composable
private fun ConfirmApply(p: RestructurePlan) {
    val c = Hypurr.colors
    TCard(emphasis = true) {
        Eyebrow("Needs you", color = c.needsText, dot = c.needs)
        Spacer(Modifier.height(10.dp))
        Text("Move ${p.moves.size} files on a new branch?", color = c.text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("Hypurr makes a hypurr/restructure branch in its own copy, moves the files, fixes imports and runs " +
            "${p.testCommand.ifEmpty { "your tests" }}. Your checkout isn't touched and nothing is uploaded.",
            color = c.secondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AppliedCard(a: RestructureApplied) {
    val c = Hypurr.colors
    val failed = a.status == "testsFailed" || a.status == "failed"
    TCard(emphasis = failed) {
        when (a.status) {
            "running" -> Text("Moving files and running the tests…", color = c.text, style = MaterialTheme.typography.bodyLarge)
            "ready", "noTests" -> {
                Eyebrow(if (a.status == "ready") "Tests passed" else "No tests found", color = c.text, dot = c.text)
                Spacer(Modifier.height(10.dp))
                Text(a.branch, color = c.text, style = SignalType.mono)
                Spacer(Modifier.height(6.dp))
                Text("${a.moved} files moved, ${a.rewritten} files' imports fixed. " +
                    (a.prUrl?.let { "Pull request opened." } ?: "The pull request is a draft until you open it."),
                    color = c.secondary, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Eyebrow("Needs you", color = c.needsText, dot = c.needs)
                Spacer(Modifier.height(10.dp))
                Text(a.error ?: "The tests failed on the new branch, so it isn't offered as a pull request.",
                    color = c.text, style = MaterialTheme.typography.bodyMedium)
                if (a.testOutput.isNotBlank()) Body("Test output", a.testOutput.lines().takeLast(12).joinToString("\n"))
            }
        }
    }
}

// ---------- shared ----------

@Composable
private fun Notice(text: String) {
    Text(text, color = Hypurr.colors.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
}

@Composable
private fun BusyCard(label: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c.text))
            Spacer(Modifier.width(10.dp))
            Text(label.ifEmpty { "Working" } + "…", color = c.secondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun NeedsYouNote(text: String) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(signalIcon(SignalIcons.NeedsYou), null, tint = c.needs, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = c.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BottomBar(
    state: TraceMapUiState, onChange: (TraceMapUiState) -> Unit, onStart: () -> Unit, onAllow: () -> Unit, onStop: () -> Unit,
    onExport: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onScan: () -> Unit, onApply: () -> Unit, onPublish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = Hypurr.colors
    Column(Modifier.background(c.bg).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        val t = state.trace
        if (state.tab == 0) when {
            state.approval != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Cancel", Modifier.weight(1f)) { onChange(state.copy(approval = null)) }
                PrimaryButton("Allow for this project", Modifier.weight(1.6f), icon = signalIcon(SignalIcons.Allow), onClick = onAllow)
            }
            t == null || t.status == "failed" -> PrimaryButton("Open and trace", Modifier.fillMaxWidth(), enabled = state.url.isNotBlank() && state.project != null,
                icon = signalIcon(SignalIcons.Trace), onClick = onStart)
            t.status == "recording" -> PrimaryButton("Stop and show the trace", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Stop), onClick = onStop)
            state.exported != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(if (state.copied) "Copied" else "Copy", Modifier.weight(1f), icon = signalIcon(SignalIcons.Paste), onClick = onCopy)
                PrimaryButton("Share", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send), onClick = onShare)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("New trace", Modifier.weight(1f), icon = signalIcon(SignalIcons.Refresh)) { onChange(state.copy(trace = null, open = null, exported = null)) }
                PrimaryButton("Playwright test", Modifier.weight(1.3f), enabled = t.clicks.isNotEmpty(), icon = signalIcon(SignalIcons.Tests), onClick = onExport)
            }
        } else when (state.mapStep) {
            MapStep.Pages -> PrimaryButton(if (state.map == null) "Make the page map" else "Scan again", Modifier.fillMaxWidth(),
                enabled = state.project != null, icon = signalIcon(SignalIcons.Map), onClick = onScan)
            MapStep.Page -> SoftButton("All pages", Modifier.fillMaxWidth()) { onChange(state.copy(mapStep = MapStep.Pages, page = null)) }
            MapStep.Plan -> {
                val a = state.applied
                when {
                    a?.status == "running" -> SoftButton("Back to pages", Modifier.fillMaxWidth()) { onChange(state.copy(mapStep = MapStep.Pages)) }
                    a?.status == "ready" && a.prUrl == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Discard", Modifier.weight(1f), icon = signalIcon(SignalIcons.Trash), onClick = onDiscard)
                        PrimaryButton("Open pull request", Modifier.weight(1.6f), icon = signalIcon(SignalIcons.Branch), onClick = onPublish)
                    }
                    a != null -> SoftButton("Discard the branch", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Trash), onClick = onDiscard)
                    state.confirmApply -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Not now", Modifier.weight(1f)) { onChange(state.copy(confirmApply = false)) }
                        PrimaryButton("Move on a new branch", Modifier.weight(1.6f), icon = signalIcon(SignalIcons.Allow), onClick = onApply)
                    }
                    else -> PrimaryButton("Apply on a new branch", Modifier.fillMaxWidth(), enabled = state.plan?.moves?.isNotEmpty() == true,
                        icon = signalIcon(SignalIcons.Branch)) { onChange(state.copy(confirmApply = true)) }
                }
            }
        }
    }
}

/** `http://localhost:5173/api/orders?x=1` → `/api/orders?x=1`. */
fun tracePathOf(url: String): String {
    val rest = url.substringAfter("://", url)
    val i = rest.indexOf('/')
    return if (i < 0) "/" else rest.substring(i)
}

