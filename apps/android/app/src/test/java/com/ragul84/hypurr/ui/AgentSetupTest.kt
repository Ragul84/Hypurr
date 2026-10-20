package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.ui.screens.AgentInstallState
import com.ragul84.hypurr.ui.screens.AgentSetupMode
import com.ragul84.hypurr.ui.screens.AgentSetupPanel
import com.ragul84.hypurr.ui.screens.agentSetupMode
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** "Install Hypurr Agent" never does nothing: pair steps, Windows agent list, or one-tap install with progress. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class AgentSetupTest {
    @get:Rule val compose = createComposeRule()

    private val win = Hello(hostId = "h", name = "RAGUL-PC", os = "windows", backends = listOf(
        Backend("claude", "Claude Code", installed = true, signedIn = true),
        Backend("codex", "Codex", installed = true, signedIn = false),
        Backend("gemini", "Gemini CLI", installed = false, available = false),
    ), builtinAgent = Backend("hypurr-agent", available = false, installed = false))
    private val linux = Hello(hostId = "h", name = "devbox", os = "linux", builtinAgent = Backend("hypurr-agent", installed = false, available = true))

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

    @Test fun modes() {
        assertEquals(AgentSetupMode.NotPaired, agentSetupMode(null))
        assertEquals(AgentSetupMode.Windows, agentSetupMode(win))
        assertEquals(AgentSetupMode.OneTap, agentSetupMode(linux))
        assertEquals(AgentSetupMode.OneTap, agentSetupMode(linux.copy(os = "macos")))
    }

    @Test fun notPairedPairs() {
        var paired = false
        compose.setContent { HypurrTheme(ThemeMode.Light) { AgentSetupPanel(null, onPair = { paired = true }) } }
        compose.onAllNodesWithText("Pair with your PC").onLast().assertIsDisplayed()
        compose.onNodeWithText("download hypurr-host", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Pair with your PC").onLast().performClick()
        assertTrue(paired)
    }

    @Test fun windowsListsAgents() {
        var checked = false
        compose.setContent { HypurrTheme(ThemeMode.Light) { AgentSetupPanel(win, onCheckAgain = { checked = true }) } }
        compose.onNodeWithText("coming soon", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sign in needed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("npm install -g @google/gemini-cli").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check again").performClick()
        assertTrue(checked)
    }

    @Test fun oneTapShowsProgressAndReason() {
        var state by mutableStateOf(AgentInstallState())
        compose.setContent { HypurrTheme(ThemeMode.Light) { AgentSetupPanel(linux, state, onInstall = { state = AgentInstallState(busy = true) }) } }
        compose.onNodeWithContentDescription("Install status").assertIsDisplayed()
        compose.onNodeWithText("Not installed yet.").assertIsDisplayed()
        compose.onAllNodesWithText("Install Hypurr Agent").onLast().performClick()
        compose.onNodeWithText("Installing…").assertIsNotEnabled()
        state = AgentInstallState(error = "no disk space")
        compose.onNodeWithText("Install failed: no disk space").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        state = AgentInstallState(done = true)
        compose.onNodeWithText("Installed. Hypurr Agent is ready.").assertIsDisplayed()
    }

    @Test fun shotNotPaired() = shoot("agent-setup-not-paired") { AgentSetupPanel(null) }
    @Test fun shotWindows() = shoot("agent-setup-windows") { AgentSetupPanel(win) }
    @Test fun shotOneTapFailed() = shoot("agent-setup-install-failed") { AgentSetupPanel(linux, AgentInstallState(error = "download blocked by the network")) }
    @Test fun shotOneTapBusy() = shoot("agent-setup-installing") { AgentSetupPanel(linux, AgentInstallState(busy = true)) }
}
