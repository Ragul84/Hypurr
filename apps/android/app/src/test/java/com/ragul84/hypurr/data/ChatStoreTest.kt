package com.ragul84.hypurr.data

import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.HypurrJson
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Threads, files, reactions and bots through the store against the in-memory host, with real host payloads. */
class ChatStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = FakeHost(scope)
    private val wire = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.chatWire")!!).readText()).jsonObject

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun threadsFilesReactionsAndBots() = runBlocking {
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val bot = wire.getValue("bot").jsonObject
        val group = wire.getValue("group").jsonObject
        val botId = bot.getValue("id").jsonPrimitive.content
        val root = wire.getValue("root").jsonObject
        val rootId = root.getValue("id").jsonPrimitive.content
        val thread = wire.getValue("thread").jsonArray
        host.handler = { _, m ->
            val method = m["m"]?.jsonPrimitive?.content ?: m["sub"]?.jsonPrimitive?.content ?: ""
            val b = m["b"]?.jsonObject ?: JsonObject(emptyMap())
            calls += method to b
            when (method) {
                "sync" -> buildJsonObject {
                    put("rev", 100)
                    put("bots", buildJsonArray { add(bot); add(group) })
                    // Catch-up carries thread replies too: they must stay out of the main chat.
                    put("entries", buildJsonArray { add(root); thread.forEach { add(it) } })
                }
                "history" -> buildJsonObject { put("entries", buildJsonArray { add(root) }) }
                "thread" -> buildJsonObject { put("entries", thread) }
                "upload" -> buildJsonObject {
                    put("attachment", buildJsonObject {
                        put("id", b.getValue("uploadId"))
                        put("name", b.getValue("name"))
                    })
                }
                "send" -> buildJsonObject {
                    put("entry", HypurrJson.parseToJsonElement(wire.getValue("fileMessage").toString().replace("\"c3\"", "\"${b.getValue("clientNonce").jsonPrimitive.content}\"")))
                }
                "readUpload" -> wire.getValue("readUpload")
                "react" -> buildJsonObject { put("entry", root) }
                "createBot" -> buildJsonObject { put("bot", group) }
                else -> buildJsonObject { }
            }
        }
        val store = HypurrStore(scope, MemoryPersistence(), DeviceIdentity.load(MemorySecrets()), "Pixel", host.dialer)
        val link = "hypurr://pair?v=3&name=Studio&id=${host.computerId}&sk=${host.computer().signKey}&bk=${host.computer().boxKey}" +
            "&code=${host.pairingCode}&urls=http://192.0.2.1:19222"
        withTimeout(10_000) { store.pair(link) }
        withTimeout(5_000) { store.synced.first { it } }

        assertTrue(store.bots.value.getValue(group.getValue("id").jsonPrimitive.content).isGroup)
        assertEquals(listOf(rootId), store.entries.value[botId].orEmpty().map { it.id })
        assertEquals(2, store.threads.value[rootId].orEmpty().size)

        store.openThread(botId, rootId)
        val read = calls.last { it.first == "markRead" }.second
        assertEquals(rootId, read.getValue("threadId").jsonPrimitive.content)

        // Files go up in chunks first, then the message names them; the sender keeps its picture.
        val png = ByteArray(500_000) { (it % 251).toByte() }
        store.send(botId, "look", threadId = null, files = listOf(PickedFile("shot.png", png, "image/png")))
        val uploads = calls.filter { it.first == "upload" }.map { it.second }
        assertEquals(2, uploads.size)
        assertEquals(393_216L, uploads[1].getValue("offset").jsonPrimitive.content.toLong())
        val send = calls.last { it.first == "send" }.second
        val id = uploads[0].getValue("uploadId").jsonPrimitive.content
        assertEquals(listOf(id), send.getValue("attachments").jsonArray.map { it.jsonPrimitive.content })
        assertArrayEquals(png, store.files.value.getValue(id))
        assertTrue(store.entries.value.getValue(botId).any { it.data.attachments?.firstOrNull()?.name == "shot.png" && it.seq > 0 })

        // A reply in a thread carries threadId.
        store.send(botId, "and then?", threadId = rootId)
        assertEquals(rootId, calls.last { it.first == "send" }.second.getValue("threadId").jsonPrimitive.content)

        // Someone else's picture is fetched once for its preview.
        store.loadAttachment(botId, Attachment("1fbdcea6-c025-4c36-9571-251fb7617b6d", "shot.png", 1000))
        assertEquals(1000, store.files.value.getValue("1fbdcea6-c025-4c36-9571-251fb7617b6d").size)
        store.loadAttachment(botId, Attachment("1fbdcea6-c025-4c36-9571-251fb7617b6d", "shot.png", 1000))
        assertEquals(1, calls.count { it.first == "readUpload" })

        store.react(rootId, "👍")
        assertEquals("👍", calls.last { it.first == "react" }.second.getValue("emoji").jsonPrimitive.content)

        val created = store.createBot(buildJsonObject {
            put("kind", "group")
            put("name", "Shop team")
            put("members", buildJsonArray { group.getValue("members").jsonArray.forEach { add(it) } })
        })
        assertTrue(created.isGroup)
        store.deleteBot(created.id)
        assertTrue(created.id !in store.bots.value)
        assertEquals(created.id, calls.last { it.first == "deleteBot" }.second.getValue("botId").jsonPrimitive.content)
    }
}
