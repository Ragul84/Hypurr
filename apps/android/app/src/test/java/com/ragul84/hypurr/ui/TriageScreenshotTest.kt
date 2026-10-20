package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.CodePlace
import com.ragul84.hypurr.model.Redaction
import com.ragul84.hypurr.model.Triage
import com.ragul84.hypurr.model.TriageResult
import com.ragul84.hypurr.model.TriageSource
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.TasksScreen
import com.ragul84.hypurr.ui.screens.TriageScreen
import com.ragul84.hypurr.ui.screens.TriageUiState
import com.ragul84.hypurr.ui.screens.redactionWords
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Fix from a log, light and dark: `android-triage-<name>-<mode>.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class TriageScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "android-triage-$name-${mode.name.lowercase()}.png").absolutePath)
        }
    }

    private val log = """
        E/AndroidRuntime: FATAL EXCEPTION: main
        java.lang.NullPointerException: token must not be null
            at com.shop.auth.LoginRepo.save(LoginRepo.kt:42)
            at com.shop.auth.LoginViewModel.onResult(LoginViewModel.kt:88)
        POST https://api.shop.in/v1/login Authorization: Bearer [TOKEN] user=[EMAIL]
    """.trimIndent()

    private val redactions = listOf(Redaction("token", 1, "eyJh…"), Redaction("email", 1, "priy…"), Redaction("ip", 2, "10.2…"))

    private val result = TriageResult(
        triage = Triage(
            broke = "The app crashes right after login when the server doesn't send a session token.",
            cause = "LoginRepo.save reads the token without checking it exists. When the login API returns an error, the token is empty and the app stops.",
            places = listOf(
                CodePlace("app/src/main/java/com/shop/auth/LoginRepo.kt", 42,
                    "  40|     fun save(res: LoginResponse) {\n  41|         val prefs = store.edit()\n  42|         prefs.putString(\"token\", res.token!!)\n  43|         prefs.apply()\n  44|     }",
                    "Uses !! on a token that can be missing"),
                CodePlace("app/src/main/java/com/shop/auth/LoginViewModel.kt", 88, "", "Calls save() even when the request failed"),
            ),
            confidence = "medium",
            confidenceWhy = "The stack trace and the code agree, but I haven't run it.",
            fix = listOf("Check the response succeeded before saving.", "Show 'Login failed, try again' instead of crashing.", "Add a test for a login error response."),
        ),
        by = "agent",
        redactions = redactions,
        ticket = "Title: App crashes after login when no token is returned\n\nSummary\nThe app crashes right after login…\n\nLikely cause (medium confidence)\nLoginRepo.save reads the token without checking it.\n\nWhere\n• app/src/main/java/com/shop/auth/LoginRepo.kt:42\n\nProject: shop",
    )

    @Test fun empty() = shoot("input-empty") { TriageScreen(TriageUiState(projectName = "shop")) }

    @Test fun pasted() = shoot("input-pasted") {
        TriageScreen(TriageUiState(input = log, sourceLabel = "Pasted", preview = redactions, projectName = "shop"))
    }

    @Test fun sources() = shoot("sources") {
        TriageScreen(TriageUiState(projectName = "shop", pickingSource = true, sources = listOf(
            TriageSource("ci", "1", "Android CI", "Fix cart total · feature/cart"),
            TriageSource("ci", "2", "Unit tests", "Bump okhttp · main"),
            TriageSource("file", "build/test-results/TEST-CartTest.xml", "TEST-CartTest.xml", "app/build/test-results/TEST-CartTest.xml"),
        )))
    }

    @Test fun busy() = shoot("busy") { TriageScreen(TriageUiState(input = log, busy = true, projectName = "shop")) }

    @Test fun explained() = shoot("result") { TriageScreen(TriageUiState(input = log, result = result, projectName = "shop")) }

    @Test fun ticket() = shoot("ticket") {
        TriageScreen(TriageUiState(input = log, result = result, ticket = result.ticket, showTicket = true, projectName = "shop"))
    }

    @Test fun tasksEntry() = shoot("tasks-entry") {
        TasksScreen("Priya's MacBook", LinkState.Ready(Route.Relay), templates = emptyList(), recent = emptyList())
    }

    @Test fun redactionWordsReadNaturally() {
        assertEquals("1 token · 1 email · 2 IP addresses", redactionWords(redactions))
        assertEquals("2 passwords", redactionWords(listOf(Redaction("password", 2))))
    }
}
