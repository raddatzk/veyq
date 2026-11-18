package me.raddatz.veyq.mobile.crypto

/**
 * A verification (ADR 0111 decisions 12–14), ported from `src/packages/crypto/src/verification.ts`: the verifier's
 * identity signs that, in this tenant, the verifier at this head of their own key chain confirmed the subject at this
 * head of theirs; the row is sealed under a value key of its own whose wrap the tenant key holds (`member-verification`,
 * ADR 0115). Pinned by `v1.json#verification` and `#field_seal['member-verification']`. Every check here reads bytes a
 * server served, so none of them throws.
 */
class Verifications(private val suite: V1) {
    private val p = suite.p

    /** Whose verification of whom, in which tenant. */
    data class Address(val tenantId: String, val verifierUserId: String, val subjectUserId: String)

    /** The two heads and the verifier's signature, as stored inside the seal. */
    class Verification(val subjectHead: ByteArray, val verifierHead: ByteArray, val signature: ByteArray)

    fun statement(a: Address, subjectHead: ByteArray, verifierHead: ByteArray): ByteArray =
        encodeContext(
            Purposes.STATEMENT,
            Context(
                "verification",
                "tenantId" to a.tenantId, "verifierUserId" to a.verifierUserId, "subjectUserId" to a.subjectUserId,
                "subjectHead" to subjectHead, "verifierHead" to verifierHead,
            ),
        )

    /** The verifier's identity signs the statement; a verification of oneself is refused. */
    fun sign(a: Address, subjectHead: ByteArray, verifierHead: ByteArray, verifierSignSeed: ByteArray): Verification {
        if (a.verifierUserId == a.subjectUserId) throw SealContextInvalid("verification of oneself")
        return Verification(subjectHead, verifierHead, p.ed25519Sign(verifierSignSeed, statement(a, subjectHead, verifierHead)))
    }

    /** Whether [v] is the signature of [verifierSignPublicKey] over this address and its heads; false, never a throw. */
    fun verifies(a: Address, v: Verification, verifierSignPublicKey: ByteArray): Boolean = runCatching {
        a.verifierUserId != a.subjectUserId && p.ed25519Verify(verifierSignPublicKey, statement(a, v.subjectHead, v.verifierHead), v.signature)
    }.getOrDefault(false)

    /** `0x01 ‖ lp(subjectHead) ‖ lp(verifierHead) ‖ lp(signature)` — fixed sizes 32/32/64. */
    fun encode(v: Verification): ByteArray {
        val parts = listOf(v.subjectHead, v.verifierHead, v.signature)
        parts.forEachIndexed { i, b -> if (b.size != SIZES[i]) throw SealContextInvalid("verification size") }
        return concat(byteArrayOf(VERSION), *parts.map(::lp).toTypedArray())
    }

    /** Null for anything but a well-formed record. */
    fun decode(b: ByteArray): Verification? {
        if (b.isEmpty() || b[0] != VERSION) return null
        var off = 1
        val out = mutableListOf<ByteArray>()
        for (size in SIZES) {
            if (off + 4 > b.size || readU32be(b, off) != size || off + 4 + size > b.size) return null
            out += b.copyOfRange(off + 4, off + 4 + size)
            off += 4 + size
        }
        return if (off == b.size) Verification(out[0], out[1], out[2]) else null
    }

    /** The row's own field-seal context at the tenant epoch it is written at. */
    fun context(a: Address, epoch: Long) =
        Context("member-verification", "scopeId" to a.tenantId, "userId" to a.verifierUserId, "subjectUserId" to a.subjectUserId, "epoch" to epoch)

    private companion object {
        const val VERSION: Byte = 0x01
        val SIZES = intArrayOf(32, 32, 64)
    }
}
