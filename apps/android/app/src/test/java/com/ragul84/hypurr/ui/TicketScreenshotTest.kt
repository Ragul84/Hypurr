package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.FileChange
import com.ragul84.hypurr.model.GitHubStatus
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.JiraStatus
import com.ragul84.hypurr.model.PlanFile
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.TicketCli
import com.ragul84.hypurr.model.TicketPlan
import com.ragul84.hypurr.model.TicketPr
import com.ragul84.hypurr.model.TicketPublishInfo
import com.ragul84.hypurr.model.TicketReview
import com.ragul84.hypurr.model.TicketSetup
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.TasksScreen
import com.ragul84.hypurr.ui.screens.TicketScreen
import com.ragul84.hypurr.ui.screens.TicketStep
import com.ragul84.hypurr.ui.screens.TicketUiState
import com.ragul84.hypurr.ui.screens.publishWords
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ticket to PR, light and dark: `android-ticket-<name>-<mode>.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class TicketScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-ticket-$name-${mode.name.lowercase()}.png")
        }
    }

    private val projects = listOf(Project("shop-app", "/Users/priya/code/shop-app"), Project("shop-api", "/Users/priya/code/shop-api"))
    private val base = TicketUiState(projects = projects, project = projects[0].path)
    private val ticket = Issue("jira", "SHOP-142", "Coupon codes don't lower the cart total", "https://acme.atlassian.net/browse/SHOP-142",
        "When a customer applies SAVE10 the total stays the same.", projects[0].path)
    private val plan = TicketPlan(
        understood = "When a customer applies a valid coupon, the cart total should drop by the coupon's amount, and the discount should show as its own line.",
        files = listOf(
            PlanFile("app/src/main/java/com/shop/cart/CartTotals.kt", "Subtract the coupon before tax"),
            PlanFile("app/src/main/java/com/shop/cart/CartScreen.kt", "Show a Discount line"),
            PlanFile("app/src/test/java/com/shop/cart/CouponTest.kt", "Tests for valid, expired and unknown codes", exists = false),
        ),
        checks = listOf("SAVE10 on a ₹500 cart shows ₹450", "An expired code shows 'This code has expired'", "The total never goes below ₹0"),
        tests = listOf("coupon lowers total", "expired coupon is refused"),
        testCommand = "./gradlew test",
    )
    private val review = TicketReview(
        ticket = ticket,
        files = listOf(
            FileChange("app/src/main/java/com/shop/cart/CartTotals.kt", 18, 4),
            FileChange("app/src/main/java/com/shop/cart/CartScreen.kt", 9, 1),
            FileChange("app/src/test/java/com/shop/cart/CouponTest.kt", 46, 0),
        ),
        summary = "Coupons now lower the cart total, and the discount shows as its own line. Expired codes get a clear message. Three new tests cover valid, expired and unknown codes, and all tests pass.",
        by = "agent",
        prTitle = "SHOP-142: Coupon codes don't lower the cart total",
        prBody = "Ticket: [SHOP-142](https://acme.atlassian.net/browse/SHOP-142)\n\n## What changed\nCoupons now lower the cart total…\n\n## How to check\n- [ ] SAVE10 on a ₹500 cart shows ₹450",
        comment = "Update from Hypurr:\n\nCoupons now lower the cart total, and the discount shows as its own line.\n\nPull request for review: https://github.com/acme/shop-app/pull/88\n(SHOP-142)",
        publish = TicketPublishInfo("github", "gh", "hypurr/shop-142-coupons", "main"),
        testCommand = "./gradlew test",
    )

    @Test fun input() = shoot("input") { TicketScreen(base.copy(input = "https://acme.atlassian.net/browse/SHOP-142")) }

    @Test fun needsSetup() = shoot("needs-setup") { TicketScreen(base.copy(input = "SHOP-142", needsSetup = "jira")) }

    @Test fun planning() = shoot("planning") {
        TicketScreen(base.copy(input = "SHOP-142", ticket = ticket, busy = true, busyLabel = "Reading the code and making a plan"))
    }

    @Test fun plan() = shoot("plan") {
        TicketScreen(base.copy(step = TicketStep.Plan, ticket = ticket, plan = plan, checksText = plan.checks.joinToString("\n")))
    }

    @Test fun running() = shoot("running") {
        TicketScreen(base.copy(step = TicketStep.Running, ticket = ticket, plan = plan, botId = "b1", taskId = "t1",
            branch = "hypurr/shop-142-coupons", agentStatus = "needsInput"))
    }

    @Test fun review() = shoot("review") {
        TicketScreen(base.copy(step = TicketStep.Review, ticket = ticket, review = review, prTitle = review.prTitle, prBody = review.prBody, taskId = "t1"))
    }

    @Test fun approval() = shoot("push-approval") {
        TicketScreen(base.copy(step = TicketStep.Review, ticket = ticket, review = review, prTitle = review.prTitle, prBody = review.prBody,
            taskId = "t1", confirmPush = true))
    }

    @Test fun opened() = shoot("pr-opened") {
        TicketScreen(base.copy(step = TicketStep.Review, ticket = ticket, taskId = "t1",
            review = review.copy(pr = TicketPr(pushed = true, branch = "hypurr/shop-142-coupons", provider = "github", url = "https://github.com/acme/shop-app/pull/88", number = 88))))
    }

    @Test fun copyOnly() = shoot("pr-copy") {
        TicketScreen(base.copy(step = TicketStep.Review, ticket = ticket.copy(source = "text", key = "AB#4521", url = ""), taskId = "t1",
            review = review.copy(publish = TicketPublishInfo("azure", "", "hypurr/ab-4521", "main"),
                pr = TicketPr(pushed = true, branch = "hypurr/ab-4521", provider = "azure", copy = true))))
    }

    @Test fun setup() = shoot("setup") {
        TicketScreen(base.copy(step = TicketStep.Setup, setup = TicketSetup(
            Integrations(github = GitHubStatus(configured = true, usesCli = true), jira = JiraStatus()),
            TicketCli(gh = true, glab = false, az = true))))
    }

    @Test fun tasksEntry() = shoot("tasks-entry") {
        TasksScreen("Priya's MacBook", LinkState.Ready(Route.Relay), templates = emptyList(), recent = emptyList())
    }

    @Test fun planEditsAndWords() {
        val s = base.copy(plan = plan, checksText = "- first\n\n second \n")
        assertEquals(listOf("first", "second"), s.editedPlan()?.checks)
        assertEquals("shop-app", s.projectName)
        assertEquals("Upload the branch to Azure DevOps, then copy the description into a new pull request", publishWords("azure", ""))
    }
}
