package com.ragul84.hypurr.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.MemoryPersistence
import com.ragul84.hypurr.data.RatePolicy
import com.ragul84.hypurr.data.ThemeMode
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class RateHypurrTest {
    @get:Rule val compose = createComposeRule()

    @Test fun asksAfterThirdTaskThenWaits() {
        var t = 1_000L
        val p = RatePolicy(MemoryPersistence()) { t }
        assertFalse(p.taskFinished("a"))
        assertFalse(p.taskFinished("b"))
        assertFalse(p.taskFinished("b"))
        assertTrue(p.taskFinished("c"))
        p.asked()
        assertFalse(p.taskFinished("d"))
        t += RatePolicy.GAP_MS
        assertTrue(p.taskFinished("e"))
        p.rated()
        t += RatePolicy.GAP_MS
        assertFalse(p.taskFinished("f"))
    }

    @Test fun twoNoThanksMeansNever() {
        var t = 0L
        val p = RatePolicy(MemoryPersistence()) { t }
        listOf("a", "b").forEach { p.taskFinished(it) }
        assertTrue(p.taskFinished("c")); p.asked(); p.declined()
        t += RatePolicy.GAP_MS
        assertTrue(p.taskFinished("d")); p.asked(); p.declined()
        t += RatePolicy.GAP_MS
        assertFalse(p.taskFinished("e"))
    }

    @Test fun cardButtons() {
        var taps = ""
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            Box(Modifier.fillMaxSize()) {
                RateHypurrCard(onRate = { taps += "rate" }, onLater = { taps += "later" }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        } }
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-rate-hypurr.png").absolutePath)
        compose.onNodeWithText("Rate Hypurr").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals("ratelater", taps)
    }
}
