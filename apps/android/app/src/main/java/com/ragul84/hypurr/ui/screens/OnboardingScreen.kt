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
import com.ragul84.hypurr.ui.CatFace
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.InkTile
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.SunfieldIcons
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.sunfieldVector
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.Motion

enum class OnboardingStep(val title: String, val hint: String) {
    Role("What's your role?", "Pick the closest one."),
    Experience("How much code do you write?", "Hypurr explains more, or less, to match."),
    Stack("Main stack", "Optional. Pick any that apply."),
    Goals("What will you mostly do?", "Pick any. We'll put those templates first."),
}

private val ExperienceHints = mapOf(
    "New" to "Plain words, tests first, asks before anything risky",
    "Some" to "Short explanations, the usual safety checks",
    "Comfortable" to "Terse replies, fewer check-ins",
)

private fun goalIcon(goal: String) = when (goal) {
    "Write tests" -> SunfieldIcons.Tests
    "Fix bugs" -> SunfieldIcons.FixError
    "Review PRs" -> SunfieldIcons.Review
    else -> SunfieldIcons.Explain
}

/**
 * First-run Sunfield onboarding: four short, optional questions. "Skip for now" on every step.
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
    val steps = OnboardingStep.entries
    val current = steps[step.coerceIn(0, steps.lastIndex)]
    val last = step >= steps.lastIndex
    val answered = when (current) {
        OnboardingStep.Role -> role != null
        OnboardingStep.Experience -> experience != null
        OnboardingStep.Stack -> stacks.isNotEmpty()
        OnboardingStep.Goals -> goals.isNotEmpty()
    }

    fun answers(skipped: Boolean) = UserProfile(
        completed = true,
        skipped = skipped,
        role = role,
        experience = experience,
        stacks = UserProfile.Stacks.filter { it in stacks },
        goals = UserProfile.Goals.filter { it in goals },
    )

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 24.dp)) {
        // Top row: back, progress, and the step count.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) {
                InkTile(sunfieldVector(SunfieldIcons.Back), "Back") { step-- }
                Spacer(Modifier.width(14.dp))
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50))
                            .background(if (i <= step) c.accent else c.text.copy(alpha = 0.14f)),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text("${step + 1} of ${steps.size}", color = c.secondary, style = MaterialTheme.typography.labelLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            if (step == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (c.dark) c.surface else Color(0xFF15130F)),
                        contentAlignment = Alignment.Center,
                    ) { CatFace(if (c.dark) c.text else Color(0xFFFFF8E8), 32.dp) }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        if (editing) "Your profile" else "Tune Hypurr to you",
                        style = MaterialTheme.typography.titleMedium, color = c.secondary, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            AnimatedContent(current, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "step") { s ->
                Column {
                    Text(s.title, style = MaterialTheme.typography.headlineMedium, color = c.text, fontWeight = FontWeight.ExtraBold)
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
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(sunfieldVector(SunfieldIcons.Shield), null, tint = c.secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Stays on this phone. No employee ID, company or salary, ever.",
                    style = MaterialTheme.typography.bodySmall, color = c.secondary,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        FlowButton(
            when {
                last && editing -> "Save"
                last -> "Done"
                answered -> "Next"
                else -> "Skip this one"
            },
            Modifier.fillMaxWidth(),
        ) { if (last) onFinished(answers(skipped = false)) else step++ }
        Spacer(Modifier.height(10.dp))
        SoftButton(
            if (editing) "Cancel" else "Skip for now",
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            tint = c.text,
        ) { if (editing) onSkipAll() else onFinished(answers(skipped = true)) }
    }
}

/** A cream answer tile; the chosen one turns forest green. */
@Composable
private fun OptionTile(label: String, hint: String?, icon: Int?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Hypurr.colors
    CreamPlate(modifier.pressable(label, onClick = onClick), RoundedCornerShape(18.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(if (on) c.accent else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = if (hint != null) 16.dp else 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(sunfieldVector(icon), null, tint = if (on) c.onAccent else c.text, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(label, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (hint != null) {
                        Text(hint, color = if (on) c.onAccent.copy(alpha = 0.8f) else c.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (on) Icon(sunfieldVector(SunfieldIcons.Check), null, tint = c.onAccent, modifier = Modifier.size(18.dp))
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
            .border(1.dp, if (on) c.accent else c.text.copy(alpha = 0.12f), RoundedCornerShape(50))
            .pressable(label, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = if (on) c.onAccent else c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
