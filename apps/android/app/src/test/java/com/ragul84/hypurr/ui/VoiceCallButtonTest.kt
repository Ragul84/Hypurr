package com.ragul84.hypurr.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertTrue
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
class VoiceCallButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chatHeaderStartsAVoiceChat() {
        var started = false
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            ChatScreen(Bot(id = "b", name = "Landing page"), emptyList(), "", {}, {}, {}, {}, { _, _ -> },
                onEdit = {}, onVoiceCall = { started = true })
        } }
        compose.onNodeWithContentDescription("Voice chat").performClick()
        assertTrue(started)
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-chat-voice-call-button.png").absolutePath)
    }
}
