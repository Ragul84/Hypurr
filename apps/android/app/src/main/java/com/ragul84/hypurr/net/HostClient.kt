package com.ragul84.hypurr.net

import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.model.TeamInfo
import com.ragul84.hypurr.model.PolicyInfo
import com.ragul84.hypurr.model.AuditLog
import com.ragul84.hypurr.model.Activity
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.model.Attachment
import com.ragul84.hypurr.model.Integrations
import com.ragul84.hypurr.model.Issue
import com.ragul84.hypurr.model.IssueList
import com.ragul84.hypurr.model.SafetySettings
import com.ragul84.hypurr.model.TaskCosts
import com.ragul84.hypurr.model.SyncResponse
import com.ragul84.hypurr.model.TaskInfo
import com.ragul84.hypurr.model.TaskRoute
import com.ragul84.hypurr.model.TaskSetup
import com.ragul84.hypurr.model.TaskTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One host event (`sub: events`), as kit HostClient.parseEvent maps them. */
sealed interface HostEvent {
    data class Hello(val hostId: String, val rev: Long) : HostEvent
    data class BotChanged(val bot: Bot) : HostEvent
    data class BotDeleted(val id: String, val rev: Long) : HostEvent
    data class EntryChanged(val entry: Entry) : HostEvent
    data object Resync : HostEvent
    /** A known type that didn't decode: the store rewinds to rev 0 rather than skip it. */
    data class Undecodable(val type: String) : HostEvent
    data class Other(val type: String, val rev: Long?) : HostEvent

    companion object {
        fun parse(ev: JsonElement): HostEvent? {
            val o = ev as? JsonObject ?: return null
            val type = o.str("type") ?: return null
            val rev = o["rev"]?.jsonPrimitive?.longOrNull
            return when (type) {
                "hello" -> Hello(o.str("hostId") ?: "", rev ?: 0)
                "bot" -> runCatching {
                    val bot = HypurrJson.decodeFromJsonElement<Bot>(o.getValue("bot"))
                    if (bot.deleted == true) BotDeleted(bot.id, bot.rev) else BotChanged(bot)
                }.getOrElse { Undecodable(type) }
                "entry" -> runCatching { EntryChanged(HypurrJson.decodeFromJsonElement<Entry>(o.getValue("entry"))) }
                    .getOrElse { Undecodable(type) }
                "resync" -> Resync
                else -> Other(type, rev)
            }
        }
    }
}

/** Typed host methods over a [ChannelTransport] (kit HostClient.swift). */
class HostClient(val transport: ChannelTransport) {
    private suspend inline fun <reified T> call(method: String, body: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): T =
        HypurrJson.decodeFromJsonElement(transport.call(method, body, timeoutMs))

    suspend fun hello(): Hello = call("hello")

    suspend fun sync(since: Long): SyncResponse = call("sync", buildJsonObject { put("since", since) })

