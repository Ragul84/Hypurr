package com.ragul84.hypurr.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.setupProgress
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The real Bots screen (not just the helper) shows the paired + installed state, on a small phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class EmptyRosterWiringTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pairedAndInstalledShowsSetUp() {
        val agent = Backend(id = "hypurr-agent", builtin = true, installed = true, available = true)
        val hello = Hello(hostId = "h", name = "raavan", os = "windows", builtinAgent = agent, backends = listOf(agent))
        compose.setContent {
            HypurrTheme(ThemeMode.Dark) {
                BotListScreen("raavan", LinkState.Ready(Route.Direct), emptyList(), synced = true,
                    onOpen = {}, onSettings = {}, onRetry = {}, onNewTask = {}, hostOs = "windows",
                    setupProgress = setupProgress(hello))
            }
        }
        compose.onNodeWithText("You're set up").assertIsDisplayed()
        compose.onNodeWithText("Paired with raavan").assertExists()
        compose.onNodeWithText("Start a task").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pair with QR").assertDoesNotExist()
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-empty-roster-ready-small.png").absolutePath)
    }
}
