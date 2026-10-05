package com.ragul84.hypurr.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Decodes real host output (docs/reference/fixtures/task-wire.json, captured from `hypurr-host`). */
class TaskWireTest {
    private val wire: JsonObject = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.taskWire")!!).readText()).jsonObject

    @Test
    fun botCarriesItsTask() {
        val bot = HypurrJson.decodeFromJsonElement<Bot>(wire.getValue("bot"))
        val task = assertNotNull(bot.task).let { bot.task!! }
        assertTrue(task.branch!!.startsWith("hypurr/write-tests-the-checkout-button-"))
        assertEquals("main", task.base)
        assertEquals("write-tests", task.template)
        assertTrue(task.hasSafetyNet)
        assertTrue(task.isActive)
        assertEquals("Start (from main)", task.checkpoints.first().label)
        assertEquals("Write tests: the checkout button", bot.name)
    }

    @Test
    fun permissionCardsAreExplained() {
        val pending = HypurrJson.decodeFromJsonElement<Entry>(wire.getValue("pendingCard"))
        assertEquals("high", pending.data.risk)
        assertEquals("The agent wants to delete build and everything inside.", pending.data.explain)
        assertTrue(pending.data.riskReasons!!.isNotEmpty())
        assertNotNull(pending.data.checkpoint)
        assertTrue(pending.isChat)

        val blocked = HypurrJson.decodeFromJsonElement<Entry>(wire.getValue("blockedCard"))
        assertEquals("answered", blocked.data.status)
        assertTrue(blocked.data.blocked!!.contains("main"))
        assertEquals("no", blocked.data.selected)
    }

    @Test
    fun setupAndRouteDecode() {
        val setup = HypurrJson.decodeFromJsonElement<TaskSetup>(wire.getValue("setup"))
        assertEquals(listOf("write-tests", "fix-error", "review-pr", "update-deps", "explain-code"), setup.templates.map { it.id })
        assertEquals("Error message or log", setup.templates[1].inputLabel)
        assertTrue(setup.safety.blockProtected)
        assertTrue("main" in setup.safety.protectedBranches)
        val route = HypurrJson.decodeFromJsonElement<TaskRoute>(wire.getValue("route"))
        assertTrue(route.reason.isNotEmpty())
    }
}
