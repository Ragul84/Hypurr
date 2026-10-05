package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.ui.motion.AllowedChip
import com.ragul84.hypurr.ui.motion.AllowPressEffect
import com.ragul84.hypurr.ui.motion.ApprovalDropIn
import com.ragul84.hypurr.ui.motion.BotCreatePop
import com.ragul84.hypurr.ui.motion.DenyHeadshake
import com.ragul84.hypurr.ui.motion.HappyDoneStamp
import com.ragul84.hypurr.ui.motion.PairingSuccessFlip
import com.ragul84.hypurr.ui.motion.ThinkingCatRow
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.HypurrTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real Compose motion frames for the Sunfield showreel.
 * `./gradlew :app:testDebugUnitTest --tests '*.SunfieldMotionShowreelTest' -Proborazzi.test.record=true`
 * writes PNGs under the Roborazzi output dir; assemble with ffmpeg into v3/showreel.mp4.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class SunfieldMotionShowreelTest {
    @get:Rule val compose = createComposeRule()

    private val outDir: File
        get() = File(System.getProperty("roborazzi.output.dir") ?: "screenshots").resolve("motion").also { it.mkdirs() }

    private fun captureMoment(slug: String, frames: Int, stepMs: Long = 16L, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HypurrTheme(ThemeMode.Light) {
                Box(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(24.dp), contentAlignment = Alignment.Center) {
                    content()
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        for (i in 0 until frames) {
            compose.waitForIdle()
            val name = String.format("%s_%03d.png", slug, i)
            compose.onRoot().captureRoboImage(File(outDir, name).absolutePath)
            compose.mainClock.advanceTimeBy(stepMs, ignoreFrameDuration = true)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun thinkingCat() = captureMoment("thinking", 48) {
        ThinkingCatRow("Thinking…", animate = true)
    }

    @Test
    fun approvalDropIn() = captureMoment("approval", 36) {
        ApprovalDropIn(Modifier.fillMaxWidth()) {
            CreamPlate(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Run cargo test?", fontSize = 18.sp, color = Hypurr.colors.text)
                    Text("shop · Kevin's Mac Studio", color = Hypurr.colors.secondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    @Test
    fun allowPressAllowed() = captureMoment("allow", 40) {
        var striking by remember { mutableStateOf(false) }
        var allowed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(120)
            striking = true
        }
        if (allowed) {
            AllowedChip()
        } else {
            AllowPressEffect(striking = striking, onSettled = {
                if (striking) {
                    striking = false
                    allowed = true
                }
            }) {
                Box(
                    Modifier
                        .background(Hypurr.colors.accent, RoundedCornerShape(14.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text("Allow once", color = Hypurr.colors.onAccent, fontSize = 15.sp)
                }
            }
        }
    }

    @Test
    fun denyHeadshake() = captureMoment("deny", 36) {
        var active by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(80)
            active = true
        }
        DenyHeadshake(active) {
            Text("Deny", color = Hypurr.colors.secondary, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
        }
    }

    @Test
    fun happyDone() = captureMoment("done", 36) {
        var vis by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            vis = true
        }
        HappyDoneStamp(vis)
    }

    @Test
    fun botCreatePop() = captureMoment("botcreate", 60) {
        BotCreatePop("Shop fixer", play = true)
    }

    @Test
    fun pairingFlip() = captureMoment("pairing", 36) {
        var ok by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(100)
            ok = true
        }
        PairingSuccessFlip(success = ok) {
            Box(
                Modifier
                    .background(Hypurr.colors.surface, RoundedCornerShape(20.dp))
                    .padding(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                FlowOrb(64.dp)
            }
        }
    }
}
