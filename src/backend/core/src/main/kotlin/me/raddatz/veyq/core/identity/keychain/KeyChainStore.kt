package me.raddatz.veyq.core.identity.keychain

import me.raddatz.veyq.core.crypto.KeyChainStatements
import me.raddatz.veyq.core.identity.ModeratorEntity
import me.raddatz.veyq.core.identity.ServerRoles
import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** One step as the chain read serves it. [previousHead] is null exactly at step 0, and so are both signatures. */
data class KeyChainLink(
    val index: Int,
    val previousHead: String?,
    val identityPublicKey: String,
    val secretKeyProofPublicKey: String,
    val identitySignature: String?,
    val proofSignature: String?,
    val head: String,
    val createdAt: Instant,
)

/**
 * Persistence of an account's key chain (ADR 0111 decision 7, cr-secure-the-account) — its only writer.
 *
 * **Every account has step 0.** The one account-birth writer (`AccountStore.createAccount`) writes it in the birth's
 * transaction. An account row without a stored step — none in production; a test fixture's seeded account — reads as
 * the genesis its row describes ([genesisOf]), which is exact for an account that never rotated: a rotation writes the
 * steps it appends, step 0 included when it was not stored, so no rotated account is ever read from its row.
 */
@ApplicationScoped
class KeyChainStore {

    /** Write step 0 of a newborn account, in the caller's (birth) transaction. */
    fun writeGenesis(userId: String, identityPublicKey: String, secretKeyProofPublicKey: String, at: Instant = Instant.now()) {
        val statement = requireNotNull(KeyChainStatements.genesis(userId, identityPublicKey, secretKeyProofPublicKey)) {
            "a birth's keys are bean-validated base64 and its id a canonical UUID"
        }
        persist(userId, 0, identityPublicKey, secretKeyProofPublicKey, null, null, b64(KeyChainStatements.head(statement)), at)
    }

    /** The whole chain of [userId], genesis first — or null for an unknown account. */
    fun chainOf(userId: String): List<KeyChainLink>? {
        val stored = KeyChainStepEntity.find("userId = ?1 order by idx", userId).list()
        if (stored.isNotEmpty()) {
            return stored.mapIndexed { i, s ->
                KeyChainLink(
                    s.idx, if (i == 0) null else stored[i - 1].head, s.identityPublicKey, s.secretKeyProofPublicKey,
                    s.identitySignature, s.proofSignature, s.head, s.createdAt,
                )
            }
        }
        val user = VeyqUser.findById(userId) ?: return null
        return listOf(genesisOf(user))
    }

    /** The head of every account in [userIds] that exists, base64 — one query for the stored ones. */
    fun heads(userIds: Collection<String>): Map<String, String> {
        val ids = userIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        val stored = KeyChainStepEntity.getEntityManager().createQuery(
            "select s.userId, s.head from KeyChainStepEntity s where s.userId in :ids and s.idx = " +
                "(select max(t.idx) from KeyChainStepEntity t where t.userId = s.userId)",
            Array<Any>::class.java,
        ).setParameter("ids", ids).resultList.associate { it[0] as String to it[1] as String }
        val missing = ids - stored.keys
        if (missing.isEmpty()) return stored
        return stored + VeyqUser.find("id in ?1", missing).list().associate { it.id to genesisOf(it).head }
    }

    /** The head of [userId], or null for an unknown account. */
    fun headOf(userId: String): String? = heads(listOf(userId))[userId]

    /**
     * Append step [index] (the stored newest + 1), installing [identityPublicKey] and [secretKeyProofPublicKey] under
     * the two signatures of the keys it retires. [genesis] is the step 0 the chain was read with: written first when
     * it was only derived from the row ([chainOf]). In the caller's (the publish's) transaction, under the account lock.
     */
    fun append(
        userId: String, genesis: KeyChainLink, index: Int, identityPublicKey: String, secretKeyProofPublicKey: String,
        identitySignature: String, proofSignature: String, head: ByteArray, at: Instant,
    ) {
        if (KeyChainStepEntity.count("userId = ?1 and idx = 0", userId) == 0L) {
            persist(userId, 0, genesis.identityPublicKey, genesis.secretKeyProofPublicKey, null, null, genesis.head, genesis.createdAt)
        }
        persist(userId, index, identityPublicKey, secretKeyProofPublicKey, identitySignature, proofSignature, b64(head), at)
    }

    /**
     * Whether [callerId] may read [userId]'s chain (ADR 0111 decision 7): the person themselves, someone who shares
     * a tenant with them — a membership of each on the same tenant root scope (`scopeId == tenantId`) — or, when both
     * are accepted moderators of the instance, each other (ADR 0099: a holder anchors the moderator it delivers the
     * moderation key to before it seals to them, and the two may share no tenant; the roster is the instance's, so
     * the moderators already know each other) — and the Server Owner's to an accepted moderator, on the roster or not
     * (ADR 0101 decision 9: the last holder's erasure delivers the key to the Server Owner and anchors them first; the
     * roster is the Server Owner's, so every moderator knows them). Its status does not matter: a suspended tenant does
     * not hide public keys the person's other tenants serve too.
     */
    fun mayRead(callerId: String, userId: String): Boolean {
        if (callerId == userId) return true
        if (ModeratorEntity.isModerator(callerId) &&
            (ModeratorEntity.isModerator(userId) || VeyqUser.findById(userId)?.serverRole == ServerRoles.SERVER_OWNER)
        ) return true
        return MembershipEntity.getEntityManager().createQuery(
            "select count(a) from MembershipEntity a, MembershipEntity b where a.userId = :caller and b.userId = :target " +
                "and a.scopeId = a.tenantId and b.scopeId = a.scopeId and a.pendingConsent = false and b.pendingConsent = false",
            Long::class.javaObjectType,
        ).setParameter("caller", callerId).setParameter("target", userId).singleResult > 0
    }

    private fun genesisOf(user: VeyqUser): KeyChainLink {
        // An account without a proof key (none can be born so) reads with an empty one: a chain every reader refuses as
        // malformed, which is the right answer for an account that can prove nothing.
        val proofKey = user.secretKeyProofPublicKey ?: ""
        val statement = KeyChainStatements.genesis(user.id, user.identityPublicKey, proofKey)
        // A row whose keys are no base64 (none is written so) makes no statement; its head is then the empty digest's,
        // never a crash of every directory read that serves the account.
        val head = KeyChainStatements.head(statement ?: ByteArray(0))
        return KeyChainLink(0, null, user.identityPublicKey, proofKey, null, null, b64(head), EPOCH)
    }

    private fun persist(
        userId: String, index: Int, identityPublicKey: String, secretKeyProofPublicKey: String,
        identitySignature: String?, proofSignature: String?, head: String, at: Instant,
    ) {
        KeyChainStepEntity().apply {
            id = UUID.randomUUID().toString()
            this.userId = userId
            this.idx = index
            this.identityPublicKey = identityPublicKey
            this.secretKeyProofPublicKey = secretKeyProofPublicKey
            this.identitySignature = identitySignature
            this.proofSignature = proofSignature
            this.head = head
            createdAt = at
        }.persist()
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private companion object {
        /** The served time of a step 0 derived from the row: the account's birth is not stored (inbox #318), so the
         *  derived genesis carries the epoch rather than a time it would invent. */
        val EPOCH: Instant = Instant.EPOCH
    }
}
