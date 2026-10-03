package com.ragul84.hypurr.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// The relay protocol's cryptography (docs/reference/remote-relay.md §5, §6), a port of
// kit/Sources/HypurrKit/Client/RelayCrypto.swift and host/src/remote/crypto.rs, checked field by field
// against docs/reference/fixtures/remote-relay-vectors.json. Labels are ASCII without a trailing NUL.

class RelayCryptoException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { BadKey, ZeroSharedSecret, BadSignature, DecryptFailed, BadCounter, TooLarge, Malformed }
}

object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Strict base64url without padding (RFC 4648 §5), or null. */
    fun decode(s: String): ByteArray? {
        if (s.length % 4 == 1 || s.any { it !in ALPHABET }) return null
        return runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
    }

    fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

fun ByteArray.b64url(): String = B64.encode(this)

object RelayCrypto {
    /** Largest plaintext chunk per frame (§6.3). */
    const val CHUNK_SIZE = 256 * 1024

    /** Reassembly limit for host → device messages. */
    const val MAX_INBOUND = 16 * 1024 * 1024

    private val random = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun label(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    fun u64be(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    // MARK: identifiers

    fun computerIdRaw(signKey: ByteArray): ByteArray = sha256(signKey).copyOf(16)

    fun computerId(signKey: ByteArray): String = computerIdRaw(signKey).b64url()

    /** The 16 raw bytes behind a computer ID, or null when it isn't one. */
    fun computerIdRaw(id: String): ByteArray? =
        if (id.length == 22) B64.decode(id)?.takeIf { it.size == 16 } else null

    /** `offerId` of a pairing code (§4.1). */
    fun offerId(code: ByteArray): String = sha256(label("hypurr/offer/v1"), code).copyOf(16).b64url()

    // MARK: Ed25519

    fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray =
        Ed25519Signer().run {
            init(true, Ed25519PrivateKeyParameters(seed, 0))
            update(message, 0, message.size)
            generateSignature()
        }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (publicKey.size != 32 || sig.size != 64) return false
        return runCatching {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(message, 0, message.size)
                verifySignature(sig)
            }
        }.getOrDefault(false)
    }

    // MARK: X25519 + HKDF

