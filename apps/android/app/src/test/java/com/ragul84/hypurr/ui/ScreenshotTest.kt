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
import com.ragul84.hypurr.data.PickedFile
import com.ragul84.hypurr.screen.ScreenPhase
import com.ragul84.hypurr.ui.screens.BotEditorScreen
import com.ragul84.hypurr.ui.screens.BuiltinInstallPrompt
import com.ragul84.hypurr.ui.screens.BotEditorState
import com.ragul84.hypurr.ui.screens.ComposerFiles
import com.ragul84.hypurr.ui.screens.ScreenScreen
import com.ragul84.hypurr.ui.screens.ScreenUiState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.IntSize
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

    // Stage D: Android parity.

    @Test
    @Config(qualifiers = "w412dp-h1250dp-xxhdpi")
    fun markdownFilesThreads() = shoot("chat-rich") {
        ChatScreen(Samples.alice, Samples.richChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            bots = Samples.groupBots + ("b1" to Samples.alice), images = Samples.richImages, onEdit = {}, now = Samples.NOW)
    }

    @Test
    fun composerFiles() = shoot("composer-files") {
        ChatScreen(Samples.alice, Samples.richChat.take(1), "Here's the error from the console", {}, onBack = {}, onSend = {}, onStop = {},
            onRespond = { _, _ -> }, composer = ComposerFiles(listOf(Samples.screenshot(), PickedFile("build.log", ByteArray(2048)))),
            images = Samples.richImages, now = Samples.NOW)
    }

    @Test
    fun messageActions() = shoot("message-actions") {
        ChatScreen(Samples.alice, Samples.richChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            images = Samples.richImages, initialActionsFor = "m2", now = Samples.NOW)
    }

    @Test
    fun threadView() = shoot("thread") {
        ChatScreen(Samples.alice, Samples.thread, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            bots = Samples.groupBots, threadRoot = Samples.richChat[1], now = Samples.NOW)
    }

    @Test
    fun groupChat() = shoot("group") {
        ChatScreen(Samples.group, Samples.groupChat, "", {}, onBack = {}, onSend = {}, onStop = {}, onRespond = { _, _ -> },
            bots = Samples.groupBots, composer = ComposerFiles(canAttach = false), onEdit = {}, now = Samples.NOW)
    }

    @Test
    fun rosterGroupsAndMenu() = shoot("bots-groups") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), rosterOrder(Samples.groupBots.values), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW, byId = Samples.groupBots, initialMenuOpen = true, onScreen = {})
    }

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    fun newBot() = shoot("new-bot") {
        BotEditorScreen(BotEditorState(name = "Reviewer", description = "Reviews pull requests", avatarColor = "violet", backend = "claude",
            folder = "/Users/kevin/code/shop", browsing = Samples.dirs), Samples.backends, emptyList(), {}, {}, {}, {}, {})
    }

    @Test
    @Config(qualifiers = "w412dp-h1250dp-xxhdpi")
    fun editGroup() = shoot("edit-group") {
        BotEditorScreen(BotEditorState.of(Samples.group), Samples.backends, listOf(Samples.alice, Samples.bob, Samples.bots[2]), {}, {}, {}, {}, {})
    }

    @Test
    fun screenViewer() = shoot("screen") {
        ScreenScreen(ScreenUiState("Kevin's Mac Studio", Samples.screenOn, ScreenPhase.Live, IntSize(2560, 1440)), {}, {}, {}, {}) { m ->
            FakeDesktop(m)
        }
    }

    @Test
    fun screenOff() = shoot("screen-off") {
        ScreenScreen(ScreenUiState("Kevin's Mac Studio", com.ragul84.hypurr.model.ScreenState()), {}, {}, {}, {}) { }
    }

    @Test
    @Config(qualifiers = "w412dp-h1600dp-xxhdpi")
    fun newBotBuiltin() = shoot("new-bot-builtin") {
        BotEditorScreen(
            BotEditorState(name = "Helper", description = "First bot on a new computer", avatarColor = "violet",
                backend = "hypurr-agent", model = "hypurr/hypurr-free"),
            Samples.backends, emptyList(), {}, {}, {}, {}, {})
    }

    @Test
    fun rosterBuiltinInstall() = shoot("builtin-install") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), emptyList(), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW, byId = emptyMap(),
            builtinInstall = BuiltinInstallPrompt())
    }
}

/** Stands in for the WebRTC video in screenshots: a desktop with a window, letterboxed like the real video. */
@androidx.compose.runtime.Composable
private fun FakeDesktop(modifier: androidx.compose.ui.Modifier) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            drawRect(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF3A1C71),
                androidx.compose.ui.graphics.Color(0xFFD76D77), androidx.compose.ui.graphics.Color(0xFFFFAF7B))))
            drawRect(androidx.compose.ui.graphics.Color(0xF0FFFFFF), topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.14f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.66f))
            drawRect(androidx.compose.ui.graphics.Color(0xFFE8EAED), topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.14f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.07f))
            for (i in 0 until 6) drawRect(androidx.compose.ui.graphics.Color(0xFFBDC1C6),
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.16f, size.height * (0.28f + i * 0.08f)),
                size = androidx.compose.ui.geometry.Size(size.width * (0.5f - (i % 3) * 0.08f), size.height * 0.03f))
            drawRect(androidx.compose.ui.graphics.Color(0xCC1E1E1E), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.94f),
                size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.06f))
        }
    }

}
