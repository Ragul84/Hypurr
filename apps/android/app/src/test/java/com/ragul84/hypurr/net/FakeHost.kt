package com.ragul84.hypurr.net

import com.ragul84.hypurr.crypto.B64
import com.ragul84.hypurr.crypto.RelayCrypto
import com.ragul84.hypurr.crypto.b64url
import com.ragul84.hypurr.model.Computer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/** One end of an in-memory WebSocket. */
class FakeSocket(private val outbox: Channel<String>, private val inbox: Channel<Any>) : ChannelSocket {
    @Volatile var closedWith: Int? = null
    override fun send(text: String) {
        if (closedWith != null) throw SocketClosed(closedWith!!)
        outbox.trySend(text)
    }

    override suspend fun receive(): String {
        val next = inbox.receiveCatching().getOrNull() ?: throw SocketClosed(closedWith ?: 1006)
        if (next is Exception) throw next
        return next as String
    }

    override fun close(code: Int) {
        if (closedWith == null) closedWith = code
        inbox.trySend(SocketClosed(code))
        inbox.close()
    }
}

/**
 * A host that speaks the channel protocol (§6) with the reference crypto, the device's peer in
 * transport tests. [handler] answers inner requests; returning null sends nothing.
 */
class FakeHost(val scope: CoroutineScope, seed: ByteArray = RelayCrypto.random(32)) {
    private val signSeed = seed
    val signPub: ByteArray = RelayCrypto.ed25519Public(seed)
    val computerId: String = RelayCrypto.computerId(signPub)
    val boxPriv = RelayCrypto.random(32)
    var name = "Studio"
    val pairingCode: String = RelayCrypto.random(16).b64url()
    val requests = CopyOnWriteArrayList<JsonObject>()
    val hellos = CopyOnWriteArrayList<JsonObject>()
    val relayEndpoints = CopyOnWriteArrayList<Endpoint.Relay>()
    /** Device keys allowed in; pairing adds one. */
    val authorized = mutableSetOf<String>()
    var rejectWith: String? = null
    var forgeWelcome = false
    /** Relay mode: presence first; online decides whether the host answers hellos. */
    var hostOnline = true
    var handler: (Session, JsonObject) -> JsonElement? = { _, _ -> buildJsonObject { } }

    fun computer(urls: List<String> = listOf("http://192.0.2.1:19222"), cloud: String? = null) = Computer(
        id = computerId, name = name, signKey = signPub.b64url(), boxKey = RelayCrypto.x25519Public(boxPriv).b64url(),
        urls = urls, cloud = cloud,
    )

    val dialer: Dialer = { endpoint ->
        val toHost = Channel<String>(Channel.UNLIMITED)
        val toDevice = Channel<Any>(Channel.UNLIMITED)
        val device = FakeSocket(toHost, toDevice)
        val session = Session(toDevice)
        if (endpoint is Endpoint.Relay) {
            relayEndpoints += endpoint
            session.relay = true
            session.push(buildJsonObject {
                put("t", "presence")
                put("online", hostOnline)
                put("lastSeenAt", 1_790_000_000_000)
            })
        }
        scope.launch { for (text in toHost) session.receive(Json.parseToJsonElement(text).jsonObject) }
        device
    }

    inner class Session(private val toDevice: Channel<Any>) {
        var relay = false
        private var sealer: RelayCrypto.FrameSealer? = null
        private var opener: RelayCrypto.FrameOpener? = null
        var pairing = false
        var dk: String = ""

        fun push(o: JsonObject) {
            toDevice.trySend(o.toString())
        }

        fun close(code: Int) {
            toDevice.trySend(SocketClosed(code))
            toDevice.close()
        }

        fun reply(o: JsonObject) {
            for ((c, d) in sealer!!.seal(o.toString().toByteArray())) push(buildJsonObject {
                put("t", "f")
                put("c", c)
                put("d", d.b64url())
            })
        }

        fun event(id: Long, ev: JsonObject) = reply(buildJsonObject {
            put("id", id)
            put("ev", ev)
        })

        fun receive(m: JsonObject) {
            when (m["t"]?.jsonPrimitive?.content) {
                "hello" -> hello(m)
                "ping" -> push(buildJsonObject { put("t", "pong") })
                "f" -> {
                    val bytes = opener!!.open(m.getValue("c").jsonPrimitive.long, B64.decode(m.getValue("d").jsonPrimitive.content)!!)
                        ?: return
                    inner(Json.parseToJsonElement(bytes.decodeToString()).jsonObject)
                }
            }
        }

        private fun hello(m: JsonObject) {
            hellos += m
            if (relay && !hostOnline) return
            dk = m.getValue("dk").jsonPrimitive.content
            val dkRaw = B64.decode(dk)!!
            val ekD = B64.decode(m.getValue("ek").jsonPrimitive.content)!!
            val n = B64.decode(m.getValue("n").jsonPrimitive.content)!!
            val cid = RelayCrypto.computerIdRaw(signPub)
            check(RelayCrypto.ed25519Verify(dkRaw, RelayCrypto.hs1Input(cid, dkRaw, ekD, n), B64.decode(m.getValue("sig").jsonPrimitive.content)!!))
            pairing = m["pair"]?.jsonPrimitive?.content == "true"
            rejectWith?.let {
                push(buildJsonObject {
                    put("t", "reject")
                    put("code", it)
                    put("message", "nope")
                })
                close(4001)
                return
            }
            if (!pairing && dk !in authorized) {
                push(buildJsonObject {
                    put("t", "reject")
                    put("code", "unauthorized")
                })
                close(4001)
                return
            }
            val ekPriv = RelayCrypto.random(32)
            val ekH = RelayCrypto.x25519Public(ekPriv)
            val th = RelayCrypto.transcriptHash(cid, dkRaw, ekD, n, ekH)
            val keys = RelayCrypto.channelKeys(RelayCrypto.sharedSecret(ekPriv, ekD), th)
            sealer = RelayCrypto.FrameSealer(keys.h2d)
            opener = RelayCrypto.FrameOpener(keys.d2h)
            val sig = RelayCrypto.ed25519Sign(if (forgeWelcome) RelayCrypto.random(32) else signSeed, th)
            push(buildJsonObject {
                put("t", "welcome")
                put("v", 1)
                put("ek", ekH.b64url())
                put("sig", sig.b64url())
            })
        }

        private fun inner(m: JsonObject) {
            requests += m
            val id = m.getValue("id").jsonPrimitive.long
            if (pairing) {
                if (m["m"]?.jsonPrimitive?.content == "pair" && m.getValue("b").jsonObject["code"]?.jsonPrimitive?.content == pairingCode) {
                    authorized += dk
                    reply(buildJsonObject {
                        put("id", id)
                        put("ok", buildJsonObject {
                            put("computerId", computerId)
                            put("name", name)
                        })
                    })
                    close(4100)
                } else {
                    reply(buildJsonObject {
                        put("id", id)
                        put("err", buildJsonObject {
                            put("status", 403)
                            put("message", "This pairing code expired.")
                        })
                    })
                    close(4001)
                }
                return
            }
            if (m["cancel"] != null) return
            val ok = handler(this, m) ?: return
            if (m["sub"] != null) return
            reply(buildJsonObject {
                put("id", id)
                put("ok", ok)
            })
        }
    }
}
