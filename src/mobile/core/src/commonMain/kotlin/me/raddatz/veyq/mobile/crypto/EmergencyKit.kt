package me.raddatz.veyq.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What an Emergency Kit carries (en.account.key-model § The Emergency Kit): the account's email, its Secret Key's 32
 * bytes and the origin of the server the kit was made on, null where the token has none — a hint, never a check.
 *
 * The key's bytes are this object's: [wipe] zeroes them once the holder is done. [toString] never shows them.
 */
class Kit(val email: String, val secretKey: ByteArray, val server: String?) {
    fun wipe() = secretKey.fill(0)

    override fun toString() = "Kit(email=$email, server=$server)"
}

/**
 * The reader half of the Emergency Kit codec (a port of `src/packages/crypto/src/emergencyKit.ts` `parseKit` and
 * `isKitServer`), held to `contracts/crypto/vectors/v1.json` § `emergency_kit_token` by [Conformance]. The apps write no
 * kit, so the writer half (`kitPayload`) is not here.
 */
object EmergencyKit {
    /** 3: `srv` carries the server's origin. Every earlier version is refused, as the web's reader refuses it. */
    private const val VERSION = 3.0

    private val token = Regex("veyqkit:([A-Za-z0-9_-]+)")

    /**
     * The kit of the **first** `veyqkit:` token in [text] (a scanned QR's text, a PDF's bytes read as Latin-1, a pasted
     * token), checked in `parseKit`'s order: `v` 3, a non-empty `email`, an `srv` that is an origin where present, an
     * `sk` that reads as a Secret Key. Null for anything else — one answer, as on the web. A later token is never tried.
     */
    fun read(p: Primitives, text: String): Kit? {
        val symbols = token.find(text)?.groupValues?.get(1) ?: return null
        val raw = fromB64url(symbols) ?: return null
        try {
            val o = runCatching { Json.parseToJsonElement(raw.decodeToString(throwOnInvalidSequence = true)) }.getOrNull() as? JsonObject
                ?: return null
            val v = o["v"] as? JsonPrimitive ?: return null
            if (v.isString || v.content.toDoubleOrNull() != VERSION) return null
            val email = (o["email"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (email.isEmpty()) return null
            val srv = o["srv"]
            val server = if (srv == null) null else (srv as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { isServer(it) } ?: return null
            val sk = (o["sk"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val key = SecretKeyText.read(p, sk) ?: return null
            return Kit(email, key, server)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Whether [s] is an http or https origin spelled exactly as the URL standard serializes it — no path, no trailing
     * slash, no userinfo, no default port, a lower-case ASCII host, a port without leading zeros, an IPv4 address in
     * its dotted-decimal form and an IPv6 one in its compressed form. Parsed by hand (no URL library in the core): the
     * spelling must be the serialization, so anything the standard would rewrite is refused. A punycode label (`xn--`)
     * passes as the ASCII it is, unvalidated; a domain label holds only `a-z`, `0-9`, `-` and `_` (the standard allows
     * a few more symbols no server name uses).
     */
    fun isServer(s: String): Boolean {
        val scheme = when {
            s.startsWith("https://") -> "https"
            s.startsWith("http://") -> "http"
            else -> return false
        }
        val rest = s.substring(scheme.length + 3)
        if (rest.isEmpty() || rest.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '\\' }) return false
        val port: String?
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0 || !isIpv6(rest.substring(1, close))) return false
            val after = rest.substring(close + 1)
            port = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return false
            }
        } else {
            val colon = rest.indexOf(':')
            if (!isDomainOrIpv4(if (colon < 0) rest else rest.substring(0, colon))) return false
            port = if (colon < 0) null else rest.substring(colon + 1)
        }
        if (port != null) {
            if (port.isEmpty() || port.length > 5 || port.any { it !in '0'..'9' } || (port.length > 1 && port[0] == '0')) return false
            val n = port.toInt()
            if (n > 65535 || scheme == "https" && n == 443 || scheme == "http" && n == 80) return false
        }
        return true
    }

    private fun isDomainOrIpv4(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.') }) return false
        val labels = host.split('.')
        // The standard reads a host whose last label (one trailing empty label aside) is a number as an IPv4 address.
        val last = if (labels.size > 1 && labels.last().isEmpty()) labels[labels.size - 2] else labels.last()
        val numeric = last.isNotEmpty() && (last.all { it in '0'..'9' } || last.startsWith("0x"))
        if (!numeric) return true
        return labels.size == 4 && labels.all { l ->
            l.isNotEmpty() && l.length <= 3 && l.all { it in '0'..'9' } && (l.length == 1 || l[0] != '0') && l.toInt() <= 255
        }
    }

    /** An IPv6 address (no embedded IPv4: the standard serializes it as hex) in exactly its serialized form. */
    private fun isIpv6(a: String): Boolean {
        if (a.isEmpty() || a.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':') }) return false
        val halves = a.split("::")
        if (halves.size > 2) return false
        fun groups(h: String): List<String>? = if (h.isEmpty()) emptyList() else h.split(':').takeIf { g -> g.all { it.length in 1..4 } }
        val head = groups(halves[0]) ?: return false
        val tail = if (halves.size == 2) groups(halves[1]) ?: return false else emptyList()
        val n = head.size + tail.size
        if (halves.size == 2 && n > 7 || halves.size == 1 && n != 8) return false
        val pieces = head.map { it.toInt(16) } + List(8 - n) { 0 } + tail.map { it.toInt(16) }
        return serializeIpv6(pieces) == a
    }

    /** The URL standard's IPv6 serializer: the first longest run of two or more zero pieces compressed, lower-case hex. */
    private fun serializeIpv6(pieces: List<Int>): String {
        var bestAt = -1
        var bestLen = 1
        var i = 0
        while (i < 8) {
            if (pieces[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < 8 && pieces[j] == 0) j++
            if (j - i > bestLen) {
                bestAt = i
                bestLen = j - i
            }
            i = j
        }
        return buildString {
            var k = 0
            while (k < 8) {
                if (k == bestAt) {
                    append(if (k == 0) "::" else ":")
                    k += bestLen
                    continue
                }
                append(pieces[k].toString(16))
                if (k < 7) append(':')
                k++
            }
        }
    }

    /** Unpadded base64url, as `atob` reads it: a length that leaves one symbol over is no base64; spare bits dropped. */
    private fun fromB64url(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        val out = ByteArray(s.length * 6 / 8)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in s) {
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> return null
            }
            acc = ((acc shl 6) or v) and 0xfff
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc shr bits).toByte()
            }
        }
        return out
    }
}
