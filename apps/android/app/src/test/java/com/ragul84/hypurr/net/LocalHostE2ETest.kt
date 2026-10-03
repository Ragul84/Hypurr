package com.ragul84.hypurr.net

import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.data.MemorySecrets
import com.ragul84.hypurr.model.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * End to end against a real `hypurr-host` (apps/android/scripts/e2e-local-host.sh starts one and
 * passes a fresh pairing link): pair over the real `/channel` WebSocket with OkHttp, reconnect as the
 * paired device, then sync, follow events and send. Skipped when HYPURR_E2E_LINK isn't set.
 */
class LocalHostE2ETest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun pairSyncSendAndFollowEvents() = runBlocking {
        val link = System.getenv("HYPURR_E2E_LINK")
        assumeTrue("HYPURR_E2E_LINK not set", !link.isNullOrBlank())
        val identity = DeviceIdentity.load(MemorySecrets())
        val pairing = Pairing.parse(link!!)
        val computer = withTimeout(30_000) { HostConnector.pair(pairing, identity, "Android E2E", scope) }
        assertEquals(pairing.computer.id, computer.id)
        println("e2e: paired with ${computer.name} (${computer.id}) via ${computer.urls}")

        val t = HostConnector.connect(computer, identity, scope)
        val state = withTimeout(15_000) { t.state.first { it is LinkState.Ready || it is LinkState.Unauthorized || it is LinkState.Failed } }
        assertEquals(LinkState.Ready(Route.Direct), state)
        val client = HostClient(t)
        val hello = client.hello()
        assertEquals(computer.id, hello.computerId)
        println("e2e: hello ${hello.name} ${hello.version} on ${hello.os}")
        val sync = client.sync(0)
        println("e2e: sync rev=${sync.rev} bots=${sync.bots.map { it.name }}")

        // Push registration path: the host accepts this device's ticket and push key.
        client.registerDevice("e2e-ticket-${UUID.randomUUID()}", "https://hypurr-relay.example.workers.dev", "Android E2E", identity.pushKey, "local")

        val botId = System.getenv("HYPURR_E2E_BOT")?.takeIf { it.isNotBlank() } ?: sync.bots.firstOrNull()?.id
        if (botId != null) {
            val nonce = UUID.randomUUID().toString().uppercase()
            val seen = scope.async {
                client.events(sync.rev).filterIsInstance<HostEvent.EntryChanged>()
                    .first { ev: HostEvent.EntryChanged -> ev.entry.data.clientNonce == nonce }
            }
            kotlinx.coroutines.delay(300)
            val sent = client.send(botId, "Hello from the Android E2E test", nonce)
            assertEquals("user", sent.kind)
            val event = withTimeout(15_000) { seen.await() }
            assertEquals(sent.id, event.entry.id)
            val history = client.history(botId)
            assertTrue(history.any { it.id == sent.id })
            println("e2e: sent ${sent.id} to $botId, saw it on the event stream and in history (${history.size} entries)")
        }
        t.shutdown()
    }
}
