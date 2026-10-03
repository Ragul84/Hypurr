package com.ragul84.hypurr.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.PairingScreen
import com.ragul84.hypurr.ui.screens.PairingUiState
import com.ragul84.hypurr.ui.screens.SettingsScreen
import com.ragul84.hypurr.ui.screens.SettingsUiState
import com.ragul84.hypurr.ui.screens.rosterOrder
import com.ragul84.hypurr.ui.theme.HypurrTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose screenshots of the key screens in light and dark (Roborazzi on Robolectric, native graphics).
 * `./gradlew recordRoborazziDebug` writes them to apps/android/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-$name-${mode.name.lowercase()}.png")
        }
    }

    @Test
    fun pairing() = shoot("pairing") { PairingScreen(PairingUiState(), {}, {}, {}, {}) }

    @Test
    fun botList() = shoot("bots") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), rosterOrder(Samples.bots), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW)
    }

    @Test
    fun chat() = shoot("chat") {
        ChatScreen(Samples.reviewer, Samples.chat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> })
    }

    @Test
    fun settings() = shoot("settings") {
        SettingsScreen(SettingsUiState(Samples.computer, LinkState.Ready(Route.Relay), ThemeMode.System, dynamicColor = false,
            notifications = true, pushAvailable = false, version = "2.4.0", dynamicSupported = true), {}, {}, {}, {}, {})
    }
}
