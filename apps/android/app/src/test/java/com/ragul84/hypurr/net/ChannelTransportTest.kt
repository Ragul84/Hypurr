package com.ragul84.hypurr.net

import com.ragul84.hypurr.crypto.RelayCrypto
import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.model.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

class ChannelTransportTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val identity = DeviceIdentity(RelayCrypto.random(32), RelayCrypto.random(32))
    private val host = FakeHost(scope)

    @After
    fun tearDown() = scope.cancel()

    private fun pairingLink(urls: String = "http://192.0.2.1:19222", cloud: String = "") =
        "hypurr://pair?v=3&name=Studio&id=${host.computerId}&sk=${host.computer().signKey}&bk=${host.computer().boxKey}" +
            "&code=${host.pairingCode}&urls=$urls&cloud=$cloud"

    @Test
    fun pairsThenConnectsDirectlyAndCalls() = runBlocking {
        val pairing = Pairing.parse(pairingLink())
        val computer = withTimeout(10_000) { HostConnector.pair(pairing, identity, "Pixel", scope, host.dialer) }
        assertEquals(host.computerId, computer.id)
        val pair = host.requests.single { it["m"]?.jsonPrimitive?.content == "pair" }.getValue("b").jsonObject
        assertEquals("android", pair.getValue("platform").jsonPrimitive.content)
        assertEquals("Pixel", pair.getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), host.hellos.first()["pair"])
        assertTrue(identity.publicKey in host.authorized)

        host.handler = { _, m ->
            when (m["m"]?.jsonPrimitive?.content) {
                "hello" -> buildJsonObject {
                    put("name", "Renamed")
                    put("computerId", host.computerId)
                    put("urls", buildJsonArray { add(JsonPrimitive("http://192.0.2.9:19222")) })
                }
                "boom" -> null.also { }
                else -> buildJsonObject { put("echo", m.getValue("b")) }
            }
        }
        val t = HostConnector.connect(computer, identity, scope, host.dialer)
        withTimeout(5_000) { t.state.first { it == LinkState.Ready(Route.Direct) } }
        val echo = t.call("anything", buildJsonObject { put("x", 1) }).jsonObject
        assertEquals(1L, echo.getValue("echo").jsonObject.getValue("x").jsonPrimitive.long)
        // The inner hello merges the host's current name and addresses.
        val merged = withTimeout(5_000) { t.computer.first { it.name == "Renamed" } }
        assertEquals(listOf("http://192.0.2.9:19222"), merged.urls)
        t.shutdown()
    }

    @Test
    fun hostErrorsBecomeHttpErrorsAndTimeoutsCancel() = runBlocking {
        host.authorized += identity.publicKey
        var session: FakeHost.Session? = null
        host.handler = { s, m ->
            session = s
            when (m["m"]?.jsonPrimitive?.content) {
                "slow", "hello" -> null
                else -> null.also {
                    s.reply(buildJsonObject {
                        put("id", m.getValue("id"))
                        put("err", buildJsonObject {
                            put("status", 404)
                            put("message", "no such bot")
                        })
                    })
                }
            }
        }
        val t = HostConnector.connect(host.computer(), identity, scope, host.dialer)
        try {
            t.call("send", buildJsonObject { put("botId", "nope") })
            fail("expected an error")
        } catch (e: HostException) {
            assertEquals(HostException.Kind.Http, e.kind)
            assertEquals(404, e.status)
            assertEquals("no such bot", e.message)
        }
        try {
            t.call("slow", timeoutMs = 300)
            fail("expected a timeout")
        } catch (e: HostException) {
            assertEquals(HostException.Kind.Unreachable, e.kind)
        }
        withTimeout(2_000) { while (host.requests.none { it["cancel"] != null }) kotlinx.coroutines.delay(10) }
        assertTrue(session != null)
        t.shutdown()
    }

    @Test
    fun eventStreamDeliversUntilEnd() = runBlocking {
        host.authorized += identity.publicKey
        host.handler = { s, m ->
            if (m["sub"]?.jsonPrimitive?.content == "events") {
                val id = m.getValue("id").jsonPrimitive.long
                assertEquals("android", m.getValue("b").jsonObject.getValue("client").jsonPrimitive.content)
                s.event(id, buildJsonObject {
                    put("type", "hello")
                    put("rev", 7)
                })
                s.event(id, buildJsonObject {
                    put("type", "bot")
                    put("bot", buildJsonObject {
                        put("id", "b1")
                        put("name", "Reviewer")
                        put("status", "needsInput")
                        put("rev", 8)
                        put("someFutureField", true)
                    })
                })
                s.reply(buildJsonObject {
                    put("id", id)
                    put("end", true)
                })
                buildJsonObject { }
            } else null
        }
        val t = HostConnector.connect(host.computer(), identity, scope, host.dialer)
        val events = withTimeout(5_000) { HostClient(t).events(0).take(2).toList() }
        assertEquals(HostEvent.Hello("", 7), events[0])
        val bot = (events[1] as HostEvent.BotChanged).bot
        assertTrue(bot.needsInput)
        t.shutdown()
    }

    @Test
    fun relayWaitsForPresenceThenHandshakes() = runBlocking {
        host.authorized += identity.publicKey
        host.hostOnline = false
        host.handler = { _, _ -> buildJsonObject { put("pong", true) } }
        val t = HostConnector.connect(host.computer(urls = emptyList(), cloud = "https://relay.example.dev"), identity, scope, host.dialer)
        val offline = withTimeout(5_000) { t.state.first { it is LinkState.HostOffline } }
        assertEquals(1_790_000_000_000, (offline as LinkState.HostOffline).lastSeen)
        val endpoint = host.relayEndpoints.single()
        val url = URI(endpoint.url)
        assertEquals("/v1/relay/device/${host.computerId}", url.path)
        assertEquals("v=1", url.query)
        assertTrue(endpoint.signature.startsWith("v=1,kid=${identity.publicKey},ts="))
        // Offline: calls fail fast with Offline, so the store can queue in the mailbox.
        try {
            t.call("hello", timeoutMs = 1000)
            fail("expected offline")
        } catch (e: HostException) {
            assertEquals(HostException.Kind.Offline, e.kind)
        }
        t.shutdown()
    }

    @Test
    fun relayPairingCarriesTheOfferId() = runBlocking {
        val pairing = Pairing.parse(pairingLink(urls = "", cloud = "https://relay.example.dev"))
        withTimeout(10_000) { HostConnector.pair(pairing, identity, "Pixel", scope, host.dialer) }
        val query = URI(host.relayEndpoints.first().url).query
        assertEquals("v=1&pair=${pairing.offerId}", query)
    }

    @Test
    fun forgedWelcomeMeansTheIdentityChanged() = runBlocking {
        host.authorized += identity.publicKey
        host.forgeWelcome = true
        val t = HostConnector.connect(host.computer(), identity, scope, host.dialer)
        val state = withTimeout(5_000) { t.state.first { it is LinkState.Unauthorized } }
        assertEquals(ChannelTransport.IDENTITY_CHANGED, (state as LinkState.Unauthorized).message)
        assertTrue(t.isStopped)
    }

    @Test
    fun unsupportedVersionAsksForAnUpdate() = runBlocking {
        host.rejectWith = "unsupportedVersion"
        val pairing = Pairing.parse(pairingLink())
        try {
            withTimeout(10_000) { HostConnector.pair(pairing, identity, "Pixel", scope, host.dialer) }
            fail("expected upgrade")
        } catch (e: HostException) {
            assertEquals(HostException.Kind.UpgradeRequired, e.kind)
        }
    }

    @Test
    fun unauthorizedDeviceWithoutRelayKeepsWaiting() = runBlocking {
        val t = HostConnector.connect(host.computer(), identity, scope, host.dialer)
        val state = withTimeout(5_000) { t.state.first { it is LinkState.Unauthorized } }
        assertTrue(state is LinkState.Unauthorized)
        // An unsigned reject from a LAN address never ends a saved computer for good.
        assertFalse(t.isStopped)
        t.shutdown()
    }

    @Test
    fun channelUrls() {
        assertEquals("ws://192.168.1.4:19222/channel?v=1", ChannelTransport.channelUrl("http://192.168.1.4:19222"))
        assertEquals("wss://mac.tail1234.ts.net/channel?v=1", ChannelTransport.channelUrl("https://mac.tail1234.ts.net"))
        assertEquals(null, ChannelTransport.channelUrl("ftp://x"))
    }
}
