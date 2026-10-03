package com.ragul84.hypurr.model

import com.ragul84.hypurr.crypto.B64
import com.ragul84.hypurr.crypto.RelayCrypto
import java.net.URI
import java.net.URLDecoder

/**
 * A scanned QR / pairing link (§4.1). Short-lived: only the resulting [Computer] is kept.
 * `hypurr://pair?v=3&name=…&id=<computerId>&sk=…&bk=…&code=…&urls=a,b&cloud=…`
 */
data class Pairing(val computer: Computer, val code: String) {
    class Problem(val kind: Kind) : Exception(kind.message) {
        enum class Kind(val message: String) {
            NotPairingLink("That isn't a Hypurr pairing code."),
            OutdatedHost("Update Hypurr on your computer, then show a new pairing code."),
            Invalid("This pairing code is damaged. Show a new one on your computer."),
        }
    }

    /** The relay's pairing socket parameter. */
    val offerId: String? get() = B64.decode(code)?.let(RelayCrypto::offerId)

    companion object {
        /** Debug builds also accept a local dev cloud over plain http. */
        var allowLocalCloud: Boolean = false

        fun parse(link: String): Pairing {
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: throw Problem(Problem.Kind.NotPairingLink)
            if (uri.scheme != "hypurr" || uri.host != "pair" || uri.rawQuery == null) throw Problem(Problem.Kind.NotPairingLink)
            val q = LinkedHashMap<String, String>()
            for (item in uri.rawQuery.split('&')) {
                if (item.isEmpty()) continue
                val name = decode(item.substringBefore('='))
                // First value wins, like the Swift parser.
                q.putIfAbsent(name, decode(item.substringAfter('=', "")))
            }
            if (q["v"] != "3") throw Problem(Problem.Kind.OutdatedHost)
            val id = q["id"]
            val sk = q["sk"]
            val bk = q["bk"]
            val code = q["code"]
            val skRaw = sk?.let(B64::decode)
            if (id == null || sk == null || bk == null || code == null ||
                skRaw?.size != 32 || B64.decode(bk)?.size != 32 || B64.decode(code)?.size != 16 ||
                RelayCrypto.computerId(skRaw) != id
            ) throw Problem(Problem.Kind.Invalid)
            val urls = q["urls"].orEmpty().split(',').filter { it.isNotEmpty() && isDirectUrl(it) }
            val cloud = q["cloud"]?.takeIf { it.isNotEmpty() }?.also { if (!isCloudUrl(it)) throw Problem(Problem.Kind.Invalid) }
            if (urls.isEmpty() && cloud == null) throw Problem(Problem.Kind.Invalid)
            val name = q["name"]?.takeIf { it.isNotEmpty() } ?: "My computer"
            return Pairing(Computer(id = id, name = name, signKey = sk, boxKey = bk, urls = urls, cloud = cloud), code)
        }

        fun parseOrNull(link: String): Pairing? = runCatching { parse(link) }.getOrNull()

        // URLComponents semantics: `+` stays a plus.
        private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

        fun isDirectUrl(s: String): Boolean {
            val u = runCatching { URI(s) }.getOrNull() ?: return false
            return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty()
        }

        /** https only; debug builds also allow a local dev cloud. */
        fun isCloudUrl(s: String): Boolean {
            val u = runCatching { URI(s) }.getOrNull() ?: return false
            if (u.host.isNullOrEmpty()) return false
            if (u.scheme == "https") return true
            return allowLocalCloud && u.scheme == "http" && u.host in setOf("127.0.0.1", "localhost")
        }
    }
}
