package com.ragul84.hypurr.data

import com.ragul84.hypurr.model.Activity
import com.ragul84.hypurr.model.Actor
import com.ragul84.hypurr.model.AuditLog
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.PolicyInfo
import com.ragul84.hypurr.model.TeamInfo
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.EntryData
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate
import com.ragul84.hypurr.net.ChannelTransport
import com.ragul84.hypurr.net.Dialer
import com.ragul84.hypurr.net.HostClient
import com.ragul84.hypurr.net.HostConnector
import com.ragul84.hypurr.net.HostEvent
import com.ragul84.hypurr.net.HostException
import com.ragul84.hypurr.net.LinkState
import com.ragul84.hypurr.net.OkHttpSocket
import com.ragul84.hypurr.net.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** A screenshot, photo or file picked on the phone, waiting to go with a task. */
class PickedFile(val name: String, val bytes: ByteArray, val mime: String = "application/octet-stream") {
    val isImage: Boolean get() = mime.startsWith("image/")
    val size: Long get() = bytes.size.toLong()
}

enum class ThemeMode { System, Light, Dark }

/** What the app keeps between launches (SharedPreferences on the phone, memory in tests). */
interface Persistence {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class MemoryPersistence : Persistence {
    private val map = mutableMapOf<String, String>()
    override fun read(key: String) = map[key]
    override fun write(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/**
 * The app's model (kit HypurrUI BotStore): the paired computer, its channel, the roster and the
 * chats. Every mutation goes to the host; the event stream brings the result back (CLAUDE.md "Sync").
 */
class HypurrStore(
    private val scope: CoroutineScope,
    private val persistence: Persistence,
    val identity: DeviceIdentity,
    private val deviceName: String,
    private val dial: Dialer = OkHttpSocket.dial,
) {
    private val _computer = MutableStateFlow(persistence.read(COMPUTER)?.let {
        runCatching { HypurrJson.decodeFromString<Computer>(it) }.getOrNull()
    })
    val computer: StateFlow<Computer?> = _computer.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _bots = MutableStateFlow<Map<String, Bot>>(emptyMap())
    val bots: StateFlow<Map<String, Bot>> = _bots.asStateFlow()

    private val _entries = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val entries: StateFlow<Map<String, List<Entry>>> = _entries.asStateFlow()

    private val _synced = MutableStateFlow(false)
    /** The first sync finished: the roster is real, not just empty. */
    val synced: StateFlow<Boolean> = _synced.asStateFlow()

    val themeMode = MutableStateFlow(persistence.read(THEME)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System)
    val dynamicColor = MutableStateFlow(persistence.read(DYNAMIC) == "1")
    val notifications = MutableStateFlow(persistence.read(NOTIFY) != "0")

    var transport: ChannelTransport? = null
        private set
    val client: HostClient? get() = transport?.let(::HostClient)
    private var jobs = listOf<Job>()
    private var rev = 0L

    /** Runs after every handshake on a fresh channel (push registration). */
    var onConnected: (suspend (HostClient) -> Unit)? = null

    fun setTheme(mode: ThemeMode) {
        themeMode.value = mode
        persistence.write(THEME, mode.name)
    }

    fun setDynamicColor(on: Boolean) {
        dynamicColor.value = on
        persistence.write(DYNAMIC, if (on) "1" else "0")
    }

    fun setNotifications(on: Boolean) {
        notifications.value = on
        persistence.write(NOTIFY, if (on) "1" else "0")
        scope.launch {
            val c = client ?: return@launch
            runCatching { if (on) onConnected?.invoke(c) else c.unregisterDevice() }
        }
    }

    // MARK: pairing and the channel

    suspend fun pair(link: String): Computer {
        val pairing = Pairing.parse(link)
        val computer = HostConnector.pair(pairing, identity, deviceName, scope, dial)
        save(computer)
        connect()
        return computer
    }

    private fun save(computer: Computer?) {
        _computer.value = computer
        persistence.write(COMPUTER, computer?.let { HypurrJson.encodeToString(Computer.serializer(), it) })
    }

    fun connect() {
        val computer = _computer.value ?: return
        disconnect()
        val t = HostConnector.connect(computer, identity, scope, dial)
        transport = t
        jobs = listOf(
            scope.launch { t.state.collect { _link.value = it } },
            scope.launch { t.computer.collect { if (it != _computer.value) save(it) } },
            scope.launch { follow(t) },
        )
    }

    fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        transport?.shutdown()
        transport = null
    }

    fun reconnect() = transport?.reconnect() ?: connect()

    /** Removes the computer from this phone: its access goes with it. */
    suspend fun forget() {
        runCatching { client?.unregisterDevice() }
        disconnect()
        save(null)
        _bots.value = emptyMap()
        _entries.value = emptyMap()
        _synced.value = false
        rev = 0
        _you.value = null
    }

    /** Sync, then follow the event stream; a dropped channel resubscribes from the last rev. */
    private suspend fun follow(t: ChannelTransport) {
        val c = HostClient(t)
        while (true) {
            try {
                t.state.first { it is LinkState.Ready }
                val sync = c.sync(rev)
                if (rev == 0L) _bots.value = emptyMap()
                sync.bots.forEach(::upsert)
                sync.entries.forEach(::upsert)
                rev = maxOf(rev, sync.rev)
                _synced.value = true
                runCatching { c.hello() }.getOrNull()?.you?.let { _you.value = it }
                if (notifications.value) runCatching { onConnected?.invoke(c) }
                c.events(rev).collect(::apply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(1000)
            }
        }
    }

    internal fun apply(event: HostEvent) {
        when (event) {
            is HostEvent.Hello -> Unit
            is HostEvent.BotChanged -> upsert(event.bot)
            is HostEvent.BotDeleted -> {
                _bots.update { it - event.id }
                rev = maxOf(rev, event.rev)
            }
            is HostEvent.EntryChanged -> upsert(event.entry)
            // Never skip what didn't decode: start over from rev 0 (as the iOS app does).
            HostEvent.Resync, is HostEvent.Undecodable -> {
                rev = 0
                throw HostException.unreachable()
            }
            is HostEvent.Other -> event.rev?.let { rev = maxOf(rev, it) }
        }
    }

    private fun upsert(bot: Bot) {
        rev = maxOf(rev, bot.rev)
        if (bot.deleted == true) _bots.update { it - bot.id } else _bots.update { it + (bot.id to bot) }
    }

    private fun upsert(entry: Entry) {
        rev = maxOf(rev, entry.rev)
        if (entry.threadId != null) return
        _entries.update { all ->
            val list = all[entry.botId].orEmpty()
            // A sent message replaces its optimistic copy (same clientNonce).
            val kept = list.filter { it.id != entry.id && !(entry.data.clientNonce != null && it.data.clientNonce == entry.data.clientNonce) }
            all + (entry.botId to (kept + entry).sortedWith(compareBy({ it.seq == 0L }, { it.seq }, { it.createdAt })))
        }
    }

    // MARK: chat actions

    suspend fun openChat(botId: String) {
        val c = client ?: return
        runCatching { c.history(botId).forEach(::upsert) }
        runCatching { c.markRead(botId) }
    }

    /** Sends now, or queues it in the relay mailbox while the computer is offline. */
    suspend fun send(botId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val nonce = UUID.randomUUID().toString().uppercase()
        val now = System.currentTimeMillis()
        val local = Entry(id = "local-$nonce", botId = botId, kind = "user", createdAt = now, updatedAt = now,
            data = EntryData(text = trimmed, status = "queued", clientNonce = nonce))
        upsertLocal(local)
        val t = transport
        try {
            val c = client ?: throw HostException.unreachable()
            upsert(c.send(botId, trimmed, nonce))
        } catch (e: HostException) {
            val queued = e.kind == HostException.Kind.Offline && t != null && runCatching { t.enqueue(botId, trimmed, nonce) }.isSuccess
            upsertLocal(local.copy(data = local.data.copy(status = if (queued) "queued" else "failed")))
            if (!queued) throw e
        }
    }

    private fun upsertLocal(entry: Entry) = _entries.update { all ->
        all + (entry.botId to (all[entry.botId].orEmpty().filter { it.id != entry.id } + entry))
    }

    suspend fun stop(botId: String) = client?.stop(botId)

    suspend fun respond(entryId: String, optionId: String?) = client?.respondPermission(entryId, optionId)

    // MARK: tasks and the safety net

    private val _setup = MutableStateFlow<TaskSetup?>(null)
    /** Templates, projects, agents and safety settings for the New task screen. */
    val setup: StateFlow<TaskSetup?> = _setup.asStateFlow()

    suspend fun loadSetup(): TaskSetup? {
        val c = client ?: return _setup.value
        return runCatching { c.taskSetup() }.getOrNull()?.also { _setup.value = it } ?: _setup.value
    }

    suspend fun route(goal: String, template: String?): TaskRoute? = client?.routeTask(goal, template)

    /**
     * Starts a task and returns its bot id (the chat to open). Files go up first as a draft
     * (the bot doesn't exist yet); the host moves them into the task's chat.
     */
    suspend fun startTask(goal: String, template: String?, input: String, project: String?, backend: String?,
                          files: List<PickedFile> = emptyList(), issue: Issue? = null): String {
        val c = client ?: throw HostException.unreachable()
        val draft = if (files.isEmpty()) null else UUID.randomUUID().toString()
        val ids = files.map { f ->
            val id = UUID.randomUUID().toString()
            c.upload(id, f.name, f.bytes, draftId = draft)
            id
        }
        val bot = c.startTask(goal, template, input, project, backend, ids, draftId = draft, issue = issue)
        upsert(bot)
        return bot.id
    }

    /** Open GitHub issues and Jira tickets to start a task from. */
    suspend fun issues(): IssueList = client?.issues() ?: IssueList()

    // MARK: work tools and costs

    private val _integrations = MutableStateFlow<Integrations?>(null)
    val integrations: StateFlow<Integrations?> = _integrations.asStateFlow()

    suspend fun loadIntegrations(): Integrations? {
        val c = client ?: return _integrations.value
        return runCatching { c.integrations() }.getOrNull()?.also { _integrations.value = it } ?: _integrations.value
    }

    suspend fun setIntegrations(patch: JsonObject) {
        val saved = client?.setIntegrations(patch) ?: throw HostException.unreachable()
        _integrations.value = saved
        _setup.update { it?.copy(integrations = saved) }
    }

    suspend fun testIntegration(kind: String): String = client?.testIntegration(kind) ?: throw HostException.unreachable()

    private val _costs = MutableStateFlow<TaskCosts?>(null)
    val costs: StateFlow<TaskCosts?> = _costs.asStateFlow()

    suspend fun loadCosts(): TaskCosts? {
        val c = client ?: return _costs.value
        return runCatching { c.taskCosts() }.getOrNull()?.also { _costs.value = it } ?: _costs.value
    }

    /** Undo / go back: stops a working agent first, then restores the checkpoint. */
    suspend fun rollback(botId: String, checkpointId: String) {
        val c = client ?: throw HostException.unreachable()
        val bot = _bots.value[botId] ?: return
        val task = bot.task ?: return
        if (bot.status == "working" || bot.status == "needsInput") {
            runCatching { c.stop(botId) }
            // The host refuses a rollback while the turn is still winding down.
            var waited = 0
            while (_bots.value[botId]?.status == "working" && waited++ < 20) delay(250)
        }
        var last: Exception? = null
        repeat(5) {
            try {
                c.rollbackTask(task.id, checkpointId)
                return
            } catch (e: HostException) {
                last = e
                delay(400)
            }
        }
        last?.let { throw it }
    }

    suspend fun finishTask(botId: String, openPr: Boolean? = null, notify: Boolean? = null, learning: Boolean? = null) {
        val task = _bots.value[botId]?.task ?: return
        client?.finishTask(task.id, openPr, notify, learning)
    }

    suspend fun saveCheckpoint(botId: String) {
        val task = _bots.value[botId]?.task ?: return
        client?.taskCheckpoint(task.id, "Saved by you")
    }

    suspend fun saveTemplate(template: TaskTemplate) {
        client?.saveTemplate(template)
        loadSetup()
    }

    suspend fun deleteTemplate(id: String) {
        client?.deleteTemplate(id)
        loadSetup()
    }

    suspend fun setSafety(settings: SafetySettings) {
        val saved = client?.setSafety(settings) ?: return
        _setup.update { it?.copy(safety = saved) ?: TaskSetup(safety = saved) }
    }

    // MARK: team admin

    private val _you = MutableStateFlow<Actor?>(null)
    /** This phone's role on the computer (null until the host says). */
    val you: StateFlow<Actor?> = _you.asStateFlow()

    private val _team = MutableStateFlow<TeamInfo?>(null)
    val team: StateFlow<TeamInfo?> = _team.asStateFlow()
    private val _policies = MutableStateFlow<PolicyInfo?>(null)
    val policies: StateFlow<PolicyInfo?> = _policies.asStateFlow()
    private val _activity = MutableStateFlow<Activity?>(null)
    val activity: StateFlow<Activity?> = _activity.asStateFlow()
    private val _audit = MutableStateFlow<AuditLog?>(null)
    val audit: StateFlow<AuditLog?> = _audit.asStateFlow()

    /** Loads what the Team admin screen shows; admin-only parts are skipped for others. */
    suspend fun loadAdmin() {
        val c = client ?: return
        runCatching { c.team() }.getOrNull()?.let {
            _team.value = it
            _you.value = it.you
        }
        runCatching { c.policies() }.getOrNull()?.let { _policies.value = it }
        if (_you.value?.isAdmin != false) {
            runCatching { c.activity() }.getOrNull()?.let { _activity.value = it }
            runCatching { c.auditLog() }.getOrNull()?.let { _audit.value = it }
        }
    }

    suspend fun setPolicies(patch: JsonObject) {
        val saved = client?.setPolicies(patch) ?: throw HostException.unreachable()
        _policies.value = saved
        _setup.update { it?.copy(safety = saved.safety) }
        runCatching { client?.auditLog() }.getOrNull()?.let { _audit.value = it }
    }

    suspend fun setRole(key: String, role: String?) {
        _team.value = client?.setRole(key, role) ?: throw HostException.unreachable()
    }

    suspend fun setDefaultRole(role: String) {
        _team.value = client?.setTeam(role) ?: throw HostException.unreachable()
    }

    val route: Route? get() = (link.value as? LinkState.Ready)?.route

    private companion object {
        const val COMPUTER = "computer"
        const val THEME = "theme"
        const val DYNAMIC = "dynamicColor"
        const val NOTIFY = "notifications"
    }
}
