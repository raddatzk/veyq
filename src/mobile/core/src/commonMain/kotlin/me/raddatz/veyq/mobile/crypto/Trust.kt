package me.raddatz.veyq.mobile.crypto

/**
 * The suite's trust derivations, ported from `src/packages/crypto/src/v1.ts`: an account's key chain (ADR 0111
 * decision 7), the fingerprint hash a pin holds, the identity-pin store's ids and set digest (ADR 0104), and the scope
 * succession that proves a new epoch's key (ADR 0115 decision 5). Pinned by `v1.json#key_chain`, `#identity_pin` and
 * `#scope_succession`. Every check here reads bytes a server served, so none of them throws: a refusal is the answer.
 */
class Trust(private val suite: V1) {
    private val p = suite.p

    // ---- key chain ----

    /** The two public keys a step installs: the identity public bundle and the Secret-Key proof public key. */
    open class KeyChainKeys(val identityPublicKey: ByteArray, val secretKeyProofPublicKey: ByteArray)

    /** One served step, decoded; [previousHead] and both signatures are null exactly at index 0. */
    class KeyChainLink(
        val index: Long,
        val previousHead: ByteArray?,
        identityPublicKey: ByteArray,
        secretKeyProofPublicKey: ByteArray,
        val identitySignature: ByteArray?,
        val proofSignature: ByteArray?,
    ) : KeyChainKeys(identityPublicKey, secretKeyProofPublicKey)

    enum class KeyChainRefusal { MALFORMED, PREDECESSOR, IDENTITY_SIGNATURE, PROOF_SIGNATURE, HEAD_NOT_IN_CHAIN }

    sealed interface KeyChainVerdict {
        /** The newest step's [head], its [index], and where the walk passed the head it started from ([fromIndex]). */
        class Ok(val head: ByteArray, val index: Int, val fromIndex: Int?, val keys: KeyChainKeys) : KeyChainVerdict
        class Refused(val step: Int, val reason: KeyChainRefusal) : KeyChainVerdict
    }

    fun keyChainGenesisStatement(userId: String, born: KeyChainKeys): ByteArray =
        encodeContext(
            Purposes.STATEMENT,
            Context("key-chain-genesis", "userId" to userId, "identityPublicKey" to born.identityPublicKey, "secretKeyProofPublicKey" to born.secretKeyProofPublicKey),
        )

    fun keyChainStepStatement(userId: String, index: Long, previousHead: ByteArray, retired: KeyChainKeys, installed: KeyChainKeys): ByteArray {
        if (index < 1) throw SealContextInvalid("key-chain-step index is not 1 or more")
        return encodeContext(
            Purposes.STATEMENT,
            Context(
                "key-chain-step",
                "userId" to userId, "index" to index, "previousHead" to previousHead,
                "previousIdentityDigest" to p.sha256(retired.identityPublicKey),
                "previousProofKeyDigest" to p.sha256(retired.secretKeyProofPublicKey),
                "identityPublicKey" to installed.identityPublicKey, "secretKeyProofPublicKey" to installed.secretKeyProofPublicKey,
            ),
        )
    }

    /** `SHA-256(utf8("veyq/v1/key-chain/head") ‖ statement)`. */
    fun keyChainHead(statement: ByteArray): ByteArray = p.sha256(concat(utf8("veyq/v1/key-chain/head"), statement))

    /**
     * Walks [links] (genesis first) of [userId]: each step names the head the one before makes and carries both
     * signatures under the keys that step retires. With [fromHead] (a pinned head) the walk must pass it. The port of
     * `verifyKeyChain`.
     */
    fun verifyKeyChain(userId: String, links: List<KeyChainLink>, fromHead: ByteArray?): KeyChainVerdict {
        if (links.isEmpty()) return KeyChainVerdict.Refused(0, KeyChainRefusal.MALFORMED)
        var head = ByteArray(0)
        var fromIndex: Int? = null
        for ((i, link) in links.withIndex()) {
            fun refuse(r: KeyChainRefusal) = KeyChainVerdict.Refused(i, r)
            if (link.index != i.toLong() || !wellFormed(link)) return refuse(KeyChainRefusal.MALFORMED)
            if (i == 0) {
                if (link.previousHead != null || link.identitySignature != null || link.proofSignature != null) return refuse(KeyChainRefusal.MALFORMED)
                head = runCatching { keyChainHead(keyChainGenesisStatement(userId, link)) }.getOrNull() ?: return refuse(KeyChainRefusal.MALFORMED)
            } else {
                val prev = link.previousHead
                val idSig = link.identitySignature
                val proofSig = link.proofSignature
                if (prev == null || idSig == null || proofSig == null) return refuse(KeyChainRefusal.MALFORMED)
                if (!prev.contentEquals(head)) return refuse(KeyChainRefusal.PREDECESSOR)
                val retired = links[i - 1]
                val stmt = runCatching { keyChainStepStatement(userId, i.toLong(), head, retired, link) }.getOrNull()
                    ?: return refuse(KeyChainRefusal.MALFORMED)
                if (!verifies(suite.deserializeIdentityPublic(retired.identityPublicKey).sign, stmt, idSig)) return refuse(KeyChainRefusal.IDENTITY_SIGNATURE)
                if (!verifies(retired.secretKeyProofPublicKey, stmt, proofSig)) return refuse(KeyChainRefusal.PROOF_SIGNATURE)
                head = keyChainHead(stmt)
            }
            if (fromHead != null && head.contentEquals(fromHead)) fromIndex = i
        }
        val newest = links.size - 1
        if (fromHead != null && fromIndex == null) return KeyChainVerdict.Refused(newest, KeyChainRefusal.HEAD_NOT_IN_CHAIN)
        return KeyChainVerdict.Ok(head, newest, fromIndex, links[newest])
    }

