package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Standup
import com.ragul84.hypurr.model.StandupCommit
import com.ragul84.hypurr.model.StandupFacts
import com.ragul84.hypurr.model.StandupReminder
import com.ragul84.hypurr.model.StandupResult
import com.ragul84.hypurr.model.StandupTask
import com.ragul84.hypurr.model.TeamStandup
import com.ragul84.hypurr.model.TeamStandupPerson
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.StandupScreen
import com.ragul84.hypurr.ui.screens.StandupUiState
import com.ragul84.hypurr.ui.screens.TasksScreen
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Daily standup, light and dark: `android-standup-<name>-<mode>.png`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class StandupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        var theme by mutableStateOf(ThemeMode.Light)
        compose.setContent { HypurrTheme(theme) { content() } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "android-standup-$name-${mode.name.lowercase()}.png").absolutePath)
        }
    }

    private val result = StandupResult(
        standup = Standup(
            yesterday = listOf("Fixed the crash on the login screen when the password is empty", "Finished SHOP-142: checkout total now includes delivery (PR open)"),
            today = listOf("Add tests for the cart page", "Continue upgrading the payments SDK"),
            blockers = listOf("Waiting on an approval to update the payments SDK"),
        ),
        by = "agent",
        date = "Tue 20 Oct",
        facts = StandupFacts(
            projects = listOf("shop"),
            commits = List(6) { StandupCommit(project = "shop", subject = "c$it") },
            tasks = listOf(StandupTask(title = "Fix checkout total", status = "finished"), StandupTask(title = "Upgrade payments SDK", waiting = true)),
        ),
    )

    private val ready = StandupUiState.from(result, StandupUiState(projectName = "shop", reminder = StandupReminder(enabled = true)))

    @Test fun ready() = shoot("ready") { StandupScreen(ready) }

    @Test fun noBlockers() = shoot("no-blockers") {
        StandupScreen(ready.copy(blockers = "", reminder = StandupReminder(enabled = false)))
    }

    @Test fun writing() = shoot("writing") { StandupScreen(StandupUiState(loading = true, projectName = "shop")) }

    @Test fun failed() = shoot("error") {
        StandupScreen(StandupUiState(error = "Your computer is offline. Open Hypurr on it and try again.", projectName = "shop"))
    }

    @Test fun team() = shoot("team") {
        StandupScreen(ready.copy(showTeam = true, team = TeamStandup(available = true, people = listOf(
            TeamStandupPerson("local", "Priya (you)", yesterday = listOf("Fix checkout total"), today = listOf("Upgrade payments SDK"), finished = 1, open = 1),
            TeamStandupPerson("d1", "Arjun", yesterday = listOf("Write login test cases", "Triage crash logs"), finished = 2, open = 0),
            TeamStandupPerson("d2", "Meera", today = listOf("Update API docs"), finished = 0, open = 1),
        ))))
    }

    @Test fun tasksEntry() = shoot("tasks-entry") {
        TasksScreen("Priya's MacBook", LinkState.Ready(Route.Relay), templates = emptyList(), recent = emptyList())
    }

    @Test fun draftLinesAreBulleted() {
        assertEquals("• Add tests for the cart page\n• Continue upgrading the payments SDK", ready.today)
    }

    @Test fun shareTextIsPlainAndHonest() {
        val text = ready.copy(today = "• Add tests\n\n- Ship it", blockers = "").shareText()
        assertEquals(
            "Standup · Tue 20 Oct\n\nYesterday\n• Fixed the crash on the login screen when the password is empty\n" +
                "• Finished SHOP-142: checkout total now includes delivery (PR open)\n\nToday\n• Add tests\n• Ship it\n\nBlockers\n• None",
            text,
        )
    }
}
