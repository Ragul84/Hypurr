package com.ragul84.hypurr.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.AskAnswer
import com.ragul84.hypurr.model.AskSource
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.EntryData
import com.ragul84.hypurr.model.GuideDoc
import com.ragul84.hypurr.model.LearnCard
import com.ragul84.hypurr.model.Quiz
import com.ragul84.hypurr.model.QuizQuestion
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.Samples
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Stage 7: Learn this project (guide, handover, ask) and learning mode in a task. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class LearnScreenshotTest {
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

    private val folder = "/Users/priya/work/shop-web"

    private val guideMd = """
        # Project guide: shop-web

        ## What it does
        The web shop customers use to browse socks, fill a cart and pay.

        ## How to run and test it
        - `package.json`: npm install, then npm run dev (opens http://localhost:3000)
        - Tests: npm test (Jest), in `tests/`

        ## Map of the code
        - `src/pages/`: one file per screen
        - `src/api/`: talks to the payments and stock services
        - `src/components/`: shared buttons, forms and the cart drawer

        ## Where to start for common changes
        - A new screen: copy `src/pages/about.tsx`
        - Checkout bugs: `src/api/checkout.ts` (changed 9 times in 90 days)

        ## People and owners
        - Priya Raman (142 commits), Arjun Mehta (87 commits)
    """.trimIndent()

    private val guide = GuideUiState(folder = folder, docs = mapOf("guide" to GuideDoc(guideMd, "Project guide: shop-web", "guide", "agent",
        dropped = listOf("src/payments/stripe.ts", "docs/setup.md"))))

    private val handover = GuideUiState(folder = folder, tab = GuideTab.Handover, docs = mapOf("handover" to GuideDoc("""
        # Handover: shop-web

        ## Recent work
        - 3f2a1c9 2026-10-18 Priya Raman: Fix the checkout button on Safari
        - 8b71d02 2026-10-16 Arjun Mehta: Add UPI as a payment option

        ## Open branches and PRs
        - hypurr/fix-safari-checkout (2026-10-18, Priya Raman)
        - feature/upi (2026-10-16, Arjun Mehta)

        ## Risky areas
        - `src/api/checkout.ts`: changed 9 times in 90 days, few tests

        ## How to deploy
        - `.github/workflows/deploy.yml` deploys main to production on every merge
    """.trimIndent(), "Handover: shop-web", "handover", "agent")))

    private val ask = GuideUiState(folder = folder, tab = GuideTab.Ask, asked = "Where is the cart total calculated?",
        answer = AskAnswer("The total is added up in `src/api/cart.ts:42` (`cartTotal`), which sums each item's price × quantity and then applies the coupon from `src/api/coupons.ts:18`. The cart drawer shows it via `src/components/CartDrawer.tsx:63`.",
            sources = listOf(AskSource("src/api/cart.ts", 42, "export function cartTotal(items: Item[], coupon?: Coupon) {"),
                AskSource("src/api/coupons.ts", 18, "export const applyCoupon = (total: number, c?: Coupon) =>"),
                AskSource("src/components/CartDrawer.tsx", 63, "<Total value={cartTotal(items, coupon)} />")), by = "agent"))

    private val cards = listOf(
        LearnCard("async", "Async code", "Async code starts slow work (a network call, a file read) and carries on; `await` pauses just that piece until the result is ready.",
            "const res = await fetch('/api/cart')", "src/api/cart.ts", 12),
        LearnCard("test", "Automated tests", "A test is a small program that runs your code and checks the result.", "test('checkout button submits the order', async () => {",
            "tests/checkout.test.js", 4),
        LearnCard("agent:Mocking", "Mocking", "Mocking – replacing the real payment call with a fake so the test runs offline"),
    )

    private val quiz = Quiz(listOf(
        QuizQuestion("“Write tests: the checkout button” changed which of these files?", listOf("tests/checkout.test.js", "README.md", "No files changed"), 0,
            "tests/checkout.test.js is in this task's changes."),
        QuizQuestion("In src/api/cart.ts:12 the code uses async code. What is it for?",
            listOf("It runs the code and checks the result automatically", "It lets slow work finish without freezing everything else", "It reuses code that lives in another file"), 1,
            "Async code starts slow work and carries on; await pauses just that piece until the result is ready."),
        QuizQuestion("Why did Hypurr do this task on its own branch?", listOf("To make the app run faster", "Git needs a new branch for every file", "So the work stays separate until you review it"), 2,
            "Hypurr does each task on its own branch so main stays safe until you review and merge."),
    ), "agent")

    private val learningTask = Samples.task.copy(learning = true)

    @Test fun guideEmpty() = shoot("learn-guide-empty") { ProjectGuideScreen(GuideUiState(folder = folder), {}) }

    @Test fun guideDoc() = shoot("learn-guide") { ProjectGuideScreen(guide, {}) }

    @Test fun guideEdit() = shoot("learn-guide-edit") { ProjectGuideScreen(guide.copy(editing = true), {}) }

    @Test fun guideSave() = shoot("learn-guide-save") { ProjectGuideScreen(guide.copy(confirmSave = true), {}) }

    @Test fun handoverDoc() = shoot("learn-handover") { ProjectGuideScreen(handover, {}) }

    @Test fun askAnswer() = shoot("learn-ask") { ProjectGuideScreen(ask, {}) }

    @Test fun teachMe() = shoot("learn-teach") {
        ChatScreen(Samples.taskBot.copy(task = learningTask, status = "working"), Samples.taskChat.dropLast(1), "", {}, onBack = {}, onSend = {}, onStop = {},
            onRespond = { _, _ -> }, now = Samples.NOW,
            learn = { LearnPanel(learningTask, cards, null, initialOpen = "async") })
    }

    @Test fun quizDone() = shoot("learn-quiz") {
        val done = learningTask.copy(status = "done")
        ChatScreen(Samples.taskBot.copy(task = done, status = "idle"), Samples.taskChat.dropLast(1), "", {}, onBack = {}, onSend = {}, onStop = {},
            onRespond = { _, _ -> }, now = Samples.NOW,
            learn = { LearnPanel(done, cards, quiz, initialPicks = mapOf(0 to 0, 1 to 2)) })
    }

    @Test fun tasksEntry() = shoot("learn-tasks-entry") {
        TasksScreen(Samples.computer.name, LinkState.Ready(Route.Direct), Samples.templates, recent = emptyList(), now = Samples.NOW)
    }

    @Test fun teachLinesComeFromTheAgent() {
        val e = Entry(id = "e", seq = 1, botId = "b", kind = "agent", createdAt = 0, updatedAt = 0,
            data = EntryData(text = "Adding the test now.\nTeach me: `mocking` – a fake payment call so tests run offline\n- Teach me: async code"))
        val cards = teachLines(listOf(e, e.copy(kind = "user")))
        assertEquals(listOf("Mocking", "Async code"), cards.map { it.concept })
    }
}