    private fun wellFormed(k: KeyChainKeys): Boolean =
        k.secretKeyProofPublicKey.size == 32 && runCatching { suite.deserializeIdentityPublic(k.identityPublicKey) }.isSuccess

    private fun verifies(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching { p.ed25519Verify(publicKey, message, signature) }.getOrDefault(false)

    /** `SHA-256(utf8("veyq/v1/fingerprint") ‖ bundle)` over a serialized identity public bundle — what a pin holds. */
    fun fingerprintHash(identityPublicKey: ByteArray): ByteArray {
        suite.deserializeIdentityPublic(identityPublicKey)
        return p.sha256(concat(utf8("veyq/v1/fingerprint"), identityPublicKey))
    }

    // ---- identity pins ----

    /** `HKDF-SHA256(masterKey, "", "veyq/v1/identity-pin-index", 32)`. */
    fun identityPinIndexKey(masterKey: ByteArray): ByteArray = p.hkdfSha256(masterKey, ByteArray(0), utf8("veyq/v1/identity-pin-index"), 32)

    /** `HMAC-SHA256(indexKey, utf8(userId))`; canonical lowercase UUIDs only. */
    fun identityPinId(indexKey: ByteArray, userId: String): ByteArray {
        if (!CANONICAL_UUID.matches(userId)) throw SealContextInvalid("identity-pin userId is not a canonical lowercase UUID")
        return p.hmacSha256(indexKey, utf8(userId))
    }

    /** The digest of a whole pin set over (pinId, sealed entry) pairs, in ascending pinId byte order; one id once. */
    fun identityPinSetDigest(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        if (entries.any { it.first.size != 32 }) throw SealContextInvalid("identity-pin pinId is not 32 bytes")
        val sorted = entries.sortedWith { a, b -> compareUnsigned(a.first, b.first) }
        val parts = mutableListOf(utf8("veyq/v1/identity-pin-set"))
        sorted.forEachIndexed { i, (id, entry) ->
            if (i > 0 && compareUnsigned(sorted[i - 1].first, id) == 0) throw SealContextInvalid("identity-pin set holds one id twice")
            parts += lp(id)
            parts += lp(p.sha256(entry))
        }
        return p.sha256(concat(*parts.toTypedArray()))
    }

    /**
     * One pinned contact (ADR 0104): the fingerprint hash of the key at the pinned head and the head (both base64 of 32
     * bytes), that head's step index, first-seen (epoch ms), and whether a join record replaced a pin off its chain.
     */
    data class Pin(val fingerprint: String, val head: String, val index: Long, val firstSeenAt: Long, val replaced: Boolean)

    /**
     * An entry's plaintext: the compact JSON `{"u","f","h","i","t","r"}` in that key order — byte for byte what the web
     * writes (`JSON.stringify`), pinned by `seal.sym.identity-pin`'s plaintext — padded with spaces to [PIN_ENTRY_BYTES].
     */
    fun encodePinEntry(userId: String, pin: Pin): ByteArray {
        if (!CANONICAL_UUID.matches(userId) || !B64_32.matches(pin.fingerprint) || !B64_32.matches(pin.head) || pin.index < 0 || pin.firstSeenAt < 0) {
            throw SealContextInvalid("identity pin entry: a field out of shape")
        }
        val json = utf8("""{"u":"$userId","f":"${pin.fingerprint}","h":"${pin.head}","i":${pin.index},"t":${pin.firstSeenAt},"r":${if (pin.replaced) 1 else 0}}""")
        if (json.size > PIN_ENTRY_BYTES) throw SealContextInvalid("identity pin entry: over its padded length")
        return ByteArray(PIN_ENTRY_BYTES) { if (it < json.size) json[it] else 0x20 }
    }

    /** An entry's plaintext read back, or null for anything that is not one: the web's `openEntry` rules. */
    fun decodePinEntry(plain: ByteArray): Pair<String, Pin>? = runCatching {
        if (plain.size != PIN_ENTRY_BYTES) return null
        val o = JSON.parseToJsonElement(plain.decodeToString()) as? kotlinx.serialization.json.JsonObject ?: return null
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        val u = str("u") ?: return null
        val f = str("f")?.takeIf { unb64(it).size == 32 } ?: return null
        val h = str("h")?.takeIf { unb64(it).size == 32 } ?: return null
        val i = num("i")?.takeIf { it in 0..MAX_SAFE } ?: return null
        val t = num("t")?.takeIf { it in 0..MAX_SAFE } ?: return null
        val r = num("r")?.takeIf { it == 0L || it == 1L } ?: return null
        u to Pin(f, h, i, t, r == 1L)
    }.getOrNull()

    /** The store head's plaintext, `{"version":2,"count":n,"digest":"…"}`, pinned by `seal.sym.identity-pins`. */
    fun encodePinHead(count: Int, digest: ByteArray): ByteArray = utf8("""{"version":2,"count":$count,"digest":"${b64(digest)}"}""")

    /** A head read back as (count, digest), or null for anything but a version-2 head. */
    fun decodePinHead(plain: ByteArray): Pair<Int, ByteArray>? = runCatching {
        val o = JSON.parseToJsonElement(plain.decodeToString()) as? kotlinx.serialization.json.JsonObject ?: return null
        fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        if (num("version") != 2L) return null
        val count = num("count")?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
        val digest = (o["digest"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let { unb64(it) }?.takeIf { it.size == 32 } ?: return null
        count.toInt() to digest
    }.getOrNull()

    // ---- scope succession ----

    /** The succession key pair an epoch's 32-byte scope key derives: (public key, seed). */
    fun successionKeyPair(scopeKey: ByteArray): Pair<ByteArray, ByteArray> {
        require(scopeKey.size == 32) { "scope key: not 32 bytes" }
        val seed = p.hkdfSha256(scopeKey, ByteArray(0), utf8("veyq/v1/scope-succession"), 32)
        return p.ed25519Public(seed) to seed
    }

    fun scopeSuccessionStatement(scopeId: String, epoch: Long, successionPublicKey: ByteArray): ByteArray {
        if (epoch < 1) throw SealContextInvalid("scope-succession epoch is not 1 or more")
        if (successionPublicKey.size != 32) throw SealContextInvalid("scope-succession successionPublicKey is not 32 bytes")
        return encodeContext(Purposes.STATEMENT, Context("scope-succession", "scopeId" to scopeId, "epoch" to epoch, "successionPublicKey" to successionPublicKey))
    }

    fun verifySuccession(previousPublicKey: ByteArray, scopeId: String, epoch: Long, successionPublicKey: ByteArray, signature: ByteArray): Boolean =
        runCatching { p.ed25519Verify(previousPublicKey, scopeSuccessionStatement(scopeId, epoch, successionPublicKey), signature) }.getOrDefault(false)

    class SuccessionLink(val epoch: Long, val successionPublicKey: ByteArray, val successionSignature: ByteArray)

    /**
     * Whether [statements] prove [targetPublicKey] as epoch [targetEpoch]'s succession key to a holder of [heldEpoch]
     * whose key derives [heldPublicKey]: exactly one statement per epoch `heldEpoch + 1 … targetEpoch`, each verifying
     * under the key before it, the last key byte-equal to the target. The port of `verifySuccessionPath`.
     */
    fun verifySuccessionPath(
        scopeId: String, heldEpoch: Long, heldPublicKey: ByteArray, statements: List<SuccessionLink>, targetEpoch: Long, targetPublicKey: ByteArray,
    ): Boolean {
        if (heldEpoch < 0 || targetEpoch < heldEpoch || heldPublicKey.size != 32) return false
        val byEpoch = HashMap<Long, SuccessionLink>()
        for (s in statements) {
            if (s.epoch < 0) return false
            if (s.epoch <= heldEpoch || s.epoch > targetEpoch) continue
            if (byEpoch.containsKey(s.epoch)) return false
            byEpoch[s.epoch] = s
        }
        var key = heldPublicKey
        for (epoch in heldEpoch + 1..targetEpoch) {
            val s = byEpoch[epoch] ?: return false
            if (!verifySuccession(key, scopeId, epoch, s.successionPublicKey, s.successionSignature)) return false
            key = s.successionPublicKey
        }
        return key.contentEquals(targetPublicKey)
    }

    companion object {
        /** Every entry's plaintext is padded to this many bytes (`v1.json#identity_pin.entry_bytes`). */
        const val PIN_ENTRY_BYTES = 192
        private const val MAX_SAFE = 9007199254740991L
        private val JSON = kotlinx.serialization.json.Json
        private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val B64_32 = Regex("^[A-Za-z0-9+/]{43}=$")

        private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}
