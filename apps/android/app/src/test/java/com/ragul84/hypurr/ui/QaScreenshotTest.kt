package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.QaCase
import com.ragul84.hypurr.model.QaFramework
import com.ragul84.hypurr.model.QaRunResult
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.qa.QaExport
import com.ragul84.hypurr.qa.QaFormat
import com.ragul84.hypurr.ui.screens.QaScreen
import com.ragul84.hypurr.ui.screens.QaStep
import com.ragul84.hypurr.ui.screens.QaUiState
import com.ragul84.hypurr.ui.screens.TasksScreen
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** QA test writer, light and dark: `android-qa-<name>-<mode>.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class QaScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-qa-$name-${mode.name.lowercase()}.png")
        }
    }

    private val projects = listOf(Project("shop-app", "/Users/priya/code/shop-app"), Project("shop-web", "/Users/priya/code/shop-web"))
    private val base = QaUiState(projects = projects, project = projects[0].path)
    private val cases = listOf(
        QaCase("TC-1", "Valid coupon lowers the total", listOf("Add a ₹500 item to the cart", "Apply the code SAVE10"), "Total shows ₹450 and a Discount line",
            "high", "positive", "This is the main promise of coupons; if it breaks, customers notice first.", true),
        QaCase("TC-2", "Expired coupon is refused", listOf("Apply the code OLD5"), "'This code has expired' appears; total unchanged",
            "high", "negative", "Checks the app says no clearly instead of silently doing nothing.", true),
        QaCase("TC-3", "Coupon bigger than the cart", listOf("Add a ₹40 item", "Apply FLAT100"), "Total shows ₹0, never negative",
            "medium", "edge", "Edge cases live at limits like zero; negative totals are a classic bug.", true),
        QaCase("TC-4", "Coupon field with spaces", listOf("Type '  save10 ' and apply"), "Code is accepted", "low", "edge", "People paste codes with spaces.", false),
    )
    private val frameworks = listOf(QaFramework("junit", "JUnit", "./gradlew test"), QaFramework("espresso", "Espresso", "./gradlew connectedAndroidTest", true))
    private val withCases = base.copy(step = QaStep.Cases, cases = cases, frameworks = frameworks, framework = "junit", explain = true)

    @Test fun input() = shoot("input") {
        QaScreen(base.copy(input = "Coupons: a valid code lowers the cart total by its amount. Expired codes show an error. The total never goes below zero.", explain = true))
    }

    @Test fun writing() = shoot("writing") { QaScreen(base.copy(source = 2, input = "Checkout", busy = true, busyLabel = "Writing test cases")) }

    @Test fun cases() = shoot("cases") { QaScreen(withCases.copy(picked = setOf("TC-1", "TC-2", "TC-3"))) }

    @Test fun editing() = shoot("edit") { QaScreen(withCases.copy(editing = "TC-2")) }

    @Test fun export() = shoot("export") { QaScreen(withCases.copy(step = QaStep.Export, format = QaFormat.TestRail)) }

    @Test fun running() = shoot("running") {
        QaScreen(withCases.copy(step = QaStep.Running, picked = setOf("TC-1", "TC-2", "TC-3"), botId = "b1", taskId = "t1", agentStatus = "idle"))
    }

    @Test fun passed() = shoot("result-pass") {
        QaScreen(withCases.copy(step = QaStep.Result, picked = setOf("TC-1", "TC-2", "TC-3"), taskId = "t1",
            result = QaRunResult(ok = true, passed = 3, failed = 0, command = "./gradlew test", seconds = 41,
                output = "> Task :app:testDebugUnitTest\nCouponTest > TC-1 valid coupon PASSED\nCouponTest > TC-2 expired PASSED\nCouponTest > TC-3 bigger than cart PASSED\nBUILD SUCCESSFUL in 41s",
                automated = listOf("TC-1", "TC-2", "TC-3"), manual = listOf("TC-4"))))
    }

    @Test fun failed() = shoot("result-fail") {
        QaScreen(withCases.copy(step = QaStep.Result, picked = setOf("TC-1", "TC-2", "TC-3"), taskId = "t1",
            result = QaRunResult(ok = false, passed = 2, failed = 1, command = "./gradlew test", seconds = 38,
                output = "CouponTest > TC-3 bigger than cart FAILED\n    expected: <0> but was: <-60>\n3 tests completed, 1 failed",
                automated = listOf("TC-1", "TC-2", "TC-3"), manual = listOf("TC-4"))))
    }

    @Test fun tasksEntry() = shoot("tasks-entry") {
        TasksScreen("Priya's MacBook", LinkState.Ready(Route.Relay), templates = emptyList(), recent = emptyList())
    }

    @Test fun exportFormats() {
        val csv = QaExport.export(cases.take(1), QaFormat.Csv, setOf("TC-1"))
        assertEquals("\"ID\",\"Title\",\"Type\",\"Priority\",\"Steps\",\"Expected result\",\"Why\",\"Automated\"", csv.lines().first())
        assertTrue(csv.contains("\"1. Add a ₹500 item to the cart\n2. Apply the code SAVE10\"") && csv.contains("\"Yes\"") && csv.endsWith("\r\n"))
        val quoted = QaExport.export(listOf(cases[1]), QaFormat.Csv)
        assertTrue(quoted.contains("\"'This code has expired' appears; total unchanged\""))
        val q2 = QaExport.export(listOf(QaCase("TC-9", "Say \"hi\"", expected = "ok")), QaFormat.Csv)
        assertTrue(q2.contains("\"Say \"\"hi\"\"\""))
        val xray = QaExport.export(cases.take(1), QaFormat.Xray).trimEnd().split("\r\n")
        assertEquals(3, xray.size)
        assertEquals("\"TC-1\",\"\",\"\",\"Apply the code SAVE10\",\"Total shows ₹450 and a Discount line\"", xray[2])
        val jira = QaExport.export(cases.take(1), QaFormat.Jira, setOf("TC-1"))
        assertTrue(jira.startsWith("h3. TC-1 Valid coupon") && jira.contains("# Apply the code SAVE10") && jira.contains("*Automated*"))
        assertEquals("test-cases-testrail.csv", QaExport.fileName(QaFormat.TestRail))
    }

    @Test fun stateHelpers() {
        val s = withCases.addCase()
        assertEquals("TC-5", s.editing)
        assertEquals("text", base.sourceName)
        assertEquals("Edge", s.update("TC-1") { it.copy(kind = "edge") }.cases[0].kind.replaceFirstChar { it.uppercase() })
    }
}
