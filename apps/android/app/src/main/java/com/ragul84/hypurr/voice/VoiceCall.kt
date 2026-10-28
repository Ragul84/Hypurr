package com.ragul84.hypurr.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.ragul84.hypurr.ui.SignalIcons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.delay

/** What a hands-free voice chat does next. */
sealed interface CallCue {
    /** Read the agent's finished reply aloud. */
    data class Say(val key: String, val text: String) : CallCue
    /** Ask the user to allow or deny an action. */
    data class Ask(val entry: Entry) : CallCue
    data object Listen : CallCue
    data object Wait : CallCue
}

private val EndWords = listOf("end call", "hang up", "stop the call", "bye hypurr", "goodbye hypurr")

fun isEndOfCall(said: String): Boolean = said.lowercase().let { s -> EndWords.any { it in s } }

/**
 * `entries` oldest first. A pending approval comes first, then the newest finished reply not read
 * yet; while the agent works, wait; otherwise listen.
 */
fun callCue(entries: List<Entry>, done: Set<String>, working: Boolean): CallCue {
    entries.lastOrNull { it.kind == "permission" && it.data.status == "pending" && "ask:${it.id}" !in done }
        ?.let { return CallCue.Ask(it) }
    entries.lastOrNull { it.kind == "agent" && it.data.final == true }
        ?.takeIf { "say:${it.id}" !in done && !it.data.text.isNullOrBlank() }
        ?.let { return CallCue.Say("say:${it.id}", it.data.text!!) }
    return if (working) CallCue.Wait else CallCue.Listen
}

/** Spoken form of an approval card. */
fun askLine(entry: Entry): String =
    "It wants to ${entry.data.title?.ifBlank { null } ?: "run a step"}. Say yes or no."

fun callLength(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Hands-free voice chat with one agent: speak, it works, its reply is read aloud, repeat.
 * Approvals follow the same rule as the chat: a spoken yes only sends for low-risk actions.
 */
@Composable
fun VoiceCallScreen(
    bot: Bot,
    entries: List<Entry>,
    language: VoiceLanguage,
    onSend: (String) -> Unit,
    onRespond: (Entry, String?) -> Unit,
    onEnd: (seconds: Long) -> Unit,
) {
    val c = Hypurr.colors
    val speaker = rememberSpeaker()
    var done by remember { mutableStateOf(entries.mapNotNull { e ->
        // Start fresh: nothing said before the call is read out.
        when (e.kind) { "agent" -> "say:${e.id}"; "permission" -> "ask:${e.id}"; else -> null }
    }.toSet()) }
    var asking by remember { mutableStateOf<Entry?>(null) }
    var status by remember { mutableStateOf("Listening…") }
    val started = remember { System.currentTimeMillis() }
    var elapsed by remember { mutableLongStateOf(0L) }
    val working = bot.status == "working"
    fun end() { speaker.stop(); onEnd(elapsed) }
    androidx.activity.compose.BackHandler { end() }
    val mic = rememberVoiceInput(language) { said ->
        val a = asking
        when {
            isEndOfCall(said) -> end()
            a != null -> {
                val opts = a.data.options.orEmpty()
                val deny = opts.firstOrNull { it.kind.startsWith("reject") }
                val allow = opts.firstOrNull { !it.kind.startsWith("reject") && !it.kind.contains("always", true) }
                when (voiceApprovalStep(parseVoiceAnswer(said), a.data.risk)) {
                    VoiceStep.SendDeny -> { onRespond(a, deny?.optionId); asking = null }
                    VoiceStep.SendAllow -> { onRespond(a, allow?.optionId); asking = null }
                    VoiceStep.ConfirmOnScreen -> status = "This one needs a tap: open the chat to allow it."
                    VoiceStep.AskAgain -> status = "Say yes or no."
                }
            }
            else -> { status = "Sent. Working…"; onSend(said) }
        }
    }
    LaunchedEffect(Unit) { while (true) { elapsed = (System.currentTimeMillis() - started) / 1000; delay(1_000) } }
    LaunchedEffect(entries, working, speaker.speaking, mic.listening, asking, mic.error) {
        if (speaker.speaking || mic.listening || mic.error != null) return@LaunchedEffect
        when (val cue = callCue(entries, done, working)) {
            is CallCue.Ask -> { done = done + "ask:${cue.entry.id}"; asking = cue.entry; status = askLine(cue.entry); speaker.speak(status, language) }
            is CallCue.Say -> { done = done + cue.key; status = "Reading the reply…"; speaker.speak(cue.text, language) }
            CallCue.Wait -> status = "Working…"
            CallCue.Listen -> { delay(400); status = if (asking != null) "Say yes or no." else "Listening…"; mic.start() }
        }
    }
    // Header, a centered live area, and the actions pinned above the nav bar, all on 24dp margins.
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("VOICE CHAT · ${callLength(elapsed)}", color = c.secondary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        Text(bot.name.ifBlank { "Voice chat" }, color = c.text, style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            val live = mic.listening || speaker.speaking
            Box(Modifier.size(112.dp).clip(CircleShape).background(if (live) c.accent else c.surface), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (speaker.speaking) SignalIcons.Speaker else SignalIcons.Mic), contentDescription = null,
                    tint = if (live) c.onAccent else c.secondary, modifier = Modifier.size(40.dp))
            }
            Text(if (mic.listening && mic.partial.isNotBlank()) mic.partial else status, color = c.text,
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
            mic.error?.let {
                Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                SoftButton("Try again") { mic.error = null }
            }
        }
        Text("Say \"end call\" to finish.", color = c.tertiary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        if (speaker.speaking) { SoftButton("Skip reading", Modifier.fillMaxWidth()) { speaker.stop() }; Spacer(Modifier.height(8.dp)) }
        PrimaryButton("End call", Modifier.fillMaxWidth()) { end() }
    }
}
