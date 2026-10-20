package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragul84.hypurr.model.Proof
import com.ragul84.hypurr.model.ProofShot
import com.ragul84.hypurr.ui.Eyebrow
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlin.math.roundToInt

/** The pass/fail line under a proof: "12 passed · 1 failed in 41s". */
fun testLine(passed: Int, failed: Int, seconds: Int): String =
    buildString {
        append("$passed passed")
        if (failed > 0) append(" · $failed failed")
        if (seconds > 0) append(" in ${if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"}")
    }

/** The card's title for each kind of proof. */
fun proofTitle(p: Proof): String = when {
    p.status == "needsApproval" -> "Show me needs to run your project"
    p.status == "running" -> "Making the proof…"
    p.status == "failed" -> "Couldn't make the proof"
    p.kind == "web" -> "Your pages, before and after"
    p.kind == "android" -> if (p.shots.any { it.before != null }) "Your screens, before and after" else "Your app on the phone"
    p.tests?.let { it.failed > 0 } == true -> "Some tests failed"
    p.tests != null -> "All tests passed"
    else -> "What changed"
}

/**
 * "Show me it works" in a task's chat: proof the change does what was asked. Optional: nothing
 * runs until the person taps Show me (or the project was allowed before, when a task finishes).
 */
@Composable
fun ProofCard(
    proof: Proof?,
    images: Map<String, ImageBitmap> = emptyMap(),
    busy: Boolean = false,
    onShowMe: () -> Unit = {},
    onAllow: () -> Unit = {},
    onLoad: (String) -> Unit = {},
    onPlayClip: (String) -> Unit = {},
    onVerdict: (ok: Boolean, text: String) -> Unit = { _, _ -> },
    initialShot: Int = 0,
    initialSplit: Float = 0.5f,
    initialFixing: Boolean = false,
    initialFeedback: String = "",
) {
    val c = Hypurr.colors
    SignalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(signalIcon(SignalIcons.Image), null, tint = c.text, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Eyebrow("Proof", Modifier.weight(1f), pulse = proof?.status == "running")
                if (proof?.status == "ready" || proof?.status == "failed") {
                    Text("Again", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).pressable("Make the proof again", onClick = onShowMe)
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            if (proof == null || proof.status.isEmpty()) {
                Text("See it working", color = c.text, style = MaterialTheme.typography.titleMedium)
                Text("Hypurr runs the result on your computer and shows you screenshots, a short clip or the test results.",
                    color = c.secondary, style = MaterialTheme.typography.bodySmall)
                PrimaryButton(if (busy) "Starting…" else "Show me", Modifier.fillMaxWidth(), enabled = !busy, icon = signalIcon(SignalIcons.Play), onClick = onShowMe)
                return@Column
            }
            Text(proofTitle(proof), color = c.text, style = MaterialTheme.typography.titleMedium)
            when (proof.status) {
                "needsApproval" -> Approval(proof, busy, onAllow)
                "running" -> Text(proof.plan?.what?.let { "$it. Servers stop as soon as it's done." } ?: "Working on it.",
                    color = c.secondary, style = MaterialTheme.typography.bodySmall)
                "failed" -> {
                    if (proof.note.isNotBlank()) Text(proof.note, color = c.secondary, style = MaterialTheme.typography.bodySmall)
                    Summary(proof)
                    LogBox(proof.log)
                }
                else -> Ready(proof, images, onLoad, onPlayClip, onVerdict, initialShot, initialSplit, initialFixing, initialFeedback)
            }
        }
    }
}

@Composable
private fun Approval(proof: Proof, busy: Boolean, onAllow: () -> Unit) {
    val c = Hypurr.colors
    Text("${proof.plan?.what ?: "Run the project"}. This runs your project's code on your computer, so it asks once for this project.",
        color = c.secondary, style = MaterialTheme.typography.bodySmall)
    proof.plan?.command?.let {
        Text(it, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(10.dp))
    }
    Text("Runs in the task's safe copy, stops after a few minutes, and logs are cleaned of secrets.",
        color = c.tertiary, style = MaterialTheme.typography.bodySmall)
    PrimaryButton(if (busy) "Starting…" else "Allow for this project", Modifier.fillMaxWidth(), enabled = !busy,
        icon = signalIcon(SignalIcons.Allow), onClick = onAllow)
}

