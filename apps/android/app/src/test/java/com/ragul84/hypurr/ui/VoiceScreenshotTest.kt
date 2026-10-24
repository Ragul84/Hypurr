package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.model.Agent
import com.ragul84.hypurr.model.PermissionOption
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.ui.screens.OnboardingScreen
import com.ragul84.hypurr.ui.screens.SettingsScreen
import com.ragul84.hypurr.ui.screens.SettingsUiState
import com.ragul84.hypurr.ui.screens.VoiceApproval
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.HypurrTheme
import com.ragul84.hypurr.voice.LocalVoiceSettings
import com.ragul84.hypurr.voice.VoiceLanguage
import com.ragul84.hypurr.voice.VoiceSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Indian-language voice on a small phone (360x640dp): mic on New task and approvals, picker in You and onboarding. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class VoiceScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val tamil = VoiceSettings(VoiceLanguage.Tamil, explainInMyLanguage = true)

    private fun shoot(name: String, voice: VoiceSettings = tamil, scrollTo: String? = null, content: @Composable () -> Unit) {
        compose.setContent {
            HypurrTheme(ThemeMode.Light) { CompositionLocalProvider(LocalVoiceSettings provides voice) { content() } }
        }
        compose.waitForIdle()
        scrollTo?.let { compose.onNodeWithText(it).performScrollTo(); compose.waitForIdle() }
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-voice-$name.png").absolutePath)
    }

    private val setup = TaskSetup(
        projects = listOf(Project("shop-web", "C:\\Users\\ragul\\work\\shop-web", saved = true)),
        agents = listOf(Agent("claude", "Claude Code")),
    )
    private val options = listOf(PermissionOption("allow", "Allow", "allow_once"), PermissionOption("reject", "Deny", "reject_once"))

    @Test fun newTaskMic() {
        shoot("new-task-small") {
            NewTaskScreen(NewTaskUiState(setup = setup, agent = "claude", project = setup.projects[0].path,
                goal = "லாகின் பட்டன் வேலை செய்யவில்லை, அதை சரி செய்", spoken = true), onChange = {}, onStart = {}, onBack = {})
        }
        compose.onNodeWithText("Say it in Tamil").assertIsDisplayed()
    }

    @Test fun approvalMic() = shoot("approval-small") {
        ChatScreen(Samples.taskBot, Samples.taskChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> }, now = Samples.NOW)
    }

    @Test fun riskyNeedsTapToConfirm() {
        shoot("approval-confirm-small", VoiceSettings(VoiceLanguage.Hindi)) {
            Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceApproval("e1", "high", options, initialConfirm = "haan kar do") {}
            }
        }
        compose.onNodeWithText("Yes, allow").assertIsDisplayed()
    }

    @Test fun youTabVoice() = shoot("you-tab-small", scrollTo = "Explain in my language") {
        SettingsScreen(
            SettingsUiState(Samples.computer, LinkState.Ready(Route.Relay), ThemeMode.System, dynamicColor = false,
                notifications = true, pushAvailable = false, version = "2.6.0", dynamicSupported = true, voice = tamil),
            {}, {}, {}, {}, {}, showHomeTabs = true,
        )
    }

    @Test fun onboardingVoiceStep() = shoot("onboarding-small") {
        OnboardingScreen(UserProfile(role = "Developer", voiceLanguage = "ta-IN", explainInMyLanguage = true), initialStep = 4, onFinished = {})
    }

    @Test fun readAloud() = shoot("read-aloud-small") {
        ChatScreen(Samples.workBot, Samples.workChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> }, now = Samples.NOW)
    }
}
