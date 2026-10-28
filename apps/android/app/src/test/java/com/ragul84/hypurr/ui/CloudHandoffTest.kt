package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class CloudHandoffTest {
    @get:Rule val compose = createComposeRule()

    private val opts = CloudOptions(
        vendors = listOf(
            CloudVendor("codex", "Codex cloud", ready = true, needs = "the name of your Codex cloud environment for this repo"),
            CloudVendor("claude", "Claude Code on the web", ready = true),
            CloudVendor("cursor", "Cursor cloud agent", ready = false, needs = "a Cursor API key"),
        ),
        repo = "https://github.com/Ragul84/Hypurr", branch = "main", unpushed = true,
    )
    private val fake = object : CloudHandoffActions {
        override suspend fun options() = opts
        override suspend fun handOff(vendor: String, prompt: String, codexEnv: String?) = "https://chatgpt.com/codex/tasks/t1"
        override suspend fun saveCursorKey(key: String) {}
    }

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

    @Test fun listsCloudsAndWarnsAboutUnpushedWork() {
        compose.setContent { HypurrTheme(ThemeMode.Light) { CloudHandoffForm("Fix the login bug", fake, opts) } }
        compose.onNodeWithText("Push your latest changes first", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Claude Code on the web").performClick()
        compose.onNodeWithText("Start in the cloud").assertIsDisplayed()
    }

    @Test fun shotChoose() = shoot("cloud-handoff") { CloudHandoffForm("Fix the login bug", fake, opts) }
}
