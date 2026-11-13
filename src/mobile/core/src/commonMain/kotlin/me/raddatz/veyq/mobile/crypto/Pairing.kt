package me.raddatz.veyq.mobile.crypto

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pairing ceremony's crypto (ADR 0059), a port of `src/web/src/features/account/pairing/pairing.ts` over
 * `src/packages/crypto/src/v1.ts`' pairing half, held to `contracts/crypto/vectors/v1.json` § `pairing_channel` by
 * [Conformance]: the channel a scanned code's secret derives, the MACs over the two values the relay carries, the check
 * number, the six-digit code, the answer's and the envelope's wire forms, and the grant's plaintext.
 *
 * **The relay sees only the handle (§11).** The code carries 32 bytes the server never receives; both devices derive
 * the handle and the channel key from them. The trusted device seals only to an answer whose MAC verifies, the new
 * device opens only an envelope whose MAC verifies. The codes of §5 stop someone who is not the relay; the channel key
 * stops the relay; an attacker who reads the QR and runs the relay holds the channel key, and nothing here claims more.
 *
 * Nothing here talks to a server or names one (§4): the payload is a marker and a secret, and [PairingPayload.parse]
 * refuses anything else.
 */
class Pairing(private val p: Primitives) {
    private val suite = V1(p)
    private val trust = Trust(suite)

    /** 32 random bytes whose standard base64 does not start with `/` — such a payload would read `veyqpair://`, a URL,
     *  which is the reading §4 keeps impossible; the draw is simply repeated, as the web's `mintPairingSecret` does. */
    fun mintSecret(): ByteArray {
        while (true) {
            val secret = p.randomBytes(SECRET_BYTES)
            if (!b64(secret).startsWith("/")) return secret
        }
    }

    /** `HKDF-SHA256(secret, "", "veyq/v1/pairing-handle", 32)`: the only value of the channel the server receives. */
    fun handle(secret: ByteArray): ByteArray = p.hkdfSha256(secret, ByteArray(0), utf8("veyq/v1/pairing-handle"), 32)

    /** `HKDF-SHA256(secret, "", "veyq/v1/pairing-channel", 32)`: the key only the code's two holders derive. */
    fun channelKey(secret: ByteArray): ByteArray = p.hkdfSha256(secret, ByteArray(0), utf8("veyq/v1/pairing-channel"), 32)

    /** The *add a new device* answer's MAC, over the serialized transport key. */
    fun answerMac(channelKey: ByteArray, transportKey: ByteArray): ByteArray =
        mac(channelKey, Context("pairing-answer", "transportKey" to transportKey))

    /** The *sign in with a QR code* answer's MAC, over the transport key and the transcribed six-digit code. */
    fun signInAnswerMac(channelKey: ByteArray, transportKey: ByteArray, code: String): ByteArray =
        mac(channelKey, Context("pairing-sign-in-answer", "transportKey" to transportKey, "code" to code))

    /** The envelope's MAC, over the sealed grant's bytes. */
    fun envelopeMac(channelKey: ByteArray, envelope: ByteArray): ByteArray =
        mac(channelKey, Context("pairing-envelope", "envelope" to envelope))

    fun answerVerifies(channelKey: ByteArray, transportKey: ByteArray, mac: ByteArray): Boolean =
        macEquals(answerMac(channelKey, transportKey), mac)

    fun signInAnswerVerifies(channelKey: ByteArray, transportKey: ByteArray, code: String, mac: ByteArray): Boolean =
        runCatching { signInAnswerMac(channelKey, transportKey, code) }.getOrNull()?.let { macEquals(it, mac) } ?: false

    fun envelopeVerifies(channelKey: ByteArray, envelope: ByteArray, mac: ByteArray): Boolean =
        macEquals(envelopeMac(channelKey, envelope), mac)

    private fun mac(channelKey: ByteArray, statement: Context): ByteArray =
        p.hmacSha256(channelKey, encodeContext(Purposes.STATEMENT, statement))

