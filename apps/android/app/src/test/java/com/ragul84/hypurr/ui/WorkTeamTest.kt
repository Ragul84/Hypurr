package com.ragul84.hypurr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.ui.theme.Hypurr
import com.ragul84.hypurr.ui.theme.HypurrTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class WorkTeamTest {
    @get:Rule val compose = createComposeRule()

    private fun json(s: String): JsonObject = HypurrJson.parseToJsonElement(s).jsonObject
    private fun shot(name: String) {
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "build/roborazzi").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(dir, "$name.png").absolutePath)
    }

    @Test fun authorizeUrlUsesPkceAndTheCompanyDomain() {
        val cfg = SsoConfig.forSetup("google", "cid.apps.googleusercontent.com", null)
        val a = WorkOAuth.start(cfg, "Sam@Acme.com")
        val url = WorkOAuth.authorizeUrl(a)
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=cid.apps.googleusercontent.com"))
        assertTrue(url.contains("code_challenge=${WorkOAuth.challenge(a.verifier)}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("hd=acme.com"))
        assertTrue(url.contains("redirect_uri=hypurr%3A%2F%2Foauth%2Fwork"))
        val ms = SsoConfig.forSetup("microsoft", "abc", "11111111-2222-3333-4444-555555555555")
        assertEquals("https://login.microsoftonline.com/11111111-2222-3333-4444-555555555555/oauth2/v2.0/token", ms.tokenUrl)
        // A reply for someone else's attempt is refused.
        val bad = android.net.Uri.parse("hypurr://oauth/work?code=x&state=nope")
        assertEquals("That sign-in didn't come from this app. Try again.", runCatching { WorkOAuth.codeFrom(bad, a) }.exceptionOrNull()?.message)
        assertEquals("x", WorkOAuth.codeFrom(android.net.Uri.parse("hypurr://oauth/work?code=x&state=${a.state}"), a))
    }

    @Test fun spendCardSignedOutAndIn() {
        var tapped = ""
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            Column(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(16.dp)) {
                WorkTeamCard(null, onSignIn = { tapped = "in" }, onManage = {}, onSignOut = {})
                androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                WorkTeamCard(json("""{"workEmail":"sam@acme.com","team":{"id":"t","monthlyLimitCents":5000,"spentThisMonthCents":1234}}"""),
                    onSignIn = {}, onManage = { tapped = "team" }, onSignOut = {})
            }
        } }
        shot("android-spend-work-account")
        compose.onNodeWithText("Sign in with work account").performClick()
        assertEquals("in", tapped)
        compose.onNodeWithText("$12.34 of your $50.00 this month").assertExists()
    }

    @Test fun signInForm() {
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            Box(Modifier.fillMaxSize().background(Hypurr.colors.bg).padding(20.dp)) { WorkSignInForm(false, null, {}, {}) }
        } }
        shot("android-work-sign-in")
    }

    @Test fun adminSeesPoolAndPeople() {
        val team = json("""{"team":{"id":"t","name":"Acme"},"role":"admin","poolCents":42000,
            "members":[{"userId":"u1","email":"it@acme.com","role":"admin","status":"active","monthlyLimitCents":null,"spentThisMonthCents":300},
                       {"userId":"u2","email":"sam@acme.com","role":"member","status":"active","monthlyLimitCents":5000,"spentThisMonthCents":1234},
                       {"userId":"u3","email":"ana@acme.com","role":"member","status":"suspended","monthlyLimitCents":null,"spentThisMonthCents":0}]}""")
        val calls = mutableListOf<String>()
        compose.setContent { HypurrTheme(ThemeMode.Light) {
            WorkTeamScreen(team, null, onBack = {}, onLimit = { id, c -> calls += "limit:$id:$c" },
                onPause = { id, p -> calls += "pause:$id:$p" }, onAdmin = { calls += "admin:$it" }, onFillPool = { calls += "pool:$it" })
        } }
        shot("android-work-team-admin")
        compose.onNodeWithText("Resume").performClick()
        assertEquals(listOf("pause:u3:false"), calls)
    }
}
