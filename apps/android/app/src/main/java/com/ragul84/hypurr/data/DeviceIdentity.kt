package com.ragul84.hypurr.data

import com.ragul84.hypurr.crypto.RelayCrypto
import com.ragul84.hypurr.crypto.b64url

/**
 * This device's keys (kit DeviceIdentity.swift): an Ed25519 signing key that hosts authorize, and an
 * X25519 push key the host seals notification text to (§6.7). Both are created once and kept wrapped
 * by the Android Keystore ([KeystoreSecrets]).
 */
class DeviceIdentity(private val signSeed: ByteArray, val pushPrivate: ByteArray) {
    val deviceKey: ByteArray = RelayCrypto.ed25519Public(signSeed)
    val publicKey: String = deviceKey.b64url()
    val pushKey: String = RelayCrypto.x25519Public(pushPrivate).b64url()

    fun sign(data: ByteArray): ByteArray = RelayCrypto.ed25519Sign(signSeed, data)

    /** The `Hypurr-Sig` header value (§5). */
    fun signatureHeader(
        method: String,
        authority: String,
        pathAndQuery: String,
        body: ByteArray,
        ts: Long = System.currentTimeMillis(),
        nonce: String = RelayCrypto.random(16).b64url(),
    ): String {
        val input = RelayCrypto.requestSigInput(method, authority, pathAndQuery, ts, nonce, body)
        return "v=1,kid=$publicKey,ts=$ts,nonce=$nonce,sig=${sign(input).b64url()}"
    }

    companion object {
        private const val SIGN = "device-key"
        private const val PUSH = "push-key"

        fun load(secrets: SecretStore): DeviceIdentity {
            val sign = secrets.get(SIGN) ?: RelayCrypto.random(32).also { secrets.put(SIGN, it) }
            val push = secrets.get(PUSH) ?: RelayCrypto.random(32).also { secrets.put(PUSH, it) }
            return DeviceIdentity(sign, push)
        }

        /** Forgetting every computer: no host can tie anything to this device anymore. */
        fun delete(secrets: SecretStore) {
            secrets.remove(SIGN)
            secrets.remove(PUSH)
        }
    }
}

interface SecretStore {
    fun get(name: String): ByteArray?
    fun put(name: String, value: ByteArray)
    fun remove(name: String)
}

class MemorySecrets : SecretStore {
    private val map = mutableMapOf<String, ByteArray>()
    override fun get(name: String) = map[name]
    override fun put(name: String, value: ByteArray) { map[name] = value }
    override fun remove(name: String) { map.remove(name) }
}
