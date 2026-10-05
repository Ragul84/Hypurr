package com.ragul84.hypurr.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** A WebSocket as the channel sees it: text messages and close codes (kit HostConnector.swift). */
interface ChannelSocket {
    /** Queues one text message; never suspends, so frames leave in the order they were sealed. */
    fun send(text: String)

    /** Throws [SocketClosed] or [SocketRefused] when the socket ends. */
    suspend fun receive(): String
    fun close(code: Int)
}

class SocketClosed(val code: Int) : Exception("socket closed ($code)")

/** The upgrade was answered with this HTTP status instead of 101. */
class SocketRefused(val status: Int) : Exception("upgrade refused ($status)")

sealed interface Endpoint {
    data class Direct(val url: String) : Endpoint
    data class Relay(val url: String, val signature: String) : Endpoint
}

typealias Dialer = suspend (Endpoint) -> ChannelSocket

class OkHttpSocket private constructor() : WebSocketListener(), ChannelSocket {
    private val inbox = Channel<Any>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private lateinit var ws: WebSocket

    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened.complete(Unit)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        inbox.trySend(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        end(SocketClosed(code))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(SocketClosed(code))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val error = if (response != null && response.code != 101) SocketRefused(response.code) else SocketClosed(1006)
        opened.completeExceptionally(error)
        end(error)
    }

    private fun end(error: Exception) {
        inbox.trySend(error)
        inbox.close()
    }

    override fun send(text: String) {
        if (!ws.send(text)) throw SocketClosed(1006)
    }

    override suspend fun receive(): String {
        val next = inbox.receiveCatching().getOrNull() ?: throw SocketClosed(1006)
        if (next is Exception) throw next
        return next as String
    }

    override fun close(code: Int) {
        ws.close(code, null)
        end(SocketClosed(code))
    }

    companion object {
        /** Direct: OkHttp answers the host's 15 s pings and pings back, failing a dead socket. */
        val client: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val dial: Dialer = { endpoint -> open(endpoint) }

        suspend fun open(endpoint: Endpoint, http: OkHttpClient = client): ChannelSocket {
            val request = when (endpoint) {
                is Endpoint.Direct -> Request.Builder().url(endpoint.url).build()
                is Endpoint.Relay -> Request.Builder().url(endpoint.url).header("Hypurr-Sig", endpoint.signature).build()
            }
            val socket = OkHttpSocket()
            socket.ws = http.newWebSocket(request, socket)
            try {
                withTimeout(15_000) { socket.opened.await() }
            } catch (e: Exception) {
                socket.ws.cancel()
                throw e
            }
            return socket
        }
    }
}
