package me.raddatz.veyq.core.identity

import me.raddatz.veyq.core.identity.keychain.KeyChainStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.util.UUID

/** One Master Key wrap to persist with a new account (opaque ciphertext + KDF params). */
data class WrapData(val method: String, val wrappedKey: String, val kdfParams: String?)

/**
 * Creates operator-blind user accounts — the **one** place a `veyq_user` + its Master Key wraps are written,
 * so the invariant (only ciphertext + public keys + metadata, never a password or content key) is audited in
 * a single spot. Used by first-run setup (the Server Owner) and by invite-gated registration (a tenant owner,
 * cr-0010). The setup single-owner lock is *not* this store's concern — its caller writes it.
 */
@ApplicationScoped
class AccountStore(private val keyChain: KeyChainStore) {

    /** The account under [email], or null. The **only** user-by-email lookup (`validate_lookup_single_home.py`
     *  rule C), and it normalizes for itself ([normalizeEmail]) so no caller can look up a different spelling of
     *  the address than the one [createAccount] stored. */
    fun findByEmail(email: String, lock: Boolean = false): VeyqUser? =
        VeyqUser.find("email", normalizeEmail(email))
            .let { if (lock) it.withLock(LockModeType.PESSIMISTIC_WRITE) else it }.firstResult()

    /**
     * The account row, locked for the rest of the caller's transaction: **the serializing parent of an account's
     * credentials** (developer-styleguide §12, inbox #297). A password reset, a password change and a key rotation
     * each end what the account's other devices hold — they revoke its session families, delete its device wraps,
     * supersede its pairing channels — and each finds those rows by a selection that cannot see one another
     * transaction has not committed. So every one of them, and every writer that adds such a row (a sign-in, a new
     * device wrap, a pairing delegation), takes this lock first, and a writer re-reads under it what the cleanup
     * changes: the auth key it verifies against, the session it acts under. A sign-in by email locks the same row
     * through [findByEmail]. A sign-out everywhere else ends the other devices' sessions too, and takes it as well
     * (`SessionService.revokeOtherFamilies`, inbox #316).
     *
     * The lock is the first read of the account in its transaction: a lock on an entity this transaction already
     * loaded hands back the loaded copy, with the values from before the wait.
     */
    fun lock(userId: String): VeyqUser? = VeyqUser.findById(userId, LockModeType.PESSIMISTIC_WRITE)

    /**
     * The Server Owner's account id, or null on an instance not set up. One per instance: first-run setup makes it and
     * nothing moves the role yet (inbox #43); the order only makes a world with several — a test's — answer the same
     * every time. Ids alone, no entity loaded, so a caller can still take [lock] as the account's first read.
     */
    fun serverOwnerId(): String? =
        VeyqUser.getEntityManager()
            .createQuery("select u.id from VeyqUser u where u.serverRole = :role order by u.id", String::class.java)
            .setParameter("role", ServerRoles.SERVER_OWNER).setMaxResults(1).resultList.firstOrNull()

    /** Persist a new account (identity + wraps) with the given server role, and step 0 of its key chain — the identity
     *  and Secret-Key proof key it is born with (ADR 0111 decision 7) — in the same transaction. Returns the new user id. */
    @Transactional
    fun createAccount(
        email: String,
        serverRole: String,
        suiteId: String,
        identityPublicKey: String,
        wrappedIdentityPrivateKey: String,
        authPublicKey: String,
        secretKeyProofPublicKey: String,
        wraps: List<WrapData>,
        /** A client-minted id (register-and-accept: the sealed real name is bound to it, ADR 0072), else a fresh one. */
        userId: String = UUID.randomUUID().toString(),
    ): String {
        VeyqUser().apply {
            id = userId
            this.email = normalizeEmail(email)
            this.serverRole = serverRole
            this.suiteId = suiteId
            this.identityPublicKey = identityPublicKey
            this.wrappedIdentityPrivateKey = wrappedIdentityPrivateKey
            this.authPublicKey = authPublicKey
            this.secretKeyProofPublicKey = secretKeyProofPublicKey
        }.persist()

        wraps.forEach { wrap ->
            MasterKeyWrapEntity().apply {
                id = UUID.randomUUID().toString()
                this.userId = userId
                method = wrap.method
                wrappedKey = wrap.wrappedKey
                kdfParams = wrap.kdfParams
            }.persist()
        }
        keyChain.writeGenesis(userId, identityPublicKey, secretKeyProofPublicKey)
        return userId
    }
}
