package com.ragul84.hypurr.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.ui.CatFace
import com.ragul84.hypurr.ui.CreamPlate
import com.ragul84.hypurr.ui.FlowButton
import com.ragul84.hypurr.ui.SoftButton
import com.ragul84.hypurr.ui.pressable
import com.ragul84.hypurr.ui.theme.Hypurr

enum class OnboardingStep { Role, Experience, Stack, Goals }

/** First-run Sunfield onboarding — fully skippable; answers stay on-device. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    initial: UserProfile = UserProfile(),
    /** When true, used as an editor under You (no “welcome” framing). */
    editing: Boolean = false,
    onFinished: (UserProfile) -> Unit,
    onSkipAll: () -> Unit = { onFinished(UserProfile(completed = true, skipped = true)) },
) {
    val c = Hypurr.colors
    var step by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf(initial.role) }
    var experience by remember { mutableStateOf(initial.experience) }
    var stacks by remember { mutableStateOf(initial.stacks.toSet()) }
    var goals by remember { mutableStateOf(initial.goals.toSet()) }
    val steps = OnboardingStep.entries
    val current = steps[step.coerceIn(0, steps.lastIndex)]

    fun finish(skipped: Boolean = false) {
        onFinished(
            UserProfile(
                completed = true,
                skipped = skipped,
                role = role,
                experience = experience,
                stacks = stacks.toList(),
                goals = goals.toList(),
            ),
        )
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 20.dp),
    ) {
        SoftButton("Skip for now", Modifier.align(Alignment.End).padding(top = 8.dp), tint = c.secondary) {
            if (editing) onFinished(initial.copy(completed = true)) else onSkipAll()
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (c.dark) c.surface else androidx.compose.ui.graphics.Color(0xFF15130F)),
                contentAlignment = Alignment.Center,
            ) {
                CatFace(if (c.dark) c.text else androidx.compose.ui.graphics.Color(0xFFFFF8E8), 44.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (editing) "Your profile" else "Quick setup",
                style = MaterialTheme.typography.headlineMedium,
                color = c.text,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                if (editing) "Stored only on this phone. Used to tune tone and defaults — never employee ID or salary."
                else "Optional. Helps Hypurr pick tone and templates. Skip anytime.",
                color = c.secondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
            )
            Spacer(Modifier.height(18.dp))
            // Step dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.size(if (i == step) 10.dp else 8.dp).clip(RoundedCornerShape(50))
                            .background(if (i <= step) c.accent else c.tertiary.copy(alpha = 0.35f)),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            CreamPlate(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when (current) {
                            OnboardingStep.Role -> "What's your role?"
                            OnboardingStep.Experience -> "Experience with code?"
                            OnboardingStep.Stack -> "Main stack (optional)"
                            OnboardingStep.Goals -> "What will you mostly do?"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = c.text,
                        fontWeight = FontWeight.Bold,
                    )
                    when (current) {
                        OnboardingStep.Role -> ChipGrid(UserProfile.Roles, role?.let { setOf(it) }.orEmpty(), single = true) {
                            role = it.singleOrNull(); stacks = stacks
                        }
                        OnboardingStep.Experience -> ChipGrid(UserProfile.Experiences, experience?.let { setOf(it) }.orEmpty(), single = true) {
                            experience = it.singleOrNull()
                        }
                        OnboardingStep.Stack -> ChipGrid(UserProfile.Stacks, stacks, single = false) { stacks = it }
                        OnboardingStep.Goals -> ChipGrid(UserProfile.Goals, goals, single = false) { goals = it }
                    }
                }
            }
        }
        SoftButton("Skip this step", Modifier.fillMaxWidth(), tint = c.secondary) {
            if (step >= steps.lastIndex) finish(skipped = !role.hasContent() && experience == null)
            else step++
        }
        Spacer(Modifier.height(8.dp))
        FlowButton(
            if (step >= steps.lastIndex) if (editing) "Save" else "Continue" else "Next",
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            onClick = {
                if (step >= steps.lastIndex) finish() else step++
            },
        )
    }
}

private fun String?.hasContent() = !isNullOrBlank()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGrid(
    options: List<String>,
    selected: Set<String>,
    single: Boolean,
    onChange: (Set<String>) -> Unit,
) {
    val c = Hypurr.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val on = opt in selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) c.accent else c.bg.copy(alpha = 0.7f))
                    .pressable(opt) {
                        onChange(
                            when {
                                single -> setOf(opt)
                                on -> selected - opt
                                else -> selected + opt
                            },
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(opt, color = if (on) c.onAccent else c.text, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
