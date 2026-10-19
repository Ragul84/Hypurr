package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.motion.AllowPressEffect
import com.ragul84.hypurr.ui.motion.AllowedChip
import com.ragul84.hypurr.ui.motion.CatAssemble
import com.ragul84.hypurr.ui.motion.DenyNudge
import com.ragul84.hypurr.ui.motion.DoneStamp
import com.ragul84.hypurr.ui.motion.PairingSuccess
import com.ragul84.hypurr.ui.screens.BotEditorScreen
import com.ragul84.hypurr.ui.screens.BotEditorState
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NeedsYouAsk
import com.ragul84.hypurr.ui.screens.rosterOrder
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.HypurrTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real Compose UI frames for the Signal motion reel, 16ms apart (60fps).
 * Off by default; run with `HYPURR_REEL=/abs/out/dir ./gradlew :app:testDebugUnitTest --tests '*.SignalMotionReelTest'
 * -Proborazzi.test.record=true`, then `scripts/signal-reel.sh` assembles the MP4.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = android.app.Application::class)
class SignalMotionReelTest {
    @get:Rule val compose = createComposeRule()

    private val outDir: File? get() = System.getenv("HYPURR_REEL")?.let { File(it).also(File::mkdirs) }

    private fun reel(slug: String, frames: Int, mode: ThemeMode = ThemeMode.Light, content: @Composable () -> Unit) {
        val dir = outDir
        assumeTrue("set HYPURR_REEL to record the reel", dir != null)
        compose.mainClock.autoAdvance = false
        compose.setContent { HypurrTheme(mode) { content() } }
        compose.mainClock.advanceTimeByFrame()
        for (i in 0 until frames) {
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, String.format("%s_%04d.png", slug, i)).absolutePath)
            compose.mainClock.advanceTimeBy(16L, ignoreFrameDuration = true)
        }
        compose.mainClock.autoAdvance = true
    }

    /** Home: the needs-you ring breathing, the running bot's dot-matrix thinking pulse. */
    @Test fun a_home() = reel("a_home", 120) {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), rosterOrder(Samples.bots), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW,
            asks = mapOf("b1" to NeedsYouAsk(title = "Wants to run the host tests", entryId = "e-perm")))
    }

    /** Chat: the agent thinks, then the approval card rises in. */
    @Test fun b_approval() = reel("b_approval", 96) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(520); shown = true }
        val entries = if (shown) Samples.taskChat else Samples.taskChat.dropLast(1)
        ChatScreen(Samples.taskBot.copy(status = if (shown) "needsInput" else "working", activity = "Reading the build folder"), entries, "", {},
            onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> }, now = Samples.NOW)
    }

    /** Allow once presses in and settles to Allowed; Deny nudges and dims. */
    @Test fun c_decide() = reel("c_decide", 84) {
        var striking by remember { mutableStateOf(false) }
        var allowed by remember { mutableStateOf(false) }
        var deny by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(260); striking = true
            kotlinx.coroutines.delay(520); deny = true
        }
        Stage {
            Column(Modifier.fillMaxWidth()) {
                SignalCard(Modifier.fillMaxWidth(), emphasis = true) {
                    Column(Modifier.padding(16.dp)) {
                        Text("The agent wants to delete build/", color = Hypurr.colors.text, style = MaterialTheme.typography.titleLarge)
                        Box(Modifier.padding(top = 14.dp)) {
                            if (allowed) AllowedChip()
                            else AllowPressEffect(striking, onSettled = { if (striking) { striking = false; allowed = true } }) {
                                Box(Modifier.background(Hypurr.colors.accent, androidx.compose.foundation.shape.RoundedCornerShape(50))
                                    .padding(horizontal = 22.dp, vertical = 12.dp)) {
                                    Text("Allow once", color = Hypurr.colors.onAccent, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.padding(top = 16.dp)) {
                    DenyNudge(deny) { OutlineButton("Deny", Modifier.padding(0.dp)) {} }
                }
            }
        }
    }

    /** New bot: the cat's dots fly in and lock to the grid, then the name types. */
    @Test fun d_create() = reel("d_create", 84) {
        BotEditorScreen(BotEditorState(name = "Website fixes", backend = "codex"), Samples.backends, emptyList(), {}, {}, {}, {}, {})
    }

    /** Done and paired: the cat squints, the dotted rule fills. */
    @Test fun e_done() = reel("e_done", 60) {
        var vis by remember { mutableStateOf(false) }
        var ok by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); vis = true; kotlinx.coroutines.delay(300); ok = true }
        Stage {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PairingSuccess(ok) { CatAssemble(playing = true, size = 120.dp) }
                Box(Modifier.padding(top = 28.dp)) { DoneStamp(vis) }
            }
        }
    }

    /** Dark mode: same home, true black. */
    @Test fun f_dark() = reel("f_dark", 72, ThemeMode.Dark) {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), rosterOrder(Samples.bots), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW,
            asks = mapOf("b1" to NeedsYouAsk(title = "Wants to run the host tests", entryId = "e-perm")))
    }
}

@Composable
private fun Stage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(28.dp), contentAlignment = Alignment.Center) { content() }
}
