package com.ragul84.hypurr.push

import com.ragul84.hypurr.crypto.B64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PushTest {
    private val v = Json.parseToJsonElement(File(System.getProperty("hypurr.vectors")!!).readText()).jsonObject
    private val push = v.getValue("push").jsonObject
    private val computerId = v.getValue("keys").jsonObject.getValue("computerId").jsonPrimitive.content
    private val pushPriv = B64.decode(push.getValue("pushPriv").jsonPrimitive.content)!!

    @Test
    fun sealedAlertReplacesTheGenericLine() {
        val data = mapOf(
            "title" to "Hypurr", "body" to "A bot needs your response. Open Hypurr to review.",
            "sealed" to push.getValue("sealed").jsonPrimitive.content, "botId" to "b1", "computerId" to computerId,
            "category" to "needsInput",
        )
        val alert = PushAlert.from(data, pushPriv)
        assertEquals("Reviewer", alert.title)
        assertEquals("Run cargo test --all?", alert.body)
        assertEquals("$computerId:b1", alert.threadId)
        assertEquals("needsInput", alert.category)
    }

    @Test
    fun withoutTheRightKeyTheGenericLineStays() {
        val data = mapOf("title" to "Hypurr", "body" to "generic", "sealed" to push.getValue("sealed").jsonPrimitive.content,
            "botId" to "b1", "computerId" to computerId)
        val alert = PushAlert.from(data, ByteArray(32) { 9 })
        assertEquals("Hypurr", alert.title)
        assertEquals("generic", alert.body)
    }

    @Test
    fun registersTheFcmTokenWithTheRelay() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ticket":"tkt_123"}"""))
        server.start()
        val registrar = PushRegistrar(server.url("/").toString(), tokenSource = { "fcm-token" })
        assertEquals("tkt_123", registrar.ticket("fcm-token"))
        val request = server.takeRequest()
        assertEquals("/register", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("fcm-token", body.getValue("token").jsonPrimitive.content)
        assertEquals("fcm", body.getValue("env").jsonPrimitive.content)
        assertEquals("alert", body.getValue("kind").jsonPrimitive.content)
        server.shutdown()
    }
}
