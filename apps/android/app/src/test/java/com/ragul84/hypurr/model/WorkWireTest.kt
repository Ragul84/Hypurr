package com.ragul84.hypurr.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Decodes real host output for workplace tasks (docs/reference/fixtures/work-wire.json, from `hypurr-host`). */
class WorkWireTest {
    private val wire: JsonObject = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.workWire")!!).readText()).jsonObject

    @Test
    fun finishedTaskCarriesIssuePrSummaryAndCost() {
        val task = HypurrJson.decodeFromJsonElement<Bot>(wire.getValue("bot")).task!!
        assertEquals("finished", task.status)
        assertFalse(task.isActive)
        assertEquals("#142", task.issue!!.key)
        assertEquals("github", task.issue!!.source)
        assertEquals(7L, task.pr!!.number)
        assertTrue(task.summary!!.contains("click handler"))
        val usage = task.usage!!
        assertEquals(0.05, usage.cost, 1e-9)
        assertFalse(usage.estimated)
        assertEquals("$0.05", usage.label)
    }

    @Test
    fun learningCardDecodes() {
        val card = HypurrJson.decodeFromJsonElement<Entry>(wire.getValue("learningCard"))
        val l = card.data.learning!!
        assertEquals("notice", card.kind)
        assertEquals("fix.txt", l.files.single().path)
        assertEquals(1L, l.added)
        assertEquals(listOf("slack", "teams"), l.posted)
        assertEquals("https://github.com/acme/shop/pull/7", l.pr!!.url)
        assertTrue(l.errors.isEmpty())
    }

    @Test
    fun issuesIntegrationsAndCostsDecode() {
        val issues = HypurrJson.decodeFromJsonElement<IssueList>(wire.getValue("issues"))
        assertEquals(listOf("github", "jira"), issues.issues.map { it.source })
        assertEquals("/Users/priya/shop", issues.issues.first().project)
        val work = HypurrJson.decodeFromJsonElement<Integrations>(wire.getValue("integrations").jsonObject.getValue("integrations"))
        assertTrue(work.github.configured && work.jira.configured && work.anyChat && work.anyIssues)
        assertEquals("…abcd", work.github.tokenHint)
        val costs = HypurrJson.decodeFromJsonElement<TaskCosts>(wire.getValue("costs"))
        assertEquals(1, costs.total.tasks)
        assertEquals(0.05, costs.today, 1e-9)
        assertEquals("less than $0.01", costLabel(0.004, false))
        assertEquals("≈ $1.20", costLabel(1.2, true))
    }
}
