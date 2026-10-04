package com.ragul84.hypurr.net

import com.ragul84.hypurr.data.DeviceIdentity
import com.ragul84.hypurr.model.Bot
import com.ragul84.hypurr.model.Computer
import com.ragul84.hypurr.model.Entry
import com.ragul84.hypurr.model.Hello
import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.model.Pairing
import com.ragul84.hypurr.model.SafetySettings
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
                          attachments: List<String> = emptyList(), command: String? = null): Bot {
        val res = transport.call("startTask", buildJsonObject {
            put("goal", goal)
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

    suspend fun finishTask(taskId: String) {
        transport.call("finishTask", buildJsonObject { put("taskId", taskId) }, timeoutMs = 60_000)
    }

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
