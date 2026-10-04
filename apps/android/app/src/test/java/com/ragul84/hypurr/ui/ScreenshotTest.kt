package com.ragul84.hypurr.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.ChatScreen
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.ui.screens.PairingScreen
import com.ragul84.hypurr.ui.screens.PairingUiState
import com.ragul84.hypurr.ui.screens.SettingsScreen
import com.ragul84.hypurr.ui.screens.SettingsUiState
import com.ragul84.hypurr.ui.screens.rosterOrder
import com.ragul84.hypurr.ui.screens.AdminTab
import com.ragul84.hypurr.ui.screens.TeamAdminScreen
import com.ragul84.hypurr.ui.screens.TeamAdminUiState
import com.ragul84.hypurr.ui.theme.HypurrTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose screenshots of the key screens in light and dark (Roborazzi on Robolectric, native graphics).
 * `./gradlew recordRoborazziDebug` writes them to apps/android/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest {
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

    @Test
    fun pairing() = shoot("pairing") { PairingScreen(PairingUiState(), {}, {}, {}, {}) }

    @Test
    fun botList() = shoot("bots") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), rosterOrder(Samples.bots), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW)
    }

    @Test
    fun chat() = shoot("chat") {
        ChatScreen(Samples.reviewer, Samples.chat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> })
    }

    @Test
    fun settings() = shoot("settings") {
        SettingsScreen(SettingsUiState(Samples.computer, LinkState.Ready(Route.Relay), ThemeMode.System, dynamicColor = false,
            notifications = true, pushAvailable = false, version = "2.4.0", dynamicSupported = true,
            safety = Samples.safety, customTemplates = Samples.templates.filter { !it.builtin }), {}, {}, {}, {}, {})
    }

    // Stage A: plain-language tasks, explained approvals, the safety net, templates.

    @Test
    fun newTask() = shoot("newtask") {
        NewTaskScreen(NewTaskUiState(Samples.setup, goal = "Fix the checkout button so it works on small phones", template = "fix-error",
            input = "TypeError: Cannot read properties of undefined (reading 'total')\n    at Checkout.tsx:42", route = Samples.route), {}, {}, {})
    }

    /** Tall, so Hypurr's plan (routing + reason + safety promise) is in frame. */
    @Test
    @Config(qualifiers = "w412dp-h1520dp-xxhdpi")
    fun newTaskPlan() = shoot("newtask-plan") {
        NewTaskScreen(NewTaskUiState(Samples.setup, goal = "The checkout total is wrong when a coupon is applied", route = Samples.route), {}, {}, {})
    }

    @Test
    fun taskBots() = shoot("taskbots") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), rosterOrder(Samples.botsWithTask), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW)
    }

    @Test
    fun taskChat() = shoot("taskchat") {
        ChatScreen(Samples.taskBot, Samples.taskChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> }, now = Samples.NOW)
    }

    @Test
    fun checkpoints() = shoot("checkpoints") {
        ChatScreen(Samples.taskBot.copy(status = "idle"), Samples.taskChat.take(3), "", {}, onBack = {}, onSend = {}, onStop = {},
            onRespond = { _, _ -> }, initialCheckpointsOpen = true, now = Samples.NOW)
    }

    @Test
    fun emptyRoster() = shoot("empty") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), emptyList(), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW)
    }

    // Stage B: screenshot/error paste, integrations, learning mode, cost tracker.

    @Test
    @Config(qualifiers = "w412dp-h1180dp-xxhdpi")
    fun newTaskAttach() = shoot("newtask-attach") {
        NewTaskUiState(Samples.setupWork, template = "fix-error", input = "TypeError: Cannot read properties of undefined (reading 'total')",
            files = listOf(Samples.screenshot()), issue = Samples.ghIssue, route = Samples.route).let { NewTaskScreen(it, {}, {}, {}) }
    }

    @Test
    fun issuePicker() = shoot("issues") {
        NewTaskScreen(NewTaskUiState(Samples.setupWork, pickingIssue = true, issues = Samples.issues, route = Samples.route), {}, {}, {})
    }

    @Test
    fun finishTask() = shoot("finish") {
        ChatScreen(Samples.workBot, Samples.workChat.take(2), "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            integrations = Samples.integrations, initialCheckpointsOpen = true, initialFinishOpen = true, now = Samples.NOW)
    }

    @Test
    fun learningCard() = shoot("learning") {
        ChatScreen(Samples.finishedBot, Samples.workChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            integrations = Samples.integrations, now = Samples.NOW)
    }

    /** Tall, so Work tools and Spending are both in frame. */
    @Test
    @Config(qualifiers = "w412dp-h2600dp-xxhdpi")
    fun settingsWork() = shoot("settings-work") {
        SettingsScreen(SettingsUiState(Samples.computer, LinkState.Ready(Route.Relay), ThemeMode.System, dynamicColor = false,
            notifications = true, pushAvailable = false, version = "2.4.0", dynamicSupported = true, safety = Samples.safety,
            integrations = Samples.integrations, costs = Samples.costs, testResults = mapOf("slack" to "Sent a test message")),
            {}, {}, {}, {}, {})
    }

    // Stage C: team admin.

    private fun admin(tab: AdminTab, you: com.ragul84.hypurr.model.Actor = Samples.admin) = TeamAdminUiState(
        you, Samples.team.copy(you = you), Samples.policies, Samples.activity, Samples.auditLog, tab, now = Samples.NOW)

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xxhdpi")
    fun adminActivity() = shoot("admin-activity") { TeamAdminScreen(admin(AdminTab.Activity), {}, {}) }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-xxhdpi")
    fun adminRules() = shoot("admin-rules") { TeamAdminScreen(admin(AdminTab.Rules), {}, {}) }

    @Test
    @Config(qualifiers = "w412dp-h1180dp-xxhdpi")
    fun adminPeople() = shoot("admin-people") { TeamAdminScreen(admin(AdminTab.People), {}, {}) }

    @Test
    @Config(qualifiers = "w412dp-h1300dp-xxhdpi")
    fun adminAudit() = shoot("admin-audit") { TeamAdminScreen(admin(AdminTab.Audit), {}, {}) }

    /** What a member sees: read-only rules. */
    @Test
    @Config(qualifiers = "w412dp-h1700dp-xxhdpi")
    fun adminRulesMember() = shoot("admin-rules-member") { TeamAdminScreen(admin(AdminTab.Rules, Samples.member), {}, {}) }

    @Test
    fun needsAdminCard() = shoot("needs-admin") {
        ChatScreen(Samples.taskBot.copy(status = "needsInput"), Samples.adminCardChat, "", {}, onBack = {}, onSend = {}, onStop = {},
            onRespond = { _, _ -> }, you = Samples.member, now = Samples.NOW)
    }
}
