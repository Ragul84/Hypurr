package com.ragul84.hypurr.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.ui.DotCat
import com.ragul84.hypurr.ui.SignalCard
import com.ragul84.hypurr.ui.PrimaryButton
import com.ragul84.hypurr.ui.IconTile
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.SignalIcons
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.signalIcon
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

enum class OnboardingStep(val title: String, val hint: String) {
    Role("What's your role?", "Pick the closest one."),
    Experience("How much code do you write?", "Hypurr explains more, or less, to match."),
    Stack("Main stack", "Optional. Pick any that apply."),
    Goals("What will you mostly do?", "Pick any. We'll put those templates first."),
    Voice("Which language do you speak?", "For the mic and read-aloud. Code always stays in English."),
}

private val ExperienceHints = mapOf(
    "New" to "Plain words, tests first, asks before anything risky",
    "Some" to "Short explanations, the usual safety checks",
    "Comfortable" to "Terse replies, fewer check-ins",
)

private fun goalIcon(goal: String) = when (goal) {
    "Write tests" -> SignalIcons.Tests
    "Fix bugs" -> SignalIcons.FixError
    "Review PRs" -> SignalIcons.Review
    else -> SignalIcons.Explain
}

/**
 * First-run Signal onboarding: four short, optional questions. "Skip for now" on every step.
 * Answers stay on this phone (only the fixed choices go to the computer to tune the agent).
 * Never asks for employee ID, company, salary or anything else sensitive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    initial: UserProfile = UserProfile(),
    /** Used as the You-tab editor: "Cancel" instead of skip, "Save" at the end. */
    editing: Boolean = false,
    initialStep: Int = 0,
    onFinished: (UserProfile) -> Unit,
    onSkipAll: () -> Unit = { onFinished(UserProfile(completed = true, skipped = true)) },
) {
    val c = Hypurr.colors
    var step by remember { mutableIntStateOf(initialStep) }
    var role by remember { mutableStateOf(initial.role) }
    var experience by remember { mutableStateOf(initial.experience) }
    var stacks by remember { mutableStateOf(initial.stacks.toSet()) }
    var goals by remember { mutableStateOf(initial.goals.toSet()) }
    var voiceLanguage by remember { mutableStateOf(com.ragul84.hypurr.voice.VoiceLanguage.of(initial.voiceLanguage)) }
    var explain by remember { mutableStateOf(initial.explainInMyLanguage) }
    val steps = OnboardingStep.entries
    val current = steps[step.coerceIn(0, steps.lastIndex)]
    val last = step >= steps.lastIndex
    val answered = when (current) {
        OnboardingStep.Role -> role != null
        OnboardingStep.Experience -> experience != null
        OnboardingStep.Stack -> stacks.isNotEmpty()
        OnboardingStep.Goals -> goals.isNotEmpty()
        OnboardingStep.Voice -> true
    }

    fun answers(skipped: Boolean) = UserProfile(
        completed = true,
        skipped = skipped,
        role = role,
        experience = experience,
        stacks = UserProfile.Stacks.filter { it in stacks },
        goals = UserProfile.Goals.filter { it in goals },
        voiceLanguage = voiceLanguage.tag,
        explainInMyLanguage = explain && !voiceLanguage.isEnglish,
    )

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 24.dp)) {
        // Top row: back, progress, and the step count.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                IconTile(signalIcon(SignalIcons.Back), "Back") { step-- }
                Spacer(Modifier.width(14.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50))
                            .background(if (i <= step) c.accent else c.dotOff),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text("${step + 1} / ${steps.size}", color = c.text, style = com.ragul84.hypurr.ui.theme.SignalType.figureSmall)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            if (step == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) { DotCat(c.text, 32.dp, off = c.dotOff) }
                    Spacer(Modifier.width(14.dp))
                    com.ragul84.hypurr.ui.Eyebrow(if (editing) "Your profile" else "Tune Hypurr to you", color = c.secondary)
                }
                Spacer(Modifier.height(20.dp))
            }
            AnimatedContent(current, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "step") { s ->
                Column {
                    Text(s.title, style = MaterialTheme.typography.headlineLarge, color = c.text)
                    Spacer(Modifier.height(6.dp))
                    Text(s.hint, style = MaterialTheme.typography.bodyLarge, color = c.secondary)
                    Spacer(Modifier.height(24.dp))
                    when (s) {
                        OnboardingStep.Role -> FlowRow(
                            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2,
                        ) {
                            UserProfile.Roles.forEach { r ->
                                OptionTile(r, null, null, r == role, Modifier.weight(1f)) { role = if (role == r) null else r }
                            }
                        }
                        OnboardingStep.Experience -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            UserProfile.Experiences.forEach { e ->
                                OptionTile(e, ExperienceHints[e], null, e == experience, Modifier.fillMaxWidth()) {
                                    experience = if (experience == e) null else e
                                }
                            }
                        }
                        OnboardingStep.Stack -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            UserProfile.Stacks.forEach { st ->
                                Chip(st, st in stacks) { stacks = if (st in stacks) stacks - st else stacks + st }
                            }
                        }
                        OnboardingStep.Goals -> FlowRow(
                            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2,
                        ) {
                            UserProfile.Goals.forEach { g ->
                                OptionTile(g, null, goalIcon(g), g in goals, Modifier.weight(1f)) { goals = if (g in goals) goals - g else goals + g }
                            }
                        }
                        OnboardingStep.Voice -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            com.ragul84.hypurr.ui.VoiceLanguagePicker(voiceLanguage) { voiceLanguage = it }
                            if (!voiceLanguage.isEnglish) {
                                OptionTile("Explain in my language", "Code in English, steps and summary in ${voiceLanguage.label}",
                                    SignalIcons.Speaker, explain, Modifier.fillMaxWidth()) { explain = !explain }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(signalIcon(SignalIcons.Shield), null, tint = c.secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Stays on this phone. No employee ID, company or salary, ever.",
                    style = MaterialTheme.typography.bodySmall, color = c.secondary,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        // Pinned action bar: always on screen above the nav bar / gesture area, whatever the text size.
        Column(Modifier.fillMaxWidth().background(c.bg).padding(top = 12.dp, bottom = 16.dp)) {
            PrimaryButton(
                if (last && editing) "Save" else "Continue",
                Modifier.fillMaxWidth(),
                // The stack is optional; the other steps need a pick (Skip for now covers "not now").
                enabled = answered || current == OnboardingStep.Stack,
            ) { if (last) onFinished(answers(skipped = false)) else step++ }
            Spacer(Modifier.height(10.dp))
            SoftButton(
                if (editing) "Cancel" else "Skip for now",
                Modifier.fillMaxWidth(),
                tint = c.text,
            ) { if (editing) onSkipAll() else onFinished(answers(skipped = true)) }
        }
    }
}

/** An answer card; the chosen one gets the 1.5dp ink border and a filled check, like the mockup's live bot card. */
@Composable
private fun OptionTile(label: String, hint: String?, icon: Int?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    SignalCard(modifier.pressable(label, onClick = onClick), RoundedCornerShape(20.dp), emphasis = on) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (hint != null) 15.dp else 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(signalIcon(icon), null, tint = c.text, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (hint != null) {
                    Text(hint, color = c.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(if (on) c.accent else Color.Transparent)
                    .border(1.5.dp, if (on) c.accent else c.borderStrong, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(signalIcon(SignalIcons.Check), null, tint = c.onAccent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Hypurr.colors
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (on) c.accent else c.surface)
            .border(1.dp, if (on) c.accent else c.border, RoundedCornerShape(50))
            .pressable(label, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.titleSmall)
    }
}
