package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDialog
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

/** "Keep going free": the used-up card, the busy notice's action, and the connect dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class KeepGoingFreeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }

    @Test fun usedUpCardOffersKeepGoingFreeAndCredits() {
        var opened = 0
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            CompositionLocalProvider(LocalKeepGoingFree provides { opened++ }) {
                Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(16.dp)) {
                    FreeMessagesNote(0, 10, onAddCredits = {})
                    KeepGoingFreeAction(FREE_BUSY, Modifier.padding(top = 16.dp))
                }
            }
        } }
        compose.onNodeWithText(FREE_MESSAGES_EXHAUSTED).assertExists()
        compose.onNodeWithText("Add credits").assertExists()
        compose.onRoot().captureRoboImage(File(dir, "android-keep-going-free-used-up.png").absolutePath)
        assertTrue(offersKeepGoingFree(FREE_BUSY))
        assertTrue(!offersKeepGoingFree("The agent failed: something else"))
    }

    @Test fun ownAccountShowsOneQuietLine() {
        compose.setContent { HypurrTheme(ThemeMode.Dark) {
            Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(16.dp)) { FreeMessagesNote(0, 10, onAddCredits = {}, ownFree = true) }
        } }
        compose.onNodeWithText("Hypurr Free on your own account · not for company code").assertExists()
        compose.onRoot().captureRoboImage(File(dir, "android-keep-going-free-connected.png").absolutePath)
    }

    @Test fun dialogExplainsThenTakesThePastedCode() {
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            KeepGoingFreeDialog(start = { "" }, finish = {}, onDone = {}, onDismiss = {})
        } }
        compose.onNodeWithText(NOT_FOR_COMPANY_CODE).assertExists()
        compose.onNodeWithText("Open OpenRouter").assertExists()
        compose.onNode(isDialog()).captureRoboImage(File(dir, "android-keep-going-free-dialog.png").absolutePath)
    }
}
