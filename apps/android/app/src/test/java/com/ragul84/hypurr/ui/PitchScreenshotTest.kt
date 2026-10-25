package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.model.FileChange
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.screens.BotListScreen
import com.ragul84.hypurr.ui.screens.DiffLineKind
import com.ragul84.hypurr.ui.screens.OnboardingScreen
import com.ragul84.hypurr.ui.screens.PairingScreen
import com.ragul84.hypurr.ui.screens.PairingUiState
import com.ragul84.hypurr.ui.screens.SpendScreen
import com.ragul84.hypurr.ui.screens.TasksScreen
import com.ragul84.hypurr.ui.screens.WhatChangedFiles
import com.ragul84.hypurr.ui.screens.diffLineKind
import com.ragul84.hypurr.ui.screens.whatChangedLine
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The sharpened pitch and the What changed diff on a small phone (360x640dp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class PitchScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { HypurrTheme(ThemeMode.Light) { content() } }
        compose.waitForIdle()
        capture(name)
    }

    private fun capture(name: String) {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-pitch-$name.png").absolutePath)
    }

    private val files = listOf(
        FileChange("src/screens/AiTutor.tsx", 12, 3, patch = "@@ -40,9 +40,18 @@ export function AiTutor() {\n   const [text, setText] = useState('')\n" +
            "-  const send = () => post(text)\n+  const [sending, setSending] = useState(false)\n+  const send = async () => {\n" +
            "+    if (sending) return\n+    setSending(true)\n+    try { await post(text) } finally { setSending(false) }\n+  }\n" +
            "   return (\n-    <Button onPress={send}>Send</Button>\n+    <Button onPress={send} disabled={sending}>\n" +
            "+      {sending ? <Spinner /> : 'Send'}\n+    </Button>\n   )\n"),
        FileChange("src/components/Spinner.tsx", 18, 0, patch = "@@ -0,0 +1,3 @@\n+export function Spinner() {\n+  return <ActivityIndicator />\n+}\n"),
        FileChange("assets/logo.png", 0, 0),
    )

    @Test fun pitchCopyNeverNamesProviders() {
        val all = Pitch.Headline + Pitch.Subhead + Pitch.Pillars.joinToString { it.title + it.body }
        listOf("OpenCode", "OpenRouter").forEach { assertTrue(it, !all.contains(it, ignoreCase = true)) }
        assertTrue(all.contains("not for company code"))
        assertTrue(all.contains("Start free with your own account"))
        assertTrue(!all.contains("10 messages"))
    }

    @Test fun diffHelpers() {
        assertEquals(DiffLineKind.Hunk, diffLineKind("@@ -1 +1 @@"))
        assertEquals(DiffLineKind.Added, diffLineKind("+a"))
        assertEquals(DiffLineKind.Removed, diffLineKind("-a"))
        assertEquals(DiffLineKind.Context, diffLineKind(" a"))
        assertEquals("3 files changed · +30 −3", whatChangedLine(files))
    }

    @Test fun onboardingFirstScreen() {
        shoot("onboarding-first-small") { OnboardingScreen(UserProfile(), onFinished = {}) }
        compose.onNodeWithText("Safe for non-experts").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test fun pairing() = shoot("pairing-small") { PairingScreen(PairingUiState(), {}, {}, {}, {}) }

    @Test fun emptyRoster() = shoot("empty-roster-small") {
        BotListScreen(Samples.computer.name, LinkState.Ready(Route.Direct), emptyList(), synced = true,
            onOpen = {}, onSettings = {}, onRetry = {}, now = Samples.NOW)
    }

    @Test fun tasksEmpty() = shoot("tasks-empty-small") {
        TasksScreen(Samples.computer.name, LinkState.Ready(Route.Direct), emptyList(), recent = emptyList(), now = Samples.NOW)
    }

    @Test fun spend() = shoot("spend-small") {
        SpendScreen(Samples.computer.name, LinkState.Ready(Route.Direct), Samples.costs, bots = Samples.bots.associateBy { it.id }, now = Samples.NOW)
    }

    @Test fun whatChangedTapToSeeDiff() {
        compose.setContent {
            HypurrTheme(ThemeMode.Light) {
                Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    WhatChangedFiles(files)
                }
            }
        }
        capture("what-changed-small")
        compose.onNodeWithText("src/screens/AiTutor.tsx").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("+    setSending(true)")[0].assertIsDisplayed()
        capture("what-changed-diff-small")
    }
}
