package com.ragul84.hypurr.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.data.UserProfile
import com.ragul84.hypurr.ui.screens.OnboardingScreen
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A small phone (360x640dp) with large text: Continue must stay on screen on every step. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi", application = android.app.Application::class)
class OnboardingSmallScreenTest {
    @get:Rule val compose = createComposeRule()

    @Before fun bigText() = RuntimeEnvironment.setFontScale(1.3f)

    private fun shot(name: String) {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "android-$name.png").absolutePath)
    }

    @Test fun continueIsVisibleAndWorksOnEveryStep() {
        var done: UserProfile? = null
        compose.setContent { HypurrTheme(ThemeMode.Light) { OnboardingScreen(UserProfile(), onFinished = { done = it }) } }
        val picks = listOf("Tester/QA", "New", "Java", "Write tests")
        picks.forEachIndexed { i, pick ->
            compose.onNodeWithText("Continue").assertIsDisplayed()
            compose.onNodeWithText("Skip for now").assertIsDisplayed()
            compose.onNodeWithText(pick).performClick()
            compose.waitForIdle()
            if (i == picks.lastIndex) shot("onboarding-small-4-goals-selected")
            compose.onNodeWithText("Continue").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf("Write tests"), done?.goals)
        assertEquals("Tester/QA", done?.role)
    }

    @Test fun roleNeedsAnAnswerButOptionalStepsDoNot() {
        compose.setContent { HypurrTheme(ThemeMode.Light) { OnboardingScreen(UserProfile(), initialStep = 0, onFinished = {}) } }
        compose.onNodeWithText("Continue").assertIsDisplayed().assertIsNotEnabled()
        shot("onboarding-small-1-role-empty")
    }
}
