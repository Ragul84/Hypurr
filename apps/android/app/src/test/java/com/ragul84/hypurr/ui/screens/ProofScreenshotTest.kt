package com.ragul84.hypurr.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Proof
import com.ragul84.hypurr.model.ProofChange
import com.ragul84.hypurr.model.ProofPlan
import com.ragul84.hypurr.model.ProofShot
import com.ragul84.hypurr.model.ProofTests
import com.ragul84.hypurr.model.ProofVerdict
import com.ragul84.hypurr.ui.Samples
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Stage 8: "Show me it works" proof card in a task's chat. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ProofScreenshotTest {
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

    /** A pretend pricing page: the change made the button green and added a "Save 20%" banner. */
    private fun page(after: Boolean): ImageBitmap {
        val w = 412; val h = 860
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        cv.drawColor(0xFFFAFAF7.toInt())
        p.color = 0xFF111111.toInt(); p.textSize = 34f; p.isFakeBoldText = true
        cv.drawText("Sock Shop", 28f, 70f, p)
        p.isFakeBoldText = false; p.textSize = 20f; p.color = 0xFF666666.toInt()
        cv.drawText("Pricing", 28f, 104f, p)
        var y = 140f
        if (after) {
            p.color = 0xFFFFE9A8.toInt(); cv.drawRoundRect(RectF(24f, y, w - 24f, y + 54f), 14f, 14f, p)
            p.color = 0xFF5A4300.toInt(); p.textSize = 20f; cv.drawText("Save 20% with yearly plans", 44f, y + 34f, p)
            y += 76f
        }
        for ((i, plan) in listOf("Basic  ₹199", "Pro  ₹499", "Team  ₹1,499").withIndex()) {
            p.color = 0xFFFFFFFF.toInt(); cv.drawRoundRect(RectF(24f, y, w - 24f, y + 150f), 18f, 18f, p)
            p.color = 0xFFE3E3DE.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            cv.drawRoundRect(RectF(24f, y, w - 24f, y + 150f), 18f, 18f, p); p.style = Paint.Style.FILL
            p.color = 0xFF111111.toInt(); p.textSize = 24f; cv.drawText(plan, 44f, y + 44f, p)
            p.color = 0xFF777777.toInt(); p.textSize = 17f; cv.drawText("Everything in ${if (i == 0) "Free" else "the plan before"}", 44f, y + 74f, p)
            p.color = if (after) 0xFF1F8F4E.toInt() else 0xFF2D5BD7.toInt()
            cv.drawRoundRect(RectF(44f, y + 92f, if (after) w - 44f else 210f, y + 132f), 20f, 20f, p)
            p.color = 0xFFFFFFFF.toInt(); p.textSize = 17f; cv.drawText(if (after) "Start free trial" else "Buy", 64f, y + 118f, p)
            y += 170f
        }
        return b.asImageBitmap()
    }

    private val images by lazy { mapOf("before-0.png" to page(false), "after-0.png" to page(true)) }

    private val web = Proof(
        status = "ready", kind = "web", plan = ProofPlan("web", "Start the web app", "npm run dev"),
        shots = listOf(ProofShot("/pricing", "before-0.png", "after-0.png"), ProofShot("/", "before-1.png", "after-1.png")),
        clip = "after-clip.webm",
        summary = "Changed 2 files (38 lines added, 6 removed). Most work in app/pricing/page.tsx (+31 −6), app/globals.css (+7 −0).",
        files = listOf(ProofChange("app/pricing/page.tsx", 31, 6), ProofChange("app/globals.css", 7, 0)),
    )

    private val tests = Proof(
        status = "ready", kind = "tests", plan = ProofPlan("tests", "Run the project's tests", "cargo test"),
        tests = ProofTests(ok = false, passed = 46, failed = 1, command = "cargo test", seconds = 41,
            output = "test parse::handles_empty ... ok\ntest parse::rejects_bad_date ... FAILED\n\nfailures:\n    parse::rejects_bad_date\n\ntest result: FAILED. 46 passed; 1 failed"),
        summary = "Changed 3 files (54 lines added, 12 removed). Most work in src/parse.rs (+40 −10), tests/parse.rs (+12 −0), README.md (+2 −2).",
    )

    private val task = Samples.task.copy(status = "done")

    @androidx.compose.runtime.Composable
    private fun chat(card: @androidx.compose.runtime.Composable () -> Unit) {
        ChatScreen(Samples.taskBot.copy(task = task, status = "idle"), Samples.taskChat, "", {}, onBack = {}, onSend = {}, onStop = {},
            onRespond = { _, _ -> }, now = Samples.NOW, proof = card)
    }

    @Test fun showMe() = shoot("proof-show-me") { chat { ProofCard(null) } }

    @Test fun approval() = shoot("proof-approval") {
        chat { ProofCard(Proof(status = "needsApproval", plan = ProofPlan("web", "Start the web app (yours and the original) for a few minutes and capture the changed pages", "npm run dev"))) }
    }

    @Test fun running() = shoot("proof-running") {
        chat { ProofCard(Proof(status = "running", plan = ProofPlan("web", "Start the web app (yours and the original) for a few minutes and capture the changed pages", "npm run dev"))) }
    }

    @Test fun webSlider() = shoot("proof-web") { ProofCardAlone { ProofCard(web, images, initialSplit = 0.42f) } }

    @Test fun webInChat() = shoot("proof-web-chat") { chat { ProofCard(web, images, initialSplit = 0.5f) } }

    @Test fun fixIt() = shoot("proof-fix") { ProofCardAlone { ProofCard(web, images, initialFixing = true, initialFeedback = "The trial button is too wide on the Basic plan") } }

    @Test fun testsOnly() = shoot("proof-tests") { ProofCardAlone { ProofCard(tests) } }

    @Test fun androidDevice() = shoot("proof-android") {
        ProofCardAlone {
            ProofCard(Proof(status = "ready", kind = "android", shots = listOf(ProofShot("On the phone", null, "after-0.png")), clip = "after-clip.mp4",
                summary = "Changed 1 file (12 lines added, 3 removed). Most work in app/src/main/java/ui/Cart.kt (+12 −3).",
                verdict = ProofVerdict(ok = true)), images)
        }
    }

    @Test fun failed() = shoot("proof-failed") {
        ProofCardAlone {
            ProofCard(Proof(status = "failed", kind = "web", note = "The pages couldn't be captured. See the log.",
                summary = "Changed 1 file (4 lines added, 1 removed). Most work in app/page.tsx (+4 −1).",
                log = "after: the dev server didn't start within 90 seconds."))
        }
    }

    @Test fun fixItLongNote() {
        shoot("proof-fix-long") { chat { ProofCard(web, images, initialFixing = true, initialFeedback = List(12) { "Line ${it + 1}: the trial button is too wide and the price wraps." }.joinToString("\n")) } }
        compose.onNodeWithText("Send to agent").assertIsDisplayed()
    }

    @Test fun testsPassed() = shoot("proof-tests-passed") { ProofCardAlone { ProofCard(tests.copy(tests = tests.tests!!.copy(ok = true, failed = 0))) } }

    @Test fun failedTestsOfferOnlyFixIt() {
        assertEquals(listOf("Not right, fix it"), verdictActions(tests))
        assertEquals(listOf("Not right, fix it", "Looks right"), verdictActions(tests.copy(tests = tests.tests!!.copy(failed = 0))))
        assertEquals(listOf("Not right, fix it", "Looks right"), verdictActions(web))
    }

    @Test fun wording() {
        assertEquals("46 passed · 1 failed in 41s", testLine(46, 1, 41))
        assertEquals("3 passed in 2m 5s", testLine(3, 0, 125))
        assertEquals("All tests passed", proofTitle(tests.copy(tests = tests.tests!!.copy(failed = 0))))
        assertEquals("Some tests failed", proofTitle(tests))
        assertEquals("Your pages, before and after", proofTitle(web))
        assertEquals("Your app on the phone", proofTitle(Proof(status = "ready", kind = "android", shots = listOf(ProofShot("x", null, "a")))))
    }
}

@androidx.compose.runtime.Composable
private fun ProofCardAlone(content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.fillMaxSize().background(com.ragul84.hypurr.ui.theme.Hypurr.colors.bg)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
    ) { content() }
}
