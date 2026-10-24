package com.ragul84.hypurr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.DirEntry
import com.ragul84.hypurr.model.DirListing
import com.ragul84.hypurr.model.Project
import com.ragul84.hypurr.ui.screens.ProjectFolderPickerScreen
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The full-screen folder browser: drives at the top, projects first, a warning before a whole drive. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi", application = android.app.Application::class)
class FolderPickerScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val dir get() = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }

    private val top = DirListing(path = "", drives = true, dirs = listOf(
        DirEntry("Home", "C:\\Users\\ragul", kind = "home"),
        DirEntry("C:", "C:\\", isRoot = true, kind = "drive"),
        DirEntry("D:", "D:\\", isRoot = true, kind = "drive"),
    ))
    private val work = DirListing(path = "C:\\Users\\ragul\\work", parent = "C:\\Users\\ragul", dirs = listOf(
        DirEntry("shop-web", "C:\\Users\\ragul\\work\\shop-web", isProject = true),
        DirEntry("billing-api", "C:\\Users\\ragul\\work\\billing-api", isProject = true),
        DirEntry("notes", "C:\\Users\\ragul\\work\\notes"),
        DirEntry("old stuff", "C:\\Users\\ragul\\work\\old stuff"),
    ))
    private val cRoot = DirListing(path = "C:\\", parent = "", isRoot = true, dirs = listOf(DirEntry("Users", "C:\\Users"), DirEntry("work", "C:\\work")))
    private val recent = listOf(Project("shop-web", "C:\\Users\\ragul\\work\\shop-web", saved = true))

    @Test fun topLevelListsDrivesAndRecent() {
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            ProjectFolderPickerScreen(recent, top, null, onBack = {}, onBrowse = {}, onSelect = {}, onConfirm = {})
        } }
        compose.onNodeWithText("Recent projects", ignoreCase = true).assertExists()
        compose.onNodeWithText("D:").assertExists()
        compose.onRoot().captureRoboImage(File(dir, "android-folder-browser-top-small.png").absolutePath)
    }

    @Test fun insideFolderShowsProjectBadgesAndBreadcrumb() {
        var browsed: String? = null
        compose.setContent { HypurrTheme(ThemeMode.Dark) {
            ProjectFolderPickerScreen(emptyList(), work, "C:\\Users\\ragul\\work\\shop-web", onBack = {}, onBrowse = { browsed = it }, onSelect = {}, onConfirm = {})
        } }
        compose.onNodeWithText("ragul").performClick()
        assertEquals("C:\\Users\\ragul", browsed)
        compose.onRoot().captureRoboImage(File(dir, "android-folder-browser-folder-small.png").absolutePath)
    }

    @Test fun wholeDriveWarnsBeforeConfirming() {
        var confirmed = 0
        var selected by mutableStateOf<String?>("C:\\")
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            ProjectFolderPickerScreen(emptyList(), cRoot, selected, onBack = {}, onBrowse = {}, onSelect = { selected = it }, onConfirm = { confirmed++ })
        } }
        compose.onNodeWithText("Use whole drive C:").performClick()
        assertEquals(0, confirmed)
        compose.onNodeWithText("The agent can see and change everything on this drive.").assertExists()
        compose.onRoot().captureRoboImage(File(dir, "android-folder-browser-drive-warning-small.png").absolutePath)
        compose.onNodeWithText("Yes, use the whole drive").performClick()
        assertEquals(1, confirmed)
    }
}