@Composable
private fun Ready(
    proof: Proof,
    images: Map<String, ImageBitmap>,
    onLoad: (String) -> Unit,
    onPlayClip: (String) -> Unit,
    onVerdict: (Boolean, String) -> Unit,
    initialShot: Int,
    initialSplit: Float,
    initialFixing: Boolean,
    initialFeedback: String,
) {
    val c = Hypurr.colors
    var pick by remember(proof.shots.size) { mutableStateOf(initialShot.coerceIn(0, (proof.shots.size - 1).coerceAtLeast(0))) }
    if (proof.shots.isNotEmpty()) {
        if (proof.shots.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                proof.shots.take(4).forEachIndexed { i, s ->
                    val on = i == pick
                    Text(s.label.ifBlank { "Shot ${i + 1}" }, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else c.surface2)
                            .pressable(s.label) { pick = i }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        val shot = proof.shots[pick]
        LaunchedEffect(shot) { listOfNotNull(shot.before, shot.after).forEach { if (it !in images) onLoad(it) } }
        BeforeAfter(shot, images, initialSplit)
    }
    proof.clip?.let { clip ->
        SoftButton("Play the clip", Modifier.fillMaxWidth(), icon = signalIcon(SignalIcons.Play)) { onPlayClip(clip) }
    }
    proof.tests?.let { t ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(signalIcon(if (t.failed > 0) SignalIcons.Close else SignalIcons.Check), null, tint = if (t.failed > 0) c.danger else c.text,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(testLine(t.passed, t.failed, t.seconds), color = c.text, style = MaterialTheme.typography.titleSmall)
                Text(t.command, color = c.tertiary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
            }
        }
        if (t.failed > 0) LogBox(t.output)
    }
    if (proof.note.isNotBlank()) Text(proof.note, color = c.secondary, style = MaterialTheme.typography.bodySmall)
    Summary(proof)
    Verdict(proof, onVerdict, initialFixing, initialFeedback)
}

@Composable
private fun Summary(proof: Proof) {
    val c = Hypurr.colors
    if (proof.summary.isBlank()) return
    Text(proof.summary, color = c.secondary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LogBox(log: String) {
    val c = Hypurr.colors
    if (log.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Text(if (open) "Hide the log" else "Show the log", color = c.tertiary, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(50)).pressable("Log") { open = !open }.padding(vertical = 2.dp))
    if (open) Text(log.lines().takeLast(20).joinToString("\n"), color = c.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(10.dp))
}

/** A before/after slider: drag (or tap) to move the line; with one side only, just that picture. */
@Composable
fun BeforeAfter(shot: ProofShot, images: Map<String, ImageBitmap>, initialSplit: Float = 0.5f) {
    val c = Hypurr.colors
    val before = shot.before?.let { images[it] }
    val after = shot.after?.let { images[it] }
    val both = shot.before != null && shot.after != null
    var split by remember(shot) { mutableFloatStateOf(initialSplit) }
    val ratio = (after ?: before)?.let { it.width.toFloat() / it.height } ?: (412f / 860f)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).border(1.dp, c.border, RoundedCornerShape(16.dp)).padding(vertical = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.fillMaxWidth(0.8f).aspectRatio(ratio.coerceIn(0.3f, 2f)).align(Alignment.Center)
                .then(if (both) Modifier.semantics { contentDescription = "Before and after"; stateDescription = "${(split * 100).roundToInt()}% before" }
                    .pointerInput(Unit) {
                        detectTapGestures { o -> split = (o.x / size.width).coerceIn(0f, 1f) }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
                    } else Modifier),
        ) {
            Picture(after ?: before, if (after != null) shot.after else shot.before)
            if (both) {
                Box(Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } }) {
                    Picture(before, shot.before)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val x = constraints.maxWidth * split
                    // Over a picture of any colour: a white line with a dark edge, a dark handle.
                    Box(Modifier.offset { IntOffset((x - 2.dp.toPx()).roundToInt(), 0) }.width(4.dp).fillMaxHeight().background(Overlay))
                    Box(Modifier.offset { IntOffset((x - 1.dp.toPx()).roundToInt(), 0) }.width(2.dp).fillMaxHeight().background(Color.White))
                    Box(Modifier.offset { IntOffset((x - 16.dp.toPx()).roundToInt(), 0) }.align(Alignment.CenterStart).size(32.dp)
                        .clip(CircleShape).background(Overlay).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Text("‹ ›", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Tag("Before", Modifier.align(Alignment.TopStart))
                Tag("After", Modifier.align(Alignment.TopEnd))
            } else {
                Tag(if (after != null) "After" else "Before", Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

@Composable
private fun Picture(img: ImageBitmap?, name: String?) {
    val c = Hypurr.colors
    if (img != null) {
        Image(img, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
    } else {
        Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
            Text("Loading…", color = c.tertiary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Overlay).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Verdict(proof: Proof, onVerdict: (Boolean, String) -> Unit, initialFixing: Boolean, initialFeedback: String) {
    val c = Hypurr.colors
    var fixing by remember { mutableStateOf(initialFixing) }
    var text by remember { mutableStateOf(initialFeedback) }
    proof.verdict?.let { v ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(signalIcon(if (v.ok) SignalIcons.Check else SignalIcons.Send), null, tint = c.secondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (v.ok) "You said it looks right." else "Sent to your agent to fix.", color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (fixing) {
        val style = MaterialTheme.typography.bodyMedium.copy(color = c.text)
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp)) {
            if (text.isEmpty()) Text("What's wrong? (optional) e.g. the button is cut off on small phones", color = c.tertiary, style = style)
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = style, cursorBrush = SolidColor(c.text))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Cancel", Modifier.weight(1f)) { fixing = false }
            PrimaryButton("Send to agent", Modifier.weight(1f), icon = signalIcon(SignalIcons.Send)) { onVerdict(false, text.trim()) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Not right, fix it", Modifier.weight(1f)) { fixing = true }
            PrimaryButton("Looks right", Modifier.weight(1f), icon = signalIcon(SignalIcons.Check)) { onVerdict(true, "") }
        }
    }
}


/** Labels and the slider handle sit on screenshots, so they keep one look in light and dark. */
private val Overlay = Color(0xCC111111)
