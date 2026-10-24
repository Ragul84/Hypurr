package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.Agent
import com.ragul84.hypurr.model.AgentModel
import com.ragul84.hypurr.model.Backend
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.ui.screens.BotEditorState
import com.ragul84.hypurr.ui.screens.NewTaskScreen
import com.ragul84.hypurr.ui.screens.NewTaskUiState
import com.ragul84.hypurr.ui.screens.runnableModels
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

/** New task: agent and folder are required pickers at the top; no Hypurr gateway models without the gateway. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class NewTaskPickersTest {
    @get:Rule val compose = createComposeRule()

    private val setup = TaskSetup(
        projects = listOf(Project("shop-web", "C:\\Users\\ragul\\work\\shop-web", saved = true)),
        agents = listOf(Agent("claude", "Claude Code"), Agent("codex", "Codex")),
    )

    @Test fun startNeedsAgentAndFolder() {
        var state by mutableStateOf(NewTaskUiState(setup = setup, goal = "Fix the login bug"))
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            NewTaskScreen(state, onChange = { state = it }, onStart = {}, onBack = {},
                onPickFolder = { state = state.copy(project = "C:\\Users\\ragul\\work\\shop-web") })
        } }
        compose.onNodeWithText("Start task").assertIsNotEnabled()
        compose.onNodeWithText("Choose an agent").performClick()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("Start task").assertIsNotEnabled()
        compose.onNodeWithText("Choose a folder on your PC").performClick()
        compose.onNodeWithText("Start task").assertIsEnabled()
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-new-task-pickers-small.png").absolutePath)
    }

    @Test fun shotEmptyPickers() {
        compose.setContent { HypurrTheme(ThemeMode.Dark) { NewTaskScreen(NewTaskUiState(setup = setup), onChange = {}, onStart = {}, onBack = {}) } }
        compose.onNodeWithText("Start task").assertIsNotEnabled()
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-new-task-pickers-empty-small.png").absolutePath)
    }

    @Test fun noGatewayHidesFreeModelsAndPrefersSignedInAgent() {
        val free = listOf(AgentModel("hypurr/hypurr-free", "Hypurr Free", free = true))
        val builtin = Backend("hypurr-agent", "Hypurr Agent", builtin = true, installed = true, freeModels = free,
            defaultModel = "hypurr/hypurr-free", gatewayReady = false)
        val claude = Backend("claude", "Claude Code", installed = true, signedIn = true)
        assertTrue(runnableModels(Hello(hostId = "h", backends = listOf(builtin, claude))).isEmpty())
        val pick = BotEditorState.newBot(listOf(builtin, claude))
        assertEquals("claude", pick.backend)
        val alone = BotEditorState.newBot(listOf(builtin))
        assertEquals(null, alone.model)
        val live = builtin.copy(gatewayReady = true)
        assertEquals("hypurr/hypurr-free", BotEditorState.newBot(listOf(live, claude)).model)
    }
}
