package com.ragul84.hypurr.data

import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.net.FakeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The task flow through the store against the in-memory host: setup, start, undo, safety settings. */
class TaskStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = FakeHost(scope)
    private val wire = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.taskWire")!!).readText()).jsonObject

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun startUndoAndSafety() = runBlocking {
        val calls = mutableMapOf<String, JsonObject>()
        var uploadCalls = 0
        val bot = wire.getValue("bot").jsonObject
        val botId = bot.getValue("id").jsonPrimitive.content
        host.handler = { _, m ->
            val method = m["m"]?.jsonPrimitive?.content ?: m["sub"]?.jsonPrimitive?.content ?: ""
            m["b"]?.jsonObject?.let { synchronized(calls) { calls[method] = it } }
            if (method == "upload") uploadCalls++
            when (method) {
                "upload" -> buildJsonObject {
                    put("attachment", buildJsonObject {
                        put("id", m["b"]!!.jsonObject.getValue("uploadId"))
                        put("name", "shot.png")
                    })
                }
                "sync" -> buildJsonObject {
                    put("rev", 1)
                    put("bots", buildJsonArray { })
                    put("entries", buildJsonArray { })
                }
                "taskSetup" -> wire.getValue("setup").jsonObject
                "routeTask" -> buildJsonObject { put("route", wire.getValue("route")) }
                "startTask" -> buildJsonObject { put("bot", bot) }
                "rollbackTask" -> buildJsonObject { put("task", bot.getValue("task")) }
                "setSafetySettings" -> buildJsonObject {
                    put("safety", HypurrJson.encodeToJsonElement(SafetySettings.serializer(), SafetySettings(alwaysAskHigh = false)))
                }
                else -> buildJsonObject { }
            }
        }
        val store = HypurrStore(scope, MemoryPersistence(), DeviceIdentity.load(MemorySecrets()), "Pixel", host.dialer)
        val link = "hypurr://pair?v=3&name=Studio&id=${host.computerId}&sk=${host.computer().signKey}&bk=${host.computer().boxKey}" +
            "&code=${host.pairingCode}&urls=http://192.0.2.1:19222"
        withTimeout(10_000) { store.pair(link) }
        withTimeout(5_000) { store.synced.first { it } }

        val setup = store.loadSetup()!!
        assertEquals(5, setup.templates.size)
        val route = store.route("fix the tests", "write-tests")!!
        assertTrue(route.reason.isNotEmpty())
        assertEquals("write-tests", calls.getValue("routeTask").getValue("template").jsonPrimitive.content)

        val id = store.startTask("the checkout button", "write-tests", "", "/Users/priya/shop", "claude")
        assertEquals(botId, id)
        assertTrue(store.bots.value.getValue(id).task!!.hasSafetyNet)
        val start = calls.getValue("startTask")
        assertEquals("/Users/priya/shop", start.getValue("project").jsonPrimitive.content)
        assertEquals("claude", start.getValue("backend").jsonPrimitive.content)
        assertTrue("input" !in start)

        // Undo sends the card's checkpoint for this bot's task.
        val cp = wire.getValue("pendingCard").jsonObject.getValue("data").jsonObject.getValue("checkpoint").jsonPrimitive.content
        store.rollback(id, cp)
        val rb = calls.getValue("rollbackTask")
        assertEquals(store.bots.value.getValue(id).task!!.id, rb.getValue("taskId").jsonPrimitive.content)
        assertEquals(cp, rb.getValue("checkpointId").jsonPrimitive.content)

        // A screenshot and an issue: the file goes up as a draft first, then the task names it.
        val issue = com.ragul84.hypurr.model.Issue("github", "#142", "Checkout button does nothing", project = "/Users/priya/shop")
        store.startTask("", "fix-error", "", null, "claude", listOf(PickedFile("shot.png", ByteArray(500_000) { 7 }, "image/png")), issue)
        val uploads = synchronized(calls) { calls.getValue("upload") }
        val fromTask = calls.getValue("startTask")
        assertEquals(fromTask.getValue("draftId"), uploads.getValue("draftId"))
        assertEquals(uploads.getValue("uploadId"), fromTask.getValue("attachments").jsonArray.single())
        assertEquals("#142", fromTask.getValue("issue").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals(2, uploadCalls)

        store.finishTask(id, openPr = true, notify = false, learning = true)
        val fin = calls.getValue("finishTask")
        assertEquals("true", fin.getValue("openPr").jsonPrimitive.content)
        assertEquals("false", fin.getValue("notify").jsonPrimitive.content)

        store.setSafety(SafetySettings(alwaysAskHigh = false))
        assertEquals(false, calls.getValue("setSafetySettings").getValue("alwaysAskHigh").jsonPrimitive.content.toBoolean())
        assertEquals(false, store.setup.value!!.safety.alwaysAskHigh)
    }
}
