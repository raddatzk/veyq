package me.raddatz.veyq.mobile.trust

import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * A person's safety number, chain code and safety-number QR (ADR 0028 decision 3, ADR 0111 decision 14), ported from
 * `v1.ts`'s `fingerprintDigits` and `chainCodeDigits` and the web's `safetyCode.ts` (ADR 0148 decision 3). Pinned by
 * `v1.json#fingerprint` (`digits`, `chain_code`) and `#safety_code`.
 *
 * The QR's payload is `veyqid:2:<base64 of the 32-byte fingerprint hash>:<base64 of the 32-byte key-chain head>`: a marker,
 * a version, the digest the digits render from and the head the chain code renders from — nothing else, so a scan and a
 * read-aloud cannot disagree. A `veyqid:1` code (the fingerprint alone) is the other app being outdated, never compared on
 * the half it carries.
 */
object SafetyNumbers {
    const val SCHEME = "veyqid"

    /** Six groups of five digits, each a 40-bit big-endian chunk of the 32-byte fingerprint hash mod 100000. */
    fun fingerprintDigits(hash: ByteArray): String {
        require(hash.size == 32) { "fingerprint hash: not a 32-byte digest" }
        return groups(hash, 6)
    }

    /** The chain code: the first two groups of the same rendering over a 32-byte key-chain head. */
    fun chainCodeDigits(head: ByteArray): String {
        require(head.size == 32) { "chain code: not a 32-byte head" }
        return groups(head, 2)
    }

    private fun groups(b: ByteArray, n: Int): String = (0 until n).joinToString(" ") { i ->
        var v = 0L
        for (j in 0 until 5) v = v * 256 + (b[i * 5 + j].toInt() and 0xff)
        (v % 100_000).toString().padStart(5, '0')
    }

    /** The QR payload of a fingerprint hash and a head (both 32 bytes) — one's own, on one's own page. */
    fun payload(fingerprintHash: ByteArray, head: ByteArray): String {
        require(fingerprintHash.size == 32 && head.size == 32) { "safety code: not a 32-byte hash and head" }
        return "$SCHEME:2:${b64(fingerprintHash)}:${b64(head)}"
    }

    /** What a scanned string carries: a current code (fingerprint and head, canonical base64), an outdated one (`veyqid:1`,
     *  the fingerprint alone), or null — another marker, another version, anything around it, a field not exactly 32 bytes. */
    sealed interface Parsed {
        data class Current(val fingerprint: String, val head: String) : Parsed
        data class Outdated(val fingerprint: String) : Parsed
    }

    private const val B64 = "[A-Za-z0-9+/]+={0,2}"
    private val CURRENT = Regex("^$SCHEME:2:($B64):($B64)$")
    private val OUTDATED = Regex("^$SCHEME:1:($B64)$")

    fun parse(text: String): Parsed? {
        val t = text.trim()
        CURRENT.matchEntire(t)?.let { m ->
            val f = canonical(m.groupValues[1]) ?: return null
            val h = canonical(m.groupValues[2]) ?: return null
            return Parsed.Current(f, h)
        }
        OUTDATED.matchEntire(t)?.let { m -> return canonical(m.groupValues[1])?.let(Parsed::Outdated) }
        return null
    }

    /** A base64 field that decodes to exactly 32 bytes, re-encoded canonically (so a compare is over the bytes). */
    private fun canonical(s: String): String? = runCatching { unb64(s) }.getOrNull()?.takeIf { it.size == 32 }?.let(::b64)

    /** What a scan says against the key and head the caller would trust. */
    enum class Verdict {
        /** Not a veyq safety code at all (another QR in the room, an Emergency Kit, a pairing code). */
        FOREIGN,
        /** A well-formed `veyqid:1` code: the other app is too old to show its head; nothing is compared. */
        OUTDATED,
        /** A code of a different key or a different head (a step withheld from one side): never accepted. */
        MISMATCH,
        /** The scanned digest and head are the expected ones. */
        MATCH,
    }

    /** [expectedFingerprint] and [expectedHead] in base64, as the directory serves them anchored. */
    fun compareScan(text: String, expectedFingerprint: String, expectedHead: String): Verdict = when (val s = parse(text)) {
        null -> Verdict.FOREIGN
        is Parsed.Outdated -> Verdict.OUTDATED
        is Parsed.Current -> if (s.fingerprint == canonical(expectedFingerprint) && s.head == canonical(expectedHead)) Verdict.MATCH else Verdict.MISMATCH
    }
}