    /**
     * The five digits both screens compare in *add a new device* (§5): the first group of the answering key's safety
     * number (`#fingerprint`'s digits), so nothing new is pinned. Shown only after the answer's MAC verified — the relay
     * could grind five digits, it cannot forge the MAC.
     */
    fun checkNumber(transportPublic: IdentityPublic): String {
        val h = trust.fingerprintHash(transportPublic.serialize())
        var v = 0L
        for (j in 0 until 5) v = v * 256 + (h[j].toInt() and 0xff)
        return (v % 100_000).toString().padStart(5, '0')
    }

    /**
     * The six-digit code of *sign in with a QR code* (§5, §6): drawn fresh, never derived from a key (the attacker
     * authored the QR, so anything derivable from it is theirs). Uniform: a byte from 250 up is drawn again, since
     * `% 10` over all 256 values would favour 0–5.
     */
    fun drawCode(): String {
        val code = StringBuilder()
        while (code.length < CODE_DIGITS) {
            for (b in p.randomBytes(CODE_DIGITS)) {
                val n = b.toInt() and 0xff
                if (n >= CODE_REJECT_AT) continue
                code.append(n % 10)
                if (code.length == CODE_DIGITS) break
            }
        }
        return code.toString()
    }

    /** The new device's single-use transport key: a whole identity bundle, so its wire form and its check number are
     *  the ones already pinned (`serializeIdentityPublic`, `#fingerprint`). */
    fun mintTransportKey(): Pair<IdentityPrivate, IdentityPublic> {
        val priv = IdentityPrivate(p.randomBytes(32), p.randomBytes(32))
        return priv to suite.identityPublic(priv)
    }

    /** `b64std(lp(serializeIdentityPublic(pub)) ‖ lp(mac))`: the *add a new device* answer. */
    fun transportAnswer(channelKey: ByteArray, pub: IdentityPublic): String {
        val serialized = pub.serialize()
        return b64(concat(lp(serialized), lp(answerMac(channelKey, serialized))))
    }

    /** The *sign in with a QR code* answer: the same shape, its MAC over the code too. */
    fun signInAnswer(channelKey: ByteArray, pub: IdentityPublic, code: String): String {
        val serialized = pub.serialize()
        return b64(concat(lp(serialized), lp(signInAnswerMac(channelKey, serialized, code))))
    }

    /** The trusted device's gate in *add a new device*: the key to seal to, or [PairingRefused]. */
    fun readTransportAnswer(channelKey: ByteArray, answer: String): IdentityPublic {
        val (pub, serialized, mac) = readAnswer(answer)
        if (!answerVerifies(channelKey, serialized, mac)) throw PairingRefused(PairingRefusal.ANSWER_REFUSED)
        return pub
    }

    /** The trusted device's gate in *sign in with a QR code*: a wrong code and a swapped key are refused alike. */
    fun readSignInAnswer(channelKey: ByteArray, code: String, answer: String): IdentityPublic {
        val (pub, serialized, mac) = readAnswer(answer)
        if (!signInAnswerVerifies(channelKey, serialized, code, mac)) throw PairingRefused(PairingRefusal.ANSWER_REFUSED)
        return pub
    }

    private fun readAnswer(answer: String): Triple<IdentityPublic, ByteArray, ByteArray> {
        val bytes = runCatching { unb64(answer) }.getOrNull() ?: throw PairingRefused(PairingRefusal.ANSWER_UNREADABLE)
        val (serialized, mac) = runCatching { splitLp(bytes, 2) }.getOrNull() ?: throw PairingRefused(PairingRefusal.ANSWER_UNREADABLE)
        val pub = runCatching { suite.deserializeIdentityPublic(serialized) }.getOrNull()
            ?: throw PairingRefused(PairingRefusal.ANSWER_UNREADABLE)
        // The key sealed to must be byte for byte the MACed one: the decoder admits one encoding only.
        if (!pub.serialize().contentEquals(serialized)) throw PairingRefused(PairingRefusal.ANSWER_UNREADABLE)
        return Triple(pub, serialized, mac)
    }

