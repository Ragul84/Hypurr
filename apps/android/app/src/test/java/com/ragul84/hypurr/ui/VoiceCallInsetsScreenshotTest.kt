package com.ragul84.hypurr.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.model.DirEntry
import com.ragul84.hypurr.model.Proof
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.ProjectFolderPickerScreen
import com.ragul84.hypurr.ui.screens.ProofCard
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Edge-to-edge check: real system-bar insets (status bar plus gesture or 3-button nav) are dispatched
 * to the window, and the bars are painted translucent on top, so anything drawn under them shows.
 * Shots: android-insets-<screen>-<small|large>-<gesture|buttons>.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class VoiceCallInsetsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir get() = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }

    private fun shoot(name: String, nav: Dp, then: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.setContent {
            HypurrTheme(ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    Bars(nav)
                }
            }
        }
        val px = { d: Dp -> (d.value * compose.activity.resources.displayMetrics.density).toInt() }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(24.dp), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(nav)))
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets) }
        compose.waitForIdle()
        then()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "android-insets-$name.png").absolutePath)
    }

    @Composable
    private fun BoxScope.Bars(nav: Dp) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp).background(Color(0x66FF00FF)))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(nav).background(Color(0x66FF00FF)))
    }

    private fun call(name: String, nav: Dp) = shoot(name, nav) {
        com.ragul84.hypurr.voice.VoiceCallScreen(Samples.taskBot.copy(name = "Write tests: the checkout button on small phones"),
            emptyList(), com.ragul84.hypurr.voice.VoiceLanguage.entries.first(), onSend = {}, onRespond = { _, _ -> }, onEnd = {})
    }

    @Test fun callSmallGesture() = call("voice-call-small-gesture", 24.dp)
    @Test fun callSmallButtons() = call("voice-call-small-buttons", 48.dp)
    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") fun callLargeGesture() = call("voice-call-large-gesture", 24.dp)
    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") fun callLargeButtons() = call("voice-call-large-buttons", 48.dp)
}
