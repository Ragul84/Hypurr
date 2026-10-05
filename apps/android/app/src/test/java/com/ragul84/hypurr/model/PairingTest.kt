package com.ragul84.hypurr.model

import com.ragul84.hypurr.crypto.RelayCrypto
import com.ragul84.hypurr.crypto.b64url
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingTest {
    private val sk = RelayCrypto.ed25519Public(ByteArray(32) { 1 })
    private val id = RelayCrypto.computerId(sk)
    private val bk = RelayCrypto.x25519Public(ByteArray(32) { 2 }).b64url()
    private val code = ByteArray(16) { 8 }.b64url()

    private fun link(v: String = "3", id: String = this.id, urls: String = "http://192.168.1.4:19222,ftp://bad,https://mac.ts.net",
                     cloud: String = "", name: String = "Kevin%E2%80%99s%20Mac+mini") =
        "hypurr://pair?v=$v&name=$name&id=$id&sk=${sk.b64url()}&bk=$bk&code=$code&urls=$urls&cloud=$cloud"

    @After
    fun reset() {
        Pairing.allowLocalCloud = false
    }

    @Test
    fun parsesTheHostLink() {
        val p = Pairing.parse(link())
        assertEquals(id, p.computer.id)
        assertEquals("Kevin’s Mac+mini", p.computer.name)
        assertEquals(listOf("http://192.168.1.4:19222", "https://mac.ts.net"), p.computer.urls)
        assertNull(p.computer.cloud)
        assertEquals(code, p.code)
        assertEquals("C2lEWgux5-BzsvF3aeGO6A", p.offerId)
    }

    @Test
    fun problems() {
        assertEquals(Pairing.Problem.Kind.NotPairingLink, assertThrows(Pairing.Problem::class.java) { Pairing.parse("https://hypurr.dev") }.kind)
        assertEquals(Pairing.Problem.Kind.OutdatedHost, assertThrows(Pairing.Problem::class.java) { Pairing.parse(link(v = "2")) }.kind)
        // The ID must be the hash of the signing key the QR carries.
        val other = RelayCrypto.computerId(RelayCrypto.random(32))
        assertEquals(Pairing.Problem.Kind.Invalid, assertThrows(Pairing.Problem::class.java) { Pairing.parse(link(id = other)) }.kind)
        assertEquals(Pairing.Problem.Kind.Invalid, assertThrows(Pairing.Problem::class.java) { Pairing.parse(link(urls = "")) }.kind)
        assertEquals(Pairing.Problem.Kind.Invalid,
            assertThrows(Pairing.Problem::class.java) { Pairing.parse(link(cloud = "http://relay.example.dev")) }.kind)
    }

    @Test
    fun cloudIsHttpsExceptALocalDevCloudInDebug() {
        assertEquals("https://api.hypurr.dev", Pairing.parse(link(urls = "", cloud = "https://api.hypurr.dev")).computer.cloud)
        assertThrows(Pairing.Problem::class.java) { Pairing.parse(link(urls = "", cloud = "http://127.0.0.1:8787")) }
        Pairing.allowLocalCloud = true
        assertEquals("http://127.0.0.1:8787", Pairing.parse(link(urls = "", cloud = "http://127.0.0.1:8787")).computer.cloud)
    }

    @Test
    fun defaultName() {
        assertEquals("My computer", Pairing.parse(link(name = "")).computer.name)
    }
}
