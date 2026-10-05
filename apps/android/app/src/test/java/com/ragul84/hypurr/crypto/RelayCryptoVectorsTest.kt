package com.ragul84.hypurr.crypto

import com.ragul84.hypurr.data.DeviceIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/reference/fixtures/remote-relay-vectors.json, the same file host/src/remote/crypto.rs and
 * kit RelayCryptoTests check: every label, key derivation, frame, push and signature must match byte for byte.
 */
class RelayCryptoVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(File(System.getProperty("hypurr.vectors")!!).readText()).jsonObject

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.b(k: String) = B64.decode(s(k)) ?: error("$k isn't base64url")
    private fun section(k: String) = v.getValue(k).jsonObject

    private val keys = section("keys")
    private val hostSignPub = keys.b("hostSignPub")
    private val deviceSeed = keys.b("deviceSignSeed")
    private val deviceKey = keys.b("deviceSignPub")
    private val cid = RelayCrypto.computerIdRaw(hostSignPub)

    @Test
    fun keysAndComputerId() {
        assertArrayEquals(hostSignPub, RelayCrypto.ed25519Public(keys.b("hostSignSeed")))
        assertArrayEquals(deviceKey, RelayCrypto.ed25519Public(deviceSeed))
        assertArrayEquals(keys.b("hostBoxPub"), RelayCrypto.x25519Public(keys.b("hostBoxPriv")))
        assertEquals(keys.s("computerId"), RelayCrypto.computerId(hostSignPub))
        assertArrayEquals(cid, RelayCrypto.computerIdRaw(keys.s("computerId")))
    }

    @Test
    fun handshake() {
        val h = section("handshake")
        val hs = RelayCrypto.Handshake(keys.s("hostSignPub"), keys.s("computerId"), deviceKey, h.b("deviceEphPriv"), h.b("n"))
        assertArrayEquals(h.b("deviceEphPub"), hs.ekD)
        assertArrayEquals(h.b("hs1SignInput"), hs.signInput)
        // Ed25519 is deterministic: the device's hello signature matches exactly.
        assertArrayEquals(h.b("hs1Sig"), RelayCrypto.ed25519Sign(deviceSeed, hs.signInput))
        val ekH = h.b("hostEphPub")
        assertArrayEquals(ekH, RelayCrypto.x25519Public(h.b("hostEphPriv")))
        val th = RelayCrypto.transcriptHash(cid, deviceKey, hs.ekD, h.b("n"), ekH)
        assertArrayEquals(h.b("transcriptHash"), th)
        assertArrayEquals(h.b("hs2Sig"), RelayCrypto.ed25519Sign(keys.b("hostSignSeed"), th))
        assertArrayEquals(h.b("sharedSecret"), RelayCrypto.sharedSecret(h.b("deviceEphPriv"), ekH))
        assertArrayEquals(h.b("sharedSecret"), RelayCrypto.sharedSecret(h.b("hostEphPriv"), hs.ekD))
        val k = hs.finish(ekH, h.b("hs2Sig"))
        assertArrayEquals(h.b("prk"), k.prk)
        assertArrayEquals(h.b("kD2H"), k.d2h)
        assertArrayEquals(h.b("kH2D"), k.h2d)
    }

    @Test
    fun welcomeSignedByAnotherKeyIsRejected() {
        val h = section("handshake")
        val hs = RelayCrypto.Handshake(keys.s("hostSignPub"), keys.s("computerId"), deviceKey, h.b("deviceEphPriv"), h.b("n"))
        val forged = h.b("hs2Sig").also { it[0] = (it[0].toInt() xor 1).toByte() }
        val e = assertThrows(RelayCryptoException::class.java) { hs.finish(h.b("hostEphPub"), forged) }
        assertEquals(RelayCryptoException.Reason.BadSignature, e.reason)
        // A sign key that doesn't hash to the computer ID is refused up front.
        assertThrows(RelayCryptoException::class.java) { RelayCrypto.Handshake(keys.s("deviceSignPub"), keys.s("computerId"), deviceKey) }
    }

    @Test
    fun zeroSharedSecretIsRejected() {
        val e = assertThrows(RelayCryptoException::class.java) { RelayCrypto.sharedSecret(RelayCrypto.random(32), ByteArray(32)) }
        assertEquals(RelayCryptoException.Reason.ZeroSharedSecret, e.reason)
    }

    @Test
    fun frames() {
        val h = section("handshake")
        val keysFor = mapOf("d2h" to h.b("kD2H"), "h2d" to h.b("kH2D"))
        for (f in v.getValue("frames").jsonArray.map { it.jsonObject }) {
            val key = keysFor.getValue(f.s("dir"))
            val c = f.getValue("c").jsonPrimitive.long
            val final = f.getValue("final").jsonPrimitive.boolean
            val plaintext = f.b("plaintext")
            assertEquals(if (final) 0 else 1, plaintext[0].toInt())
            assertArrayEquals(f.s("message").toByteArray(), plaintext.copyOfRange(1, plaintext.size))
            assertArrayEquals(f.b("d"), RelayCrypto.sealFrame(key, c, final, f.s("message").toByteArray()))
            val (openedFinal, chunk) = RelayCrypto.openFrame(key, c, f.b("d"))
            assertEquals(final, openedFinal)
            assertEquals(f.s("message"), chunk.decodeToString())
        }
    }

    @Test
    fun sealerAndOpenerKeepExactCountersAndReassembleChunks() {
        val key = RelayCrypto.random(32)
        val sealer = RelayCrypto.FrameSealer(key)
        val opener = RelayCrypto.FrameOpener(key)
        val big = ByteArray(RelayCrypto.CHUNK_SIZE * 2 + 10) { (it % 251).toByte() }
        val frames = sealer.seal(big)
        assertEquals(listOf(0L, 1L, 2L), frames.map { it.first })
        assertNull(opener.open(0, frames[0].second))
        assertNull(opener.open(1, frames[1].second))
        assertArrayEquals(big, opener.open(2, frames[2].second))
        val next = sealer.seal("{}".toByteArray()).single()
        assertEquals(3L, next.first)
        // A replayed or skipped counter is fatal.
        assertThrows(RelayCryptoException::class.java) { RelayCrypto.FrameOpener(key).open(1, frames[1].second) }
        // Wrong direction key: decryption fails.
        assertThrows(RelayCryptoException::class.java) { RelayCrypto.FrameOpener(RelayCrypto.random(32)).open(0, frames[0].second) }
        // Inbound limit.
        val small = RelayCrypto.FrameOpener(key, limit = 1024)
        assertThrows(RelayCryptoException::class.java) { small.open(0, frames[0].second) }
    }

    @Test
    fun sas() {
        val s = section("sas")
        assertArrayEquals(s.b("commit"), RelayCrypto.sasCommit(deviceKey, s.b("deviceNonce")))
        assertEquals(s.s("code"), RelayCrypto.sasCode(hostSignPub, deviceKey, s.b("deviceNonce"), s.b("hostNonce")))
    }

    @Test
    fun pairingOfferId() {
        val p = section("pairing")
        assertEquals(p.s("offerId"), RelayCrypto.offerId(p.b("code")))
    }

    @Test
    fun push() {
        val p = section("push")
        assertArrayEquals(p.b("pushPub"), RelayCrypto.x25519Public(p.b("pushPriv")))
        assertArrayEquals(p.b("ephPub"), RelayCrypto.x25519Public(p.b("ephPriv")))
        val ss = RelayCrypto.sharedSecret(p.b("pushPriv"), p.b("ephPub"))
        assertArrayEquals(p.b("sharedSecret"), ss)
        assertArrayEquals(p.b("key"), RelayCrypto.pushKey(ss, cid, p.b("pushPub"), p.b("ephPub")))
        assertEquals(p.s("plaintext"), RelayCrypto.openPush(p.s("sealed"), keys.s("computerId"), p.b("pushPriv")).decodeToString())
        // Another device's push key can't open it.
        assertThrows(RelayCryptoException::class.java) { RelayCrypto.openPush(p.s("sealed"), keys.s("computerId"), RelayCrypto.random(32)) }
    }

    @Test
    fun mailbox() {
        val m = section("mailbox")
        val sealed = RelayCrypto.sealMailbox(
            m.s("plaintext").toByteArray(), keys.b("hostBoxPub"), keys.s("computerId"), deviceKey, m.s("clientNonce"),
            sign = { RelayCrypto.ed25519Sign(deviceSeed, it) }, ephemeral = m.b("ephPriv"),
        )
        assertArrayEquals(m.b("ephPub"), sealed.epk)
        assertArrayEquals(m.b("sharedSecret"), RelayCrypto.sharedSecret(keys.b("hostBoxPriv"), sealed.epk))
        assertArrayEquals(m.b("key"), RelayCrypto.mailboxKey(m.b("sharedSecret"), cid, deviceKey, sealed.epk))
        assertArrayEquals(m.b("ciphertext"), sealed.ciphertext)
        assertArrayEquals(m.b("sig"), sealed.sig)
        assertArrayEquals(m.b("blob"), sealed.blob)
    }

    @Test
    fun requestSignature() {
        val r = section("requestSig")
        val input = RelayCrypto.requestSigInput(r.s("method"), r.s("authority"), r.s("pathAndQuery"),
            r.getValue("ts").jsonPrimitive.long, r.s("nonce"), ByteArray(0))
        assertEquals(r.s("canonical"), input.decodeToString())
        assertArrayEquals(r.b("bodySha256"), RelayCrypto.sha256(ByteArray(0)))
        val identity = DeviceIdentity(deviceSeed, RelayCrypto.random(32))
        val header = identity.signatureHeader(r.s("method"), r.s("authority"), r.s("pathAndQuery"), ByteArray(0),
            ts = r.getValue("ts").jsonPrimitive.long, nonce = r.s("nonce"))
        assertEquals(r.s("header"), "Hypurr-Sig: $header")
        val url = java.net.URI("https://${r.s("authority")}${r.s("pathAndQuery")}")
        assertEquals(r.s("authority"), RelayCrypto.authority(url))
        assertEquals(r.s("pathAndQuery"), RelayCrypto.pathAndQuery(url))
        assertEquals("h.example:8443", RelayCrypto.authority(java.net.URI("wss://H.example:8443/x")))
    }

    @Test
    fun hostSignaturesVerify() {
        val claim = section("claim")
        assertTrue(RelayCrypto.ed25519Verify(hostSignPub, claim.s("canonical").toByteArray(), claim.b("sig")))
        val acl = section("acl")
        assertEquals(acl.s("json"), acl.b("d").decodeToString())
        assertTrue(RelayCrypto.ed25519Verify(hostSignPub, acl.b("d"), acl.b("sig")))
    }

    @Test
    fun strictBase64Url() {
        assertNull(B64.decode("AAA="))
        assertNull(B64.decode("AA+/"))
        assertNull(B64.decode("A"))
        assertArrayEquals(byteArrayOf(0, 0), B64.decode("AAA"))
    }
}
