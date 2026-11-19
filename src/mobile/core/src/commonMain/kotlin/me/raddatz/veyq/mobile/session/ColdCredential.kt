package me.raddatz.veyq.mobile.session

import me.raddatz.veyq.mobile.api.models.AccountKeyMaterial
import me.raddatz.veyq.mobile.api.models.MasterKeyWrap
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * The cold-credential proof (ADR 0060 decision 2, ADR 0148 decision 6), a port of the web's `coldCredential.ts`
 * (`proveColdCredential`, `checkColdCredential`, `staleAsRejected`): **a live proof, not a memory.** Every flow that
 * mints a cold credential or moves the address a reset goes to — the password change, the address change, the rotation —
 * and the kit made after an address change runs this first: the stored `PASSWORD` wrap, read now, is opened with the
 * password and the Secret Key typed now, and must give the Master Key this account holds. A device that holds the
 * Master Key through its own wrap but not the Secret Key fails here, whatever flow it reaches it from.
 *
 * Nothing here writes. The Secret Key bytes are the caller's and are zeroed by the caller; every key derived here is
 * zeroed before this returns.
 */
object ColdCredential {
    /** Why the proof refused — the web's `ColdCredentialRefusal`. Nothing was written on any of them. */
    enum class Refusal {
        /** The password and Secret Key offered do not open this account's `PASSWORD` floor. */
        REJECTED,

        /** The account has no `PASSWORD` floor to prove against. */
        NO_FLOOR,

        /** The account is closed: no Master Key to prove the credential against. */
        LOCKED,

        /** The credential opens a floor whose Master Key is not this account's but opens the identity the server serves:
         *  the account was secured since this account opened (a rotation elsewhere). The rotation resumes from it; the
         *  password and address changes read it as [REJECTED] ([staleAsRejected]). */
        STALE,
    }

    class Refused(val reason: Refusal) : Exception("cold credential refused: $reason")

    /** The stored floor, opened: the Master Key it holds and the wrapped identity served beside it. */
    class Floor(val masterKey: ByteArray, val wrappedIdentityPrivateKey: String)

    /**
     * Opens the stored `PASSWORD` floor of [material] (read by the caller just now) with [password] and [secretKey]; the
     * caller owns and zeroes the returned Master Key. [Refusal.NO_FLOOR] without a floor, [Refusal.REJECTED] where the
     * wrap does not open. A KDF profile this client does not write throws as it is (the server's fault, not the
     * credential's: "nothing was changed, try again").
     */
    fun openFloor(suite: V1, material: AccountKeyMaterial, password: String, secretKey: ByteArray): Floor {
        val floor = material.wraps.firstOrNull { it.method == MasterKeyWrap.Method.PASSWORD }
        val params = floor?.kdfParams ?: throw Refused(Refusal.NO_FLOOR)
        val (argon, salt) = suite.decodeKdfParams(unb64(params))
        val kek = suite.kekPasswordSecret(secretKey, password, salt, argon)
        try {
            val mk = runCatching { suite.open(Context("master-key"), kek, unb64(floor.wrappedKey)) }.getOrNull()
                ?: throw Refused(Refusal.REJECTED)
            return Floor(mk, material.wrappedIdentityPrivateKey)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * `proveColdCredential`: [held] is a copy of the account's Master Key (zeroed here on every path but success, where
     * the caller gets it back to own and zero). A floor that opens to another key is [Refusal.STALE] where that key
     * opens the served identity, else [Refusal.REJECTED].
     */
    fun prove(suite: V1, held: ByteArray?, material: AccountKeyMaterial, password: String, secretKey: ByteArray): ByteArray {
        if (held == null) throw Refused(Refusal.LOCKED)
        val floor = try {
            openFloor(suite, material, password, secretKey)
        } catch (e: Throwable) {
            held.fill(0)
            throw e
        }
        val opened = floor.masterKey
        try {
            if (opened.contentEquals(held)) return held
            val stale = opensIdentity(suite, opened, floor.wrappedIdentityPrivateKey)
            held.fill(0)
            throw Refused(if (stale) Refusal.STALE else Refusal.REJECTED)
        } finally {
            opened.fill(0)
        }
    }

    /** Whether [mk] opens [wrapped] to an identity that decodes; nothing of it is kept. */
    fun opensIdentity(suite: V1, mk: ByteArray, wrapped: String): Boolean = runCatching {
        val priv = suite.deserializeIdentityPrivate(suite.open(Context("identity-priv"), mk, unb64(wrapped)))
        priv.kemSeed.fill(0)
        priv.signSeed.fill(0)
        true
    }.getOrDefault(false)

    /** The refusal as the flows that never finish a rotation read it: [Refusal.STALE] is [Refusal.REJECTED]. */
    fun staleAsRejected(e: Refused): Refused = if (e.reason == Refusal.STALE) Refused(Refusal.REJECTED) else e
}

/** A write's answer, as a flow tells statuses apart: its status and the error body's `code`. */
data class FlowReply(val status: Int, val code: String? = null) {
    val ok: Boolean get() = status in 200..299
}