    /** `b64std(lp(sealTo(pairing-grant, pub.kem, utf8(json))) ‖ lp(mac))`. The code never enters this (§6). */
    fun sealGrant(channelKey: ByteArray, pub: IdentityPublic, grant: PairingGrant): String {
        val sealed = suite.sealTo(GRANT, pub.kem, utf8(PairingGrant.write(grant)))
        return b64(concat(lp(sealed), lp(envelopeMac(channelKey, sealed))))
    }

    /** The MAC first, then the seal: anyone can seal to this device's public key, only the MAC says who did (§11). */
    fun openGrant(channelKey: ByteArray, priv: IdentityPrivate, envelope: String): PairingGrant {
        val bytes = runCatching { unb64(envelope) }.getOrNull() ?: throw PairingRefused(PairingRefusal.ENVELOPE_UNREADABLE)
        val (sealed, mac) = runCatching { splitLp(bytes, 2) }.getOrNull() ?: throw PairingRefused(PairingRefusal.ENVELOPE_UNREADABLE)
        if (!envelopeVerifies(channelKey, sealed, mac)) throw PairingRefused(PairingRefusal.ENVELOPE_REFUSED)
        val plaintext = suite.openAs(GRANT, suite.xwingKey(priv.kemSeed), sealed)
        try {
            val text = runCatching { plaintext.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                ?: throw PairingRefused(PairingRefusal.GRANT_UNREADABLE)
            return PairingGrant.read(text) ?: throw PairingRefused(PairingRefusal.GRANT_UNREADABLE)
        } finally {
            plaintext.fill(0)
        }
    }

    companion object {
        const val SECRET_BYTES = 32
        private const val CODE_DIGITS = 6
        private const val CODE_REJECT_AT = 250
        private val GRANT = Context("pairing-grant")

        /** Constant-time over the MAC bytes, as `v1.ts`' `macEquals`. */
        internal fun macEquals(expected: ByteArray, mac: ByteArray): Boolean {
            if (expected.size != mac.size) return false
            var diff = 0
            for (i in expected.indices) diff = diff or (expected[i].toInt() xor mac[i].toInt())
            return diff == 0
        }
    }
}

/** Why a value that crossed the relay was refused — each its own name, as on the web. */
enum class PairingRefusal { ANSWER_UNREADABLE, ANSWER_REFUSED, ENVELOPE_UNREADABLE, ENVELOPE_REFUSED, GRANT_UNREADABLE }

class PairingRefused(val reason: PairingRefusal) : Exception("pairing_refused: ${reason.name.lowercase()}")

/** Which direction a scanned code belongs to, by its marker (the only direction signal there is, client-side). */
enum class PairingMarker(val prefix: String) {
    /** *Add a new device*: the trusted device shows it, the new one scans. */
    ADD_DEVICE("veyqpair:"),

    /** *Sign in with a QR code*: the new device shows it, the trusted one scans. */
    SIGN_IN("veyqsignin:"),
}

/**
 * The displayed code's whole text: a marker and a 32-byte secret, nothing else (ADR 0059 §4, §11). The grammar is
 * `#pairing_channel.payloads`' note, held to its cases by [Conformance].
 */
object PairingPayload {
    private val SECRET_TEXT = Regex("[A-Za-z0-9+/]{43}=?")
    private val decoder = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun of(secret: ByteArray, marker: PairingMarker): String {
        require(secret.size == Pairing.SECRET_BYTES) { "a pairing secret is 32 bytes" }
        return marker.prefix + b64(secret)
    }

