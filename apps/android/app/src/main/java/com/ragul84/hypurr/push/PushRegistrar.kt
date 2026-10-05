package com.ragul84.hypurr.push

import com.ragul84.hypurr.model.HypurrJson
import com.ragul84.hypurr.net.HostClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Push setup (CLAUDE.md "Push", Android flavour): the FCM registration token goes to `relay/`
 * (`POST /register {token, env: "fcm", kind: "alert"}`), which returns an opaque AES-GCM ticket.
 * The phone hands the ticket and its X25519 push key to the host (`registerDevice`); the host seals
 * title and body to that key, so the relay and FCM only ever see generic text.
 */
class PushRegistrar(
    private val relayUrl: String,
    private val tokenSource: suspend () -> String?,
    private val http: OkHttpClient = OkHttpClient(),
) {
    /** The relay ticket for an FCM token. */
    suspend fun ticket(token: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("token", token)
            put("env", "fcm")
            put("kind", "alert")
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${relayUrl.trimEnd('/')}/register").post(body).build()
        http.newCall(request).execute().use { res ->
            check(res.isSuccessful) { "relay refused registration (${res.code})" }
            HypurrJson.parseToJsonElement(res.body!!.string()).jsonObject.getValue("ticket").jsonPrimitive.content
        }
    }

    /** Registers this phone for alerts with the connected host; false when push isn't configured. */
    suspend fun register(client: HostClient, deviceName: String, pushKey: String, ctx: String = CONTEXT): Boolean {
        val token = tokenSource() ?: return false
        client.registerDevice(ticket(token), relayUrl, deviceName, pushKey, ctx)
        return true
    }

    companion object {
        /** The pairing context (iOS uses one per account; this app pairs locally only). */
        const val CONTEXT = "local"
    }
}
