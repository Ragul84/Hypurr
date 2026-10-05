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
        val project = System.getenv("HYPURR_E2E_PROJECT")?.takeIf { it.isNotBlank() }
        val agent = System.getenv("HYPURR_E2E_AGENT")?.takeIf { it.isNotBlank() }
        if (project != null && agent != null) beginnerTask(client, project, agent)
        t.shutdown()
    }

    /** Stage A over the encrypted channel: setup, route, start a task, explained cards, approve, checkpoint, rollback. */
    private suspend fun beginnerTask(client: HostClient, project: String, agent: String) {
        val setup = client.taskSetup()
        assertEquals(5, setup.templates.count { it.builtin })
        val route = client.routeTask("write tests for the shop checkout", "write-tests")
        println("e2e: route project=${route.project?.name} reason=\"${route.reason}\"")
        val rev = client.sync(0).rev
        val cards = scope.async {
            client.events(rev).filterIsInstance<HostEvent.EntryChanged>()
                .first { it.entry.kind == "permission" && it.entry.data.status == "pending" }
        }
        kotlinx.coroutines.delay(300)
        val bot = client.startTask("the checkout button", "write-tests", "", project, "custom", command = agent)
        val task = bot.task!!
        assertTrue(task.hasSafetyNet)
        println("e2e: task ${task.id} on ${task.branch} in ${task.worktree}")
        val card = withTimeout(20_000) { cards.await() }.entry
        assertEquals("high", card.data.risk)
        println("e2e: card risk=${card.data.risk} explain=\"${card.data.explain}\" checkpoint=${card.data.checkpoint}")
        val blocked = client.history(bot.id).first { it.data.blocked != null }
        println("e2e: blocked=\"${blocked.data.blocked}\"")
        client.respondPermission(card.id, "allow")
        val after = withTimeout(20_000) {
            var t: com.ragul84.hypurr.model.TaskInfo? = null
            while (t == null) {
                t = client.sync(0).bots.firstOrNull { it.id == bot.id }?.task?.takeIf { tk -> tk.checkpoints.any { it.label.startsWith("After step") } }
                if (t == null) kotlinx.coroutines.delay(200)
            }
            t
        }
        println("e2e: checkpoints=${after.checkpoints.map { it.label }}")
        val back = client.rollbackTask(task.id, task.checkpoints.first().id)
        assertTrue(back.checkpoints.any { it.label.startsWith("Went back") || it.label == "Before going back" })
        println("e2e: rolled back; checkpoints now ${back.checkpoints.size}")
        client.finishTask(task.id)
    }
}
