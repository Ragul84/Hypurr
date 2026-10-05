package com.ragul84.hypurr.ui.screens
import com.ragul84.hypurr.ui.SunfieldIcons
import com.ragul84.hypurr.ui.sunfieldVector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.ScreenState
import com.ragul84.hypurr.screen.DisplayPoint
import com.ragul84.hypurr.screen.ScreenInput
import com.ragul84.hypurr.screen.ScreenPhase
import com.ragul84.hypurr.ui.FlowOrb
import com.ragul84.hypurr.ui.IconBubble
import com.ragul84.hypurr.ui.Pill
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.glass
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.serialization.json.JsonObject

/** What the screen viewer shows (the video itself comes from [ScreenScreen]'s `video` slot). */
data class ScreenUiState(
    val computerName: String = "",
    /** Null until `screenStatus` answers. */
    val status: ScreenState? = null,
    val phase: ScreenPhase = ScreenPhase.Connecting,
    /** The video's size in pixels, once the first frame arrives. */
    val frame: IntSize = IntSize.Zero,
    val clipboard: String? = null,
    val error: String? = null,
)

/**
 * The computer's screen: tap to click, double-tap for a double click, long-press for a right click,
 * drag to scroll, and a keyboard for typing. Video and input go over WebRTC (`screen/ScreenSession`).
 */
@Composable
fun ScreenScreen(
    state: ScreenUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onInput: (JsonObject) -> Unit,
    onTakeClipboard: () -> Unit,
    video: @Composable (Modifier) -> Unit,
) {
    val c = Hypurr.colors
    var view by remember { mutableStateOf(IntSize.Zero) }
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val display = state.status?.displays?.firstOrNull { it.main } ?: state.status?.displays?.firstOrNull()
    fun point(x: Float, y: Float): DisplayPoint? = ScreenInput.toDisplay(x, y, view.width.toFloat(), view.height.toFloat(),
        state.frame.width, state.frame.height, display?.width, display?.height)
    val off = state.status != null && !state.status.ready
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!off) {
            video(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().onSizeChanged { view = it }
                .pointerInput(state.frame, display, view) {
                    detectTapGestures(
                        onTap = { o -> point(o.x, o.y)?.let { onInput(ScreenInput.click(it)) } },
                        onDoubleTap = { o -> point(o.x, o.y)?.let { onInput(ScreenInput.click(it, count = 2)) } },
                        onLongPress = { o -> point(o.x, o.y)?.let { onInput(ScreenInput.click(it, button = "right")) } },
                    )
                }
                .pointerInput(state.frame, display, view) {
                    var at: DisplayPoint? = null
                    detectDragGestures(onDragStart = { o -> at = point(o.x, o.y) }) { change, drag ->
                        change.consume()
                        at?.let { onInput(ScreenInput.scroll(it, -drag.x * 1.5, -drag.y * 1.5)) }
                    }
                })
        }
        // Header: back, the computer, the connection.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(sunfieldVector(SunfieldIcons.Back), "Back", fill = Color.Black.copy(alpha = 0.5f), tint = Color.White, onClick = onBack)
            Spacer(Modifier.width(10.dp))
            Text(state.computerName, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            when (state.phase) {
                ScreenPhase.Live -> Pill("Live", c.success)
                ScreenPhase.Reconnecting -> Pill("Reconnecting", c.warning)
                ScreenPhase.Connecting -> if (!off) Pill("Connecting", c.tertiary)
                else -> Unit
            }
            if (state.clipboard != null) {
                Spacer(Modifier.width(8.dp))
                IconBubble(sunfieldVector(SunfieldIcons.Paste), "Copy the computer's clipboard", fill = Color.Black.copy(alpha = 0.5f), tint = Color.White,
                    onClick = onTakeClipboard)
            }
            if (state.phase == ScreenPhase.Live) {
                Spacer(Modifier.width(8.dp))
                IconBubble(sunfieldVector(SunfieldIcons.Docs), if (typing) "Hide keyboard" else "Type", fill = Color.Black.copy(alpha = 0.5f),
                    tint = if (typing) c.accent else Color.White) { typing = !typing }
            }
        }
        val failed = state.phase as? ScreenPhase.Failed
        when {
            off -> Message(sunfieldVector(SunfieldIcons.Host), "Remote screen is off",
                if (state.status?.enabled != true) "Turn on Remote screen in Hypurr's menu on the computer. It stays off until you do there."
                else "The computer's screen helper isn't running or doesn't have screen recording permission yet. Finish its setup on the computer.",
                null, onRetry)
            failed != null -> Message(sunfieldVector(SunfieldIcons.Host), "Couldn't show the screen", failed.message, "Try again", onRetry)
            state.phase == ScreenPhase.Connecting || state.phase == ScreenPhase.Reconnecting || state.frame == IntSize.Zero ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    FlowOrb(48.dp)
                    Text(if (state.phase == ScreenPhase.Reconnecting) "Reconnecting…" else "Connecting to the screen…", color = Color.White,
                        modifier = Modifier.padding(top = 12.dp))
                }
        }
        state.error?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.TopCenter)
                .statusBarsPadding().padding(top = 64.dp).glass(RoundedCornerShape(14.dp), c.danger.copy(alpha = 0.7f)).padding(10.dp))
        }
        AnimatedVisibility(typing && state.phase == ScreenPhase.Live, Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp)
                .glass(RoundedCornerShape(22.dp), c.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("return" to "Return", "delete" to "⌫", "tab" to "Tab", "escape" to "Esc", "left" to "←", "up" to "↑",
                        "down" to "↓", "right" to "→").forEach { (key, label) ->
                        SoftButton(label) { onInput(ScreenInput.key(key)) }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                    if (text.isEmpty()) Text("Type, then press send on the keyboard", color = c.tertiary, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(text, { text = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotEmpty()) onInput(ScreenInput.text(text))
                            text = ""
                        }), modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Message(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String,
                                                               action: String?, onAction: () -> Unit) {
    val c = Hypurr.colors
    Column(Modifier.align(Alignment.Center).padding(32.dp).glass(RoundedCornerShape(26.dp), c.surface).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = c.secondary)
        Text(title, color = c.text, style = MaterialTheme.typography.titleMedium)
        Text(body, color = c.secondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (action != null) SoftButton(action, icon = sunfieldVector(SunfieldIcons.Refresh), onClick = onAction)
        else SoftButton("Check again", icon = sunfieldVector(SunfieldIcons.Refresh), tint = c.secondary, onClick = onAction)
    }
}