    /**
     * The secret [text] carries under [marker], or null. Anchored at both ends: after trimming space, tab, CR and LF,
     * the whole text is the exact marker and the canonical standard base64 of 32 bytes, padded or not, not starting
     * with `/`. The other direction's marker is refused, which is what keeps a mis-scan from burning the write-once
     * answer slot of the real ceremony.
     */
    fun parse(text: String, marker: PairingMarker): ByteArray? {
        val t = text.trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        if (!t.startsWith(marker.prefix)) return null
        val body = t.substring(marker.prefix.length)
        if (!SECRET_TEXT.matches(body) || body.startsWith("/")) return null
        val bytes = runCatching { decoder.decode(body) }.getOrNull() ?: return null
        // Canonical only: the last symbol's unused bits are zero, so one secret has one text.
        return bytes.takeIf { it.size == Pairing.SECRET_BYTES && b64(it) == body.padEnd(44, '=') }
    }
}

/** The linking device's trust-state marks as the envelope carries them (ADR 0028 Decision 7). */
data class PairingMarks(val pins: Long, val register: Long, val epochs: Map<String, Long>)

/**
 * What the trusted device seals and the new device opens (ADR 0059 §2, §9). The Master Key's bytes are this object's:
 * [wipe] zeroes them once the holder is done; [toString] shows neither the key nor the token.
 */
class PairingGrant(
    val email: String,
    val masterKey: ByteArray,
    val refreshToken: String,
    val label: String,
    val marks: PairingMarks? = null,
) {
    fun wipe() = masterKey.fill(0)

    override fun toString() = "PairingGrant(email=$email, label=$label, marks=$marks)"

    companion object {
        private const val MAX_MARK = 9_007_199_254_740_991.0 // 2^53 - 1, JavaScript's largest safe integer

        /** The plaintext JSON, byte for byte the web's `writeGrant` (`#pairing_channel.grant`'s `written` cases). */
        fun write(g: PairingGrant): String = buildJsonObject {
            put("v", 1)
            put("email", g.email)
            put("masterKey", b64(g.masterKey))
            put("refreshToken", g.refreshToken)
            put("label", g.label)
            g.marks?.let { m ->
                put("marks", buildJsonObject {
                    put("pins", m.pins)
                    put("register", m.register)
                    put("epochs", buildJsonObject { for ((scope, e) in m.epochs) put(scope, e) })
                })
            }
        }.toString()

        /** The plaintext read in the writer's shape, or null; a field it does not know is ignored. */
        fun read(text: String): PairingGrant? {
            val o = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val v = o["v"] as? JsonPrimitive ?: return null
            if (v.isString || v.content.toDoubleOrNull() != 1.0) return null
            val email = o.string("email") ?: return null
            val masterKeyText = o.string("masterKey") ?: return null
            val refreshToken = o.string("refreshToken") ?: return null
            val label = o.string("label") ?: return null
            val masterKey = runCatching { Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(masterKeyText) }.getOrNull()
                ?.takeIf { it.size == 32 } ?: return null
            if ("marks" !in o) return PairingGrant(email, masterKey, refreshToken, label)
            val marks = (o["marks"] as? JsonObject)?.let { readMarks(it) } ?: return null
            return PairingGrant(email, masterKey, refreshToken, label, marks)
        }

        private fun JsonObject.string(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun readMarks(m: JsonObject): PairingMarks? {
            val pins = mark(m["pins"]) ?: return null
            val register = mark(m["register"]) ?: return null
            val raw = m["epochs"] as? JsonObject ?: return null
            val epochs = LinkedHashMap<String, Long>()
            for ((scope, e) in raw) epochs[scope] = mark(e) ?: return null
            return PairingMarks(pins, register, epochs)
        }

        /** A non-negative integer no larger than 2^53 - 1, as the web's `Number.isSafeInteger(n) && n >= 0`. */
        private fun mark(e: Any?): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return null
            val d = p.content.toDoubleOrNull() ?: return null
            if (d < 0 || d > MAX_MARK || d != kotlin.math.floor(d)) return null
            return d.toLong()
        }
    }
}