    fun x25519Public(priv: ByteArray): ByteArray = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    fun sharedSecret(priv: ByteArray, pub: ByteArray): ByteArray {
        if (pub.size != 32 || priv.size != 32) throw RelayCryptoException(RelayCryptoException.Reason.BadKey)
        val out = ByteArray(32)
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(priv, 0))
        runCatching { agreement.calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0) }
        if (out.all { it == 0.toByte() }) throw RelayCryptoException(RelayCryptoException.Reason.ZeroSharedSecret)
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    /** HKDF-Expand to 32 bytes: a single block. */
    fun expand(prk: ByteArray, info: String): ByteArray = hmac(prk, label(info) + byteArrayOf(1))

    // MARK: ChaCha20-Poly1305

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < 16) throw RelayCryptoException(RelayCryptoException.Reason.Malformed)
        return try {
            val c = ChaCha20Poly1305()
            c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(c.getOutputSize(sealed.size))
            val n = c.processBytes(sealed, 0, sealed.size, out, 0)
            c.doFinal(out, n)
            out
        } catch (e: Exception) {
            throw RelayCryptoException(RelayCryptoException.Reason.DecryptFailed)
        }
    }

    // MARK: handshake (§6.2)

    fun hs1Input(cid: ByteArray, dk: ByteArray, ekD: ByteArray, n: ByteArray): ByteArray =
        label("hypurr/hs1/v1") + cid + dk + ekD + n

    fun transcriptHash(cid: ByteArray, dk: ByteArray, ekD: ByteArray, n: ByteArray, ekH: ByteArray): ByteArray =
        sha256(label("hypurr/hs2/v1"), cid, dk, ekD, n, ekH)

    class ChannelKeys(val prk: ByteArray, val d2h: ByteArray, val h2d: ByteArray)

    fun channelKeys(sharedSecret: ByteArray, transcriptHash: ByteArray): ChannelKeys {
        val prk = extract(transcriptHash, sharedSecret)
        return ChannelKeys(prk, expand(prk, "hypurr/d2h/v1"), expand(prk, "hypurr/h2d/v1"))
    }

    /** The device's half of one handshake: a fresh ephemeral key and nonce per connection. */
    class Handshake(
        hostSignKey: String,
        computerId: String,
        val dk: ByteArray,
        private val ephemeral: ByteArray = random(32),
        val n: ByteArray = random(32),
    ) {
        private val hostKey: ByteArray = B64.decode(hostSignKey)?.takeIf { it.size == 32 && computerId(it) == computerId }
            ?: throw RelayCryptoException(RelayCryptoException.Reason.BadKey)
        val cid: ByteArray = computerIdRaw(hostKey)
        val ekD: ByteArray = x25519Public(ephemeral)
        val signInput: ByteArray get() = hs1Input(cid, dk, ekD, n)

        /** Checks `welcome.sig` against the pinned host key, then derives both directions' keys. */
        fun finish(ekH: ByteArray, sig: ByteArray): ChannelKeys {
            val th = transcriptHash(cid, dk, ekD, n, ekH)
            if (!ed25519Verify(hostKey, th, sig)) throw RelayCryptoException(RelayCryptoException.Reason.BadSignature)
            return channelKeys(sharedSecret(ephemeral, ekH), th)
        }
    }

    // MARK: frames (§6.3)

    fun frameNonce(c: Long): ByteArray = ByteArray(4) + u64be(c)

    fun frameAAD(c: Long): ByteArray = label("hypurr/frame/v1") + u64be(c)

    fun sealFrame(key: ByteArray, c: Long, final: Boolean, chunk: ByteArray): ByteArray =
        seal(key, frameNonce(c), byteArrayOf(if (final) 0 else 1) + chunk, frameAAD(c))

    fun openFrame(key: ByteArray, c: Long, d: ByteArray): Pair<Boolean, ByteArray> {
        if (d.size < 17) throw RelayCryptoException(RelayCryptoException.Reason.Malformed)
        val plain = open(key, frameNonce(c), d, frameAAD(c))
        val flag = plain.firstOrNull()?.toInt()
        if (flag == null || flag !in 0..1) throw RelayCryptoException(RelayCryptoException.Reason.DecryptFailed)
        return (flag == 0) to plain.copyOfRange(1, plain.size)
    }

    /** One direction's sender: every inner message becomes ≤ 256 KiB chunks with consecutive counters. */
    class FrameSealer(private val key: ByteArray) {
        var counter: Long = 0
            private set

        /** Past 2^32 frames the channel must be replaced (`4011 rekey`). */
        val exhausted: Boolean get() = counter >= (1L shl 32)

        fun seal(message: ByteArray): List<Pair<Long, ByteArray>> {
            val out = mutableListOf<Pair<Long, ByteArray>>()
            var offset = 0
            do {
                val end = minOf(offset + CHUNK_SIZE, message.size)
                val chunk = message.copyOfRange(offset, end)
                offset = end
                out += counter to sealFrame(key, counter, offset >= message.size, chunk)
                counter++
            } while (offset < message.size)
            return out
        }
    }

    /** One direction's receiver: demands the exact next counter and reassembles chunks. */
    class FrameOpener(private val key: ByteArray, private val limit: Int = MAX_INBOUND) {
        var counter: Long = 0
            private set
        private var buffer = java.io.ByteArrayOutputStream()

        /** A whole message once its last chunk arrived, else null. */
        fun open(c: Long, d: ByteArray): ByteArray? {
            if (c != counter) throw RelayCryptoException(RelayCryptoException.Reason.BadCounter)
            val (final, chunk) = openFrame(key, c, d)
            counter++
            if (buffer.size() + chunk.size > limit) throw RelayCryptoException(RelayCryptoException.Reason.TooLarge)
            buffer.write(chunk)
            if (!final) return null
            return buffer.toByteArray().also { buffer = java.io.ByteArrayOutputStream() }
        }
    }

    // MARK: mailbox (§6.4)

    class Mailbox(val epk: ByteArray, val ciphertext: ByteArray, val sig: ByteArray) {
        val blob: ByteArray get() = epk + sig + ciphertext
    }

    fun mailboxKey(sharedSecret: ByteArray, cid: ByteArray, dk: ByteArray, epk: ByteArray): ByteArray =
        expand(extract(label("hypurr/mbox/v1") + cid + dk + epk, sharedSecret), "hypurr/mbox-key/v1")

    /** Seals one inner message for an offline host. A fresh ephemeral key every time (MUST). */
    fun sealMailbox(
        plaintext: ByteArray,
        hostBoxKey: ByteArray,
        computerId: String,
        deviceKey: ByteArray,
        clientNonce: String,
        sign: (ByteArray) -> ByteArray,
        ephemeral: ByteArray = random(32),
    ): Mailbox {
        val cid = computerIdRaw(computerId) ?: throw RelayCryptoException(RelayCryptoException.Reason.BadKey)
        val epk = x25519Public(ephemeral)
        val key = mailboxKey(sharedSecret(ephemeral, hostBoxKey), cid, deviceKey, epk)
        val ct = seal(key, ByteArray(12), plaintext, deviceKey + clientNonce.toByteArray())
        return Mailbox(epk, ct, sign(label("hypurr/mbox/v1") + cid + epk + ct))
    }

    // MARK: push (§6.7)

    fun pushKey(sharedSecret: ByteArray, cid: ByteArray, pushKey: ByteArray, epk: ByteArray): ByteArray =
        expand(extract(label("hypurr/push/v1") + cid + pushKey + epk, sharedSecret), "hypurr/push-key/v1")

    /** Opens `sealed = b64url(epk ‖ ct)` with the device's push key. */
    fun openPush(sealed: String, computerId: String, pushPrivate: ByteArray): ByteArray {
        val raw = B64.decode(sealed)?.takeIf { it.size > 32 + 16 }
            ?: throw RelayCryptoException(RelayCryptoException.Reason.Malformed)
        val cid = computerIdRaw(computerId) ?: throw RelayCryptoException(RelayCryptoException.Reason.Malformed)
        val epk = raw.copyOf(32)
        val ct = raw.copyOfRange(32, raw.size)
        val key = pushKey(sharedSecret(pushPrivate, epk), cid, x25519Public(pushPrivate), epk)
        return open(key, ByteArray(12), ct, cid)
    }

    // MARK: SAS (§4.2 B)

    fun sasCommit(deviceKey: ByteArray, deviceNonce: ByteArray): ByteArray =
        sha256(label("hypurr/sascommit/v1"), deviceKey, deviceNonce)

    fun sasCode(hostSignKey: ByteArray, deviceKey: ByteArray, deviceNonce: ByteArray, hostNonce: ByteArray): String {
        val h = sha256(label("hypurr/sas/v2"), hostSignKey, deviceKey, deviceNonce, hostNonce)
        val v = h.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        return (v % 1_000_000).toString().padStart(6, '0')
    }

    // MARK: Hypurr-Sig (§5)

    fun requestSigInput(method: String, authority: String, pathAndQuery: String, ts: Long, nonce: String, body: ByteArray): ByteArray =
        listOf("hypurr-sig-v1", method.uppercase(), authority.lowercase(), pathAndQuery, ts.toString(), nonce, sha256(body).b64url())
            .joinToString("\n").toByteArray()

    /** The `Host` header value of a URL: lowercased, with a non-default port. */
    fun authority(url: java.net.URI): String {
        val host = (url.host ?: "").lowercase()
        val default = mapOf("https" to 443, "wss" to 443, "http" to 80, "ws" to 80)[url.scheme?.lowercase()]
        return if (url.port == -1 || url.port == default) host else "$host:${url.port}"
    }

    /** Path and query exactly as sent on the request line. */
    fun pathAndQuery(url: java.net.URI): String {
        val path = url.rawPath.orEmpty().ifEmpty { "/" }
        return path + (url.rawQuery?.let { "?$it" } ?: "")
    }
}
