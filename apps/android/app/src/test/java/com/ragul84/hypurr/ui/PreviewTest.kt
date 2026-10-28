package com.ragul84.hypurr.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Live preview: a button per dev server the task started; only that server goes over the channel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class PreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyThePreviewedServerIsIntercepted() {
        assertTrue(isPreviewRequest("localhost", 5173, 5173))
        assertTrue(isPreviewRequest("127.0.0.1", 5173, 5173))
        assertFalse(isPreviewRequest("localhost", 5173, 3000))
        assertFalse(isPreviewRequest("fonts.googleapis.com", 5173, 443))
    }

    @Test fun chatShowsAPreviewButton() {
        var opened = 0
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            ChatScreen(Bot(id = "b", name = "Landing page"), emptyList(), "", {}, {}, {}, {}, { _, _ -> },
                previews = listOf(5173), onOpenPreview = { opened = it })
        } }
        compose.onNodeWithText("Preview localhost:5173").assertIsDisplayed().performClick()
        assertEquals(5173, opened)
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-chat-live-preview.png").absolutePath)
    }
}
