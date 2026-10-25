package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** "Start free with your own account": the card, the chat notice's action, and the connect dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class StartFreeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }

    @Test fun startFreeCardIsTheFreePathWithCredits() {
        var opened = 0
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            CompositionLocalProvider(LocalStartFree provides { opened++ }) {
                Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(16.dp)) {
                    StartFreeNote(ownFree = false, onAddCredits = {})
                    StartFreeAction(NEEDS_ACCOUNT, Modifier.padding(top = 16.dp))
                }
            }
        } }
        compose.onAllNodesWithText(START_FREE).assertCountEquals(2)
        compose.onNodeWithText("Add credits").assertExists()
        compose.onNodeWithText("free messages", substring = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage(File(dir, "android-start-free-card.png").absolutePath)
        assertTrue(offersStartFree(NEEDS_ACCOUNT))
        assertTrue(offersStartFree("Free models aren't available yet. Start free with your own account, or pick Claude or Codex."))
        assertTrue(!offersStartFree("The agent failed: something else"))
    }

    @Test fun ownAccountShowsOneQuietLine() {
        compose.setContent { HypurrTheme(ThemeMode.Dark) {
            Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(16.dp)) { StartFreeNote(ownFree = true, onAddCredits = {}) }
        } }
        compose.onNodeWithText("Hypurr Free on your own account · not for company code").assertExists()
        compose.onRoot().captureRoboImage(File(dir, "android-start-free-connected.png").absolutePath)
    }

    @Test fun dialogExplainsThenTakesThePastedCode() {
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            StartFreeDialog(start = { "" }, finish = {}, onDone = {}, onDismiss = {})
        } }
        compose.onNodeWithText(NOT_FOR_COMPANY_CODE).assertExists()
        compose.onNodeWithText("Open OpenRouter").assertExists()
        compose.onNodeWithText(START_FREE).assertExists()
        compose.onNode(isDialog()).captureRoboImage(File(dir, "android-start-free-dialog.png").absolutePath)
    }
}
