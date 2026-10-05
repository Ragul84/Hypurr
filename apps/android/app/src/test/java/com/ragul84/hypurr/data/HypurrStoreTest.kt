package com.ragul84.hypurr.data

import com.ragul84.hypurr.crypto.RelayCrypto
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HypurrStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = FakeHost(scope)
    private val persistence = MemoryPersistence()
    private val identity = DeviceIdentity.load(MemorySecrets())

    @After
    fun tearDown() = scope.cancel()

    private fun bot(id: String, rev: Long, status: String = "idle") = buildJsonObject {
        put("id", id)
        put("name", "Reviewer")
        put("status", status)
        put("rev", rev)
    }

    private fun entry(id: String, seq: Long, kind: String, text: String, nonce: String? = null, final: Boolean = true) = buildJsonObject {
        put("id", id)
        put("seq", seq)
        put("botId", "b1")
        put("rev", seq + 10)
        put("kind", kind)
        put("data", buildJsonObject {
            put("text", text)
            put("final", final)
            nonce?.let { put("clientNonce", it) }
        })
    }

    @Test
    fun pairSyncFollowAndSend() = runBlocking {
        var events: Pair<FakeHost.Session, Long>? = null
        var registered: JsonObject? = null
        host.handler = { s, m ->
            when (m["m"]?.jsonPrimitive?.content ?: m["sub"]?.jsonPrimitive?.content) {
                "sync" -> buildJsonObject {
                    put("hostId", "h")
                    put("rev", 5)
                    put("bots", buildJsonArray { add(bot("b1", 5, "needsInput")) })
                    put("entries", buildJsonArray { add(entry("e1", 1, "agent", "Hello from the computer")) })
                }
                "events" -> buildJsonObject { }.also { events = s to m.getValue("id").jsonPrimitive.long }
                "send" -> {
                    val b = m.getValue("b").jsonObject
                    buildJsonObject {
                        put("entry", entry("e2", 2, "user", b.getValue("text").jsonPrimitive.content, b.getValue("clientNonce").jsonPrimitive.content))
                    }
                }
                "registerDevice" -> buildJsonObject { }.also { registered = m.getValue("b").jsonObject }
                else -> buildJsonObject { }
            }
        }
        val store = HypurrStore(scope, persistence, identity, "Pixel", host.dialer)
        store.onConnected = { it.registerDevice("ticket", "https://relay", "Pixel", identity.pushKey, "local") }
        val link = "hypurr://pair?v=3&name=Studio&id=${host.computerId}&sk=${host.computer().signKey}&bk=${host.computer().boxKey}" +
            "&code=${host.pairingCode}&urls=http://192.0.2.1:19222"
        withTimeout(10_000) { store.pair(link) }
        // The paired computer survives a relaunch.
        assertEquals(host.computerId, HypurrStore(scope, persistence, identity, "Pixel", host.dialer).computer.value?.id)

        withTimeout(5_000) { store.synced.first { it } }
        assertEquals("needsInput", store.bots.value.getValue("b1").status)
        assertEquals("Hello from the computer", store.entries.value.getValue("b1").single().data.text)
        withTimeout(5_000) { while (events == null || registered == null) kotlinx.coroutines.delay(10) }
        assertEquals(identity.pushKey, registered!!.getValue("pushKey").jsonPrimitive.content)

        // Live events upsert by id.
        val (session, id) = events!!
        session.event(id, buildJsonObject {
            put("type", "bot")
            put("bot", bot("b1", 6, "working"))
        })
        withTimeout(5_000) { store.bots.first { it["b1"]?.status == "working" } }

        // Sending replaces the optimistic copy with the host's entry (same clientNonce).
        store.send("b1", "  run the tests  ")
        val chat = store.entries.value.getValue("b1")
        assertEquals(listOf("e1", "e2"), chat.map { it.id })
        assertEquals("run the tests", chat.last().data.text)

        // A deleted bot leaves the roster.
        session.event(id, buildJsonObject {
            put("type", "bot")
            put("bot", buildJsonObject {
                put("id", "b1")
                put("deleted", true)
                put("rev", 9)
            })
        })
        withTimeout(5_000) { store.bots.first { "b1" !in it } }

        store.forget()
        assertNull(store.computer.value)
        assertNull(persistence.read("computer"))
    }

    @Test
    fun settingsPersist() {
        val store = HypurrStore(scope, persistence, identity, "Pixel", host.dialer)
        store.setTheme(ThemeMode.Dark)
        store.setDynamicColor(true)
        val again = HypurrStore(scope, persistence, identity, "Pixel", host.dialer)
        assertEquals(ThemeMode.Dark, again.themeMode.value)
        assertEquals(true, again.dynamicColor.value)
        assertEquals(true, again.notifications.value)
    }

    @Test
    fun identityIsCreatedOnceAndReloaded() {
        val secrets = MemorySecrets()
        val a = DeviceIdentity.load(secrets)
        val b = DeviceIdentity.load(secrets)
        assertEquals(a.publicKey, b.publicKey)
        assertEquals(a.pushKey, b.pushKey)
        assertEquals(43, a.publicKey.length)
        DeviceIdentity.delete(secrets)
        assert(DeviceIdentity.load(secrets).publicKey != a.publicKey)
        assert(RelayCrypto.ed25519Verify(com.ragul84.hypurr.crypto.B64.decode(a.publicKey)!!, "x".toByteArray(), a.sign("x".toByteArray())))
    }
}