    suspend fun history(botId: String, beforeSeq: Long = Long.MAX_VALUE, limit: Int = 100): List<Entry> {
        val res = transport.call("history", buildJsonObject {
            put("botId", botId)
            put("beforeSeq", beforeSeq)
            put("limit", limit)
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("entries"))
    }

    suspend fun send(botId: String, text: String, clientNonce: String): Entry {
        val res = transport.call("send", buildJsonObject {
            put("botId", botId)
            put("text", text)
            put("clientNonce", clientNonce)
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("entry"))
    }

    suspend fun stop(botId: String) {
        transport.call("stop", buildJsonObject { put("botId", botId) })
    }

    suspend fun newSession(botId: String) {
        transport.call("newSession", buildJsonObject { put("botId", botId) })
    }

    suspend fun markRead(botId: String) {
        transport.call("markRead", buildJsonObject {
            put("botId", botId)
            put("all", false)
        })
    }

    suspend fun respondPermission(entryId: String, optionId: String?) {
        transport.call("respondPermission", buildJsonObject {
            put("entryId", entryId)
            optionId?.let { put("optionId", it) }
        })
    }

    /** Push tickets are bound to this device's key; `pushKey` lets the host seal notification text (§6.7). */
    suspend fun registerDevice(ticket: String, relay: String, name: String, pushKey: String, ctx: String) {
        transport.call("registerDevice", buildJsonObject {
            put("ticket", ticket)
            put("relay", relay)
            put("name", name)
            put("pushKey", pushKey)
            put("ctx", ctx)
        })
    }

    suspend fun unregisterDevice() {
        transport.call("unregisterDevice")
    }

    // Beginner tasks and the safety net (host `tasks`).

    suspend fun taskSetup(): TaskSetup = call("taskSetup")

    suspend fun routeTask(goal: String, template: String?): TaskRoute {
        val res = transport.call("routeTask", buildJsonObject {
            put("goal", goal)
            template?.let { put("template", it) }
        })
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("route"))
    }

    /** Starts a task; returns its bot (with `task`). */
    suspend fun startTask(goal: String, template: String?, input: String, project: String?, backend: String?,
                          attachments: List<String> = emptyList(), command: String? = null,
                          draftId: String? = null, issue: Issue? = null): Bot {
        val res = transport.call("startTask", buildJsonObject {
            put("goal", goal)
            draftId?.let { put("draftId", it) }
            issue?.let { put("issue", HypurrJson.encodeToJsonElement(Issue.serializer(), it)) }
            template?.let { put("template", it) }
            if (input.isNotBlank()) put("input", input)
            project?.let { put("project", it) }
            backend?.let { put("backend", it) }
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map(::JsonPrimitive)))
            command?.let { put("command", it) }
        }, timeoutMs = 60_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("bot"))
    }

    suspend fun rollbackTask(taskId: String, checkpointId: String): TaskInfo {
        val res = transport.call("rollbackTask", buildJsonObject {
            put("taskId", taskId)
            put("checkpointId", checkpointId)
        }, timeoutMs = 60_000)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("task"))
    }

    suspend fun taskCheckpoint(taskId: String, label: String) {
        transport.call("taskCheckpoint", buildJsonObject {
            put("taskId", taskId)
            put("label", label)
        }, timeoutMs = 60_000)
    }

    /** Unset options follow the host's Work tools settings. */
    suspend fun finishTask(taskId: String, openPr: Boolean? = null, notify: Boolean? = null, learning: Boolean? = null) {
        transport.call("finishTask", buildJsonObject {
            put("taskId", taskId)
            openPr?.let { put("openPr", it) }
            notify?.let { put("notify", it) }
            learning?.let { put("learning", it) }
        }, timeoutMs = 60_000)
    }

    /**
     * One file for a task that hasn't started yet (`draftId`) or a bot's chat (`botId`), in
     * 384 KiB chunks so each call stays under the channel's 1 MiB limit (docs/features/file-attachments.md).
     */
    suspend fun upload(uploadId: String, name: String, bytes: ByteArray, draftId: String? = null, botId: String? = null): Attachment {
        val chunk = 384 * 1024
        var offset = 0
        var result: Attachment? = null
        do {
            val end = minOf(bytes.size, offset + chunk)
            val done = end == bytes.size
            val res = transport.call("upload", buildJsonObject {
                draftId?.let { put("draftId", it) }
                botId?.let { put("botId", it) }
                put("uploadId", uploadId)
                put("name", name)
                put("offset", offset.toLong())
                put("data", java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)))
                put("done", done)
            }, timeoutMs = 60_000)
            if (done) result = HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("attachment"))
            offset = end
        } while (!done)
        return result!!
    }

    // Work tools (host `integrations`).

    suspend fun integrations(): Integrations {
        val res = transport.call("integrations")
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("integrations"))
    }

    /** Partial update: only the given fields change; an empty string clears a credential. */
    suspend fun setIntegrations(patch: JsonObject): Integrations {
        val res = transport.call("setIntegrations", patch)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("integrations"))
    }

    /** Checks a connection or posts a test message; returns what happened in plain words. */
    suspend fun testIntegration(kind: String): String {
        val res = transport.call("testIntegration", buildJsonObject { put("kind", kind) }, timeoutMs = 40_000)
        return res.jsonObject.str("detail") ?: "OK"
    }

    suspend fun issues(project: String? = null): IssueList =
        call("issues", buildJsonObject { project?.let { put("project", it) } }, timeoutMs = 45_000)

    suspend fun taskCosts(): TaskCosts = call("taskCosts")

    suspend fun saveTemplate(template: TaskTemplate): TaskTemplate {
        val res = transport.call("saveTemplate", HypurrJson.encodeToJsonElement(TaskTemplate.serializer(), template).jsonObject)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("template"))
    }

    suspend fun deleteTemplate(id: String) {
        transport.call("deleteTemplate", buildJsonObject { put("id", id) })
    }

    suspend fun setSafety(settings: SafetySettings): SafetySettings {
        val res = transport.call("setSafetySettings", HypurrJson.encodeToJsonElement(SafetySettings.serializer(), settings).jsonObject)
        return HypurrJson.decodeFromJsonElement(res.jsonObject.getValue("safety"))
    }

    // Team admin (host `admin`).

    suspend fun team(): TeamInfo = call("team")

    /** `role: null` goes back to the default role. */
    suspend fun setRole(key: String, role: String?): TeamInfo = call("setRole", buildJsonObject {
        put("key", key)
        if (role == null) put("role", kotlinx.serialization.json.JsonNull) else put("role", role)
    })

    suspend fun setTeam(defaultRole: String): TeamInfo = call("setTeam", buildJsonObject { put("defaultRole", defaultRole) })

    suspend fun policies(): PolicyInfo = call("policies")

    /** Partial update of the team rules and the safety net settings. */
    suspend fun setPolicies(patch: JsonObject): PolicyInfo = call("setPolicies", patch)

    suspend fun auditLog(before: Long? = null, limit: Int = 100): AuditLog = call("auditLog", buildJsonObject {
        before?.let { put("before", it) }
        put("limit", limit)
    })

    suspend fun activity(days: Int = 7): Activity = call("activity", buildJsonObject { put("days", days) })

    fun events(since: Long): Flow<HostEvent> = transport.events(since).mapNotNull(HostEvent::parse)
}

