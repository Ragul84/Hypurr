package com.ragul84.hypurr.data

import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.net.FakeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Team admin through the store against the in-memory host: your role, the screen's data, edits. */
class AdminStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = FakeHost(scope)
    private val wire = HypurrJson.parseToJsonElement(File(System.getProperty("hypurr.adminWire")!!).readText()).jsonObject

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun roleTeamPoliciesAndAudit() = runBlocking {
        val calls = mutableMapOf<String, JsonObject>()
        val seen = mutableListOf<String>()
        host.handler = { _, m ->
            val method = m["m"]?.jsonPrimitive?.content ?: m["sub"]?.jsonPrimitive?.content ?: ""
            synchronized(calls) {
                seen += method
                m["b"]?.jsonObject?.let { calls[method] = it }
            }
            when (method) {
                "sync" -> buildJsonObject {
                    put("rev", 1)
                    put("bots", buildJsonArray { })
                    put("entries", buildJsonArray { })
                }
                "hello" -> wire.getValue("hello").jsonObject
                "team", "setRole", "setTeam" -> wire.getValue("team").jsonObject
                "policies", "setPolicies" -> wire.getValue("policies").jsonObject
                "activity" -> wire.getValue("activity").jsonObject
                "auditLog" -> wire.getValue("auditLog").jsonObject
                else -> buildJsonObject { }
            }
        }
        val store = HypurrStore(scope, MemoryPersistence(), DeviceIdentity.load(MemorySecrets()), "Pixel", host.dialer)
        val link = "hypurr://pair?v=3&name=Studio&id=${host.computerId}&sk=${host.computer().signKey}&bk=${host.computer().boxKey}" +
            "&code=${host.pairingCode}&urls=http://192.0.2.1:19222"
        withTimeout(10_000) { store.pair(link) }
        withTimeout(5_000) { store.synced.first { it } }
        // The phone learns its role from hello after syncing.
        val you = withTimeout(5_000) { store.you.first { it != null } }!!
        assertEquals("viewer", you.role)

        // The team endpoint is the source of truth (here: the computer's admin view).
        store.loadAdmin()
        assertEquals("admin", store.you.value!!.role)
        assertEquals(3, store.team.value!!.people.size)
        assertEquals("low", store.policies.value!!.policies.adminApprovesFrom)
        assertTrue(store.audit.value!!.intact)
        assertTrue(store.activity.value!!.people.isNotEmpty())

        store.setPolicies(buildJsonObject { put("dailyLimit", 5.0) })
        assertEquals(5.0, calls.getValue("setPolicies").getValue("dailyLimit").jsonPrimitive.content.toDouble(), 1e-9)
        store.setRole("abc", null)
        assertEquals(JsonNull, calls.getValue("setRole")["role"])
        store.setRole("abc", "member")
        assertEquals("member", calls.getValue("setRole").getValue("role").jsonPrimitive.content)
        store.setDefaultRole("viewer")
        assertEquals("viewer", calls.getValue("setTeam").getValue("defaultRole").jsonPrimitive.content)

        store.forget()
        assertNull(store.you.value)
    }
}
