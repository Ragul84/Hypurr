package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.ui.screens.OnboardingScreen
import com.ragul84.hypurr.ui.screens.ProjectFolderPickerScreen
import com.ragul84.hypurr.ui.screens.SettingsScreen
import com.ragul84.hypurr.ui.screens.SettingsUiState
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Signal: first-run onboarding steps, the You-tab profile editor and the project-folder picker,
 * light and dark. Written next to the other baselines as `android-<name>-<mode>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class OnboardingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "android-$name-${mode.name.lowercase()}.png").absolutePath)
        }
    }

    private val partial = UserProfile(role = "Tester/QA", experience = "New", stacks = listOf("Java", "JS/React"), goals = listOf("Write tests"))

    @Test fun step1Role() = shoot("onboarding-1-role") { OnboardingScreen(UserProfile(role = "Tester/QA"), initialStep = 0, onFinished = {}) }
    @Test fun step2Experience() = shoot("onboarding-2-experience") { OnboardingScreen(partial, initialStep = 1, onFinished = {}) }
    @Test fun step3Stack() = shoot("onboarding-3-stack") { OnboardingScreen(partial, initialStep = 2, onFinished = {}) }
    @Test fun step4Goals() = shoot("onboarding-4-goals") { OnboardingScreen(partial, initialStep = 3, onFinished = {}) }
    @Test fun step1Empty() = shoot("onboarding-1-role-empty") { OnboardingScreen(UserProfile(), onFinished = {}) }

    @Test fun profileEditor() = shoot("profile-editor") {
        OnboardingScreen(partial.copy(completed = true), editing = true, initialStep = 3, onFinished = {})
    }

    @Test fun youTabProfile() = shoot("you-tab-profile") {
        SettingsScreen(
            SettingsUiState(Samples.computer, LinkState.Ready(Route.Relay), ThemeMode.System, dynamicColor = false,
                notifications = true, pushAvailable = false, version = "2.4.0", dynamicSupported = true,
                profile = partial.copy(completed = true), projectFolder = "/Users/priya/shop"),
            {}, {}, {}, {}, {}, showHomeTabs = true,
        )
    }

    @Test fun youTabProfileEmpty() = shoot("you-tab-profile-empty") {
        SettingsScreen(
            SettingsUiState(Samples.computer, LinkState.Ready(Route.Relay), ThemeMode.System, dynamicColor = false,
                notifications = true, pushAvailable = false, version = "2.4.0", dynamicSupported = true,
                profile = UserProfile(completed = true, skipped = true)),
            {}, {}, {}, {}, {}, showHomeTabs = true,
        )
    }

    @Test fun folderPicker() = shoot("folder-picker") {
        ProjectFolderPickerScreen(Samples.setup.projects, browsing = null, selectedPath = "/Users/priya/shop",
            onBack = {}, onBrowse = {}, onSelect = {}, onConfirm = {})
    }

    @Test fun folderPickerBrowse() = shoot("folder-picker-browse") {
        ProjectFolderPickerScreen(emptyList(), browsing = Samples.dirs, selectedPath = "/Users/kevin/code/hypurr",
            subtitle = "Which folder should agents work in? You can change it anytime under You.",
            onBack = {}, onBrowse = {}, onSelect = {}, onConfirm = {}, onLater = {})
    }

    @Test fun chatHeaderFolder() = shoot("chat-header-folder") {
        ChatScreen(Samples.taskBot, Samples.taskChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            now = Samples.NOW, onChangeFolder = {})
    }

    @Test fun newTaskFolder() = shoot("newtask-folder") {
        NewTaskScreen(NewTaskUiState(goal = "Fix the checkout tests", setup = Samples.setup, project = "/Users/kevin/code/hypurr"),
            {}, {}, {})
    }
}