/** Opens channels to computers (§7.5) and pairs new ones from a QR (§4.1). */
object HostConnector {
    fun connect(computer: Computer, identity: DeviceIdentity, scope: CoroutineScope, dial: Dialer = OkHttpSocket.dial): ChannelTransport =
        ChannelTransport(computer, identity, scope, dial = dial).also { it.start() }

    /**
     * Proves the QR's one-time code inside a channel to the QR's host key; the host then authorizes
     * this device and closes with `4100 paired`.
     */
    suspend fun pair(
        pairing: Pairing,
        identity: DeviceIdentity,
        deviceName: String,
        scope: CoroutineScope,
        dial: Dialer = OkHttpSocket.dial,
    ): Computer {
        val transport = ChannelTransport(pairing.computer, identity, scope, pairingCode = pairing.code, dial = dial)
        transport.start()
        try {
            val res = transport.call("pair", buildJsonObject {
                put("code", pairing.code)
                put("name", deviceName)
                put("platform", "android")
            }, timeoutMs = 30_000).jsonObject
            if (res.str("computerId") != pairing.computer.id) {
                throw HostException(HostException.Kind.Unauthorized, ChannelTransport.IDENTITY_CHANGED)
            }
            val name = res.str("name")?.takeIf { it.isNotEmpty() } ?: pairing.computer.name
            return pairing.computer.copy(name = name)
        } catch (e: HostException) {
            throw if (transport.needsUpgrade) HostException.upgrade() else e
        } finally {
            transport.shutdown()
        }
    }
}
