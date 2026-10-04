package com.ragul84.hypurr.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Decodes real host output for team admin (docs/reference/fixtures/admin-wire.json, from `hypurr-host`). */
class AdminWireTest {
    private val wire: JsonObject = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.adminWire")!!).readText()).jsonObject

    @Test
    fun helloSaysWhoYouAre() {
        val you = HypurrJson.decodeFromJsonElement<Hello>(wire.getValue("hello")).you!!
        assertEquals("Priya's phone", you.name)
        assertEquals("viewer", you.role)
        assertFalse(you.isAdmin)
        assertFalse(you.canAct)
    }

    @Test
    fun teamAndPolicies() {
        val team = HypurrJson.decodeFromJsonElement<TeamInfo>(wire.getValue("team"))
        assertEquals("member", team.defaultRole)
        assertTrue(team.you.isAdmin)
        assertEquals(listOf("This computer", "Arjun's Pixel", "Priya's phone"), team.people.map { it.name })
        assertTrue(team.people.first().fixed)
        assertEquals("viewer", team.people.last().role)
        val p = HypurrJson.decodeFromJsonElement<PolicyInfo>(wire.getValue("policies"))
        assertEquals(listOf("custom"), p.policies.allowedAgents)
        assertEquals("low", p.policies.adminApprovesFrom)
        assertEquals("high", p.policies.askFrom)
        assertEquals(listOf("main"), p.safety.protectedBranches)
        assertEquals("No limit", limitLabel(p.policies.dailyLimit))
        assertEquals("$5.00", limitLabel(5.0))
    }

    @Test
    fun cardNeedsAnAdmin() {
        val card = HypurrJson.decodeFromJsonElement<Entry>(wire.getValue("card"))
        assertEquals(true, card.data.needsAdmin)
        assertEquals("low", card.data.risk)
    }

    @Test
    fun auditLogAndActivity() {
        val log = HypurrJson.decodeFromJsonElement<AuditLog>(wire.getValue("auditLog"))
        assertTrue(log.intact)
        assertEquals(log.count, log.entries.size.toLong())
        val blocked = log.entries.first { it.action == "blocked" && it.target == "setPolicies" }
        assertEquals("was blocked", blocked.summary)
        assertEquals("Changing the team rules", blocked.targetLabel)
        assertTrue(blocked.reason!!.contains("member"))
        val answer = log.entries.first { it.action == "approval.answer" }
        assertEquals("Arjun's Pixel", answer.actorName)
        assertEquals("low", answer.risk)
        val a = HypurrJson.decodeFromJsonElement<Activity>(wire.getValue("activity"))
        val priya = a.people.first { it.name == "Priya's phone" }
        assertTrue(priya.blocked >= 3)
        assertTrue(a.totals.approvals >= 1)
    }
}
