package com.ragul84.hypurr.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.ItKit
import com.ragul84.hypurr.model.ItKitPage
import com.ragul84.hypurr.model.PersonSpend
import com.ragul84.hypurr.model.Pricing
import com.ragul84.hypurr.model.SpendByPerson
import com.ragul84.hypurr.model.planPrice
import com.ragul84.hypurr.model.rupees
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.Route
import com.ragul84.hypurr.ui.Samples
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** Stage 6: Share with IT, the Manager console (₹ spend, local-only, folder rules, audit export) and ₹ plans. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ItKitScreenshotTest {
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

    /** The real config file, so the shots show exactly what's configured. */
    private val pricing: Pricing = run {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "pricing/plans.json") }.first { it.isFile }
        HypurrJson.decodeFromString(Pricing.serializer(), f.readText())
    }

    private fun doc(name: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/it-kit/$name.md") }.first { it.isFile }.readText()

    private val kit = ItKit(
        facts = listOf(
            "Hypurr host 0.9.0",
            "Listens on TCP 19222 for paired phones on the LAN",
            "Cloud relay off: LAN pairing only",
            "Local-only mode: on",
            "Approved model endpoints: https://llm.acme.in/v1, http://localhost:11434",
        ),
        pages = listOf(
            "data-flow" to "Data flow", "security-faq" to "Security FAQ", "allowlist" to "Allowlist",
            "signed-installer" to "Signed installer", "local-only" to "Local-only mode", "audit-export" to "Audit export",
        ).map { (id, title) -> ItKitPage(id, title, doc(id)) },
    )

    private val spend = SpendByPerson(
        people = listOf(
            PersonSpend("k-priya", "Priya's phone", todayInr = 310.0, monthInr = 2480.0, dailyInr = 300.0, monthlyInr = 3000.0),
            PersonSpend("k-arjun", "Arjun's Pixel", todayInr = 42.0, monthInr = 1210.0, dailyInr = 500.0, monthlyInr = 5000.0, custom = true),
            PersonSpend("local", "This computer", todayInr = 0.0, monthInr = 96.0, dailyInr = 300.0, monthlyInr = 3000.0),
        ),
        teamMonthInr = 3786.0,
        teamMonthlyInr = 15000.0,
    )

    private val rules = Samples.policies.copy(policies = Samples.policies.policies.copy(
        localOnly = true,
        modelEndpoints = listOf("https://llm.acme.in/v1", "http://localhost:11434"),
        allowedFolders = listOf("/Users/arjun/work"),
        personDailyInr = 300.0, personMonthlyInr = 3000.0, teamMonthlyInr = 15000.0,
    ))

    private fun admin(tab: AdminTab, note: String? = null) = TeamAdminUiState(
        Samples.admin, Samples.team, rules, Samples.activity, Samples.auditLog, spend, tab = tab, exportNote = note, now = Samples.NOW)

    @Test fun itKit() = shoot("itkit") { ItKitScreen(kit, {}) }

    @Test fun itKitOpen() = shoot("itkit-allowlist") { ItKitScreen(kit, {}, initiallyOpen = "allowlist", copied = true) }

    @Test fun adminSpend() = shoot("itkit-admin-spend") { TeamAdminScreen(admin(AdminTab.Spend), {}, {}) }

    @Test fun adminRules() = shoot("itkit-admin-rules") { TeamAdminScreen(admin(AdminTab.Rules), {}, {}) }

    @Test fun adminAudit() = shoot("itkit-admin-audit") {
        TeamAdminScreen(admin(AdminTab.Audit, "15 records, chain intact."), {}, {})
    }

    @Test fun spendPlans() = shoot("itkit-spend-plans") {
        SpendScreen(Samples.computer.name, LinkState.Ready(Route.Direct), Samples.costs,
            bots = Samples.bots.associateBy { it.id }, now = Samples.NOW, pricing = pricing)
    }

    @Test fun plansCard() = shoot("itkit-plans") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.background(com.ragul84.hypurr.ui.theme.Hypurr.colors.bg).padding(16.dp),
        ) { PlansCard(pricing, current = "pro") }
    }

    @Test fun rupeesUseIndianGrouping() {
        assertEquals("₹0", rupees(0.0))
        assertEquals("₹999", rupees(999.4))
        assertEquals("₹1,000", rupees(1000.0))
        assertEquals("₹12,34,567", rupees(1234567.0))
    }

    @Test fun plansComeFromTheConfig() {
        assertEquals(listOf("free", "pro", "team"), pricing.plans.map { it.id })
        assertEquals("₹199 / person / month", planPrice(pricing.plans[1]))
        assertEquals("Price to be announced", planPrice(pricing.plans[2]))
        assertFalse("no live billing without keys", pricing.payments.ready)
    }

    @Test fun kitNeverNamesTheUpstreamAgent() {
        assertFalse(kit.pages.any { it.markdown.contains("opencode", ignoreCase = true) })
    }
}
