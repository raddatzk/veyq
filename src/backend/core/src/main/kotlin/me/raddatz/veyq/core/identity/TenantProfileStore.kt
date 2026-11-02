package me.raddatz.veyq.core.identity

import me.raddatz.veyq.core.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

/**
 * A member's per-tenant identity (username + sealed real name + the picture's revision), for the directory + profile
 * reads. **Never the picture's bytes** (ADR 0084): [avatarRevision] is `0` when there is no picture, else the
 * profile's stored counter.
 */
data class ProfileView(
    val userId: String, val username: String, val sealedName: String?, val avatarRevision: Int,
    /** The member's sealed join record (ADR 0111 decisions 3 and 12), null for the tenant's first owner. */
    val sealedJoinRecord: String? = null,
    /** The inviting owner's endorsement of that record (ADR 0111 decision 21), or null. */
    val joinEndorsement: String? = null,
)

/** A join record an owner rooted (ADR 0111 decision 21): the member, the sealed record as
 *  stored, and the endorsement it carries now. */
data class RootedJoinRecord(val tenantId: String, val userId: String, val sealedJoinRecord: String, val joinEndorsement: String?)

/**
 * Persistence for the per-`(user, tenant)` identity profile (cr-0083, ADR 0023). Operator-blind: `username` is a
 * plaintext handle (unique within the tenant), `sealedName` is ciphertext the server stores verbatim. Usernames
 * are normalised (trim + lowercase) so uniqueness is case-insensitive.
 */
@ApplicationScoped
class TenantProfileStore {

    private fun norm(username: String) = username.trim().lowercase()

    /** Whether a username is already taken in this tenant (case-insensitive). */
    fun usernameTaken(tenantId: String, username: String): Boolean =
        TenantProfileEntity.count("tenantId = ?1 and username = ?2", tenantId, norm(username)) > 0

    /** The caller's profile in a tenant, or null if they have none there. */
    fun find(userId: String, tenantId: String): TenantProfileEntity? =
        TenantProfileEntity.find("userId = ?1 and tenantId = ?2", userId, tenantId).firstResult()

    /** Resolve a per-tenant username (case-insensitive) to its userId, or null if no member holds it — the
     *  tenant-scoped resolve behind group member-add (cr-0086, ADR 0020: never a global user-search). */
    fun findUserIdByUsername(tenantId: String, username: String): String? =
        TenantProfileEntity.find("tenantId = ?1 and username = ?2", tenantId, norm(username)).firstResult()?.userId

    /**
     * Create the member's tenant profile at the join: the username, and what the joiner sealed under `K_tenant`, which
     * every joiner holds at accept (the owner's cold birth, a member's self-grant, ADR 0111 decision 3) — the optional
     * name and picture (ADR 0084 decision 7: stored with revision 1, so a directory read at once shows it) and a
     * member's join record (null for the tenant's first owner), with [joinSignerUserId] — the invite's signer, who rooted
     * it (ADR 0111 decision 21).
     */
    @Transactional
    fun create(
        userId: String, tenantId: String, username: String, sealedName: String? = null, sealedAvatar: String? = null,
        sealedJoinRecord: String? = null, joinSignerUserId: String? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        TenantProfileEntity().apply {
            this.id = id
            this.userId = userId
            this.tenantId = tenantId
            this.username = norm(username)
            this.sealedName = sealedName
            this.sealedAvatar = sealedAvatar
            this.avatarRevision = if (sealedAvatar != null) 1 else 0
            this.sealedJoinRecord = sealedJoinRecord
            this.joinSignerUserId = joinSignerUserId
        }.persist()
        return id
    }

    /** A returning member's new join record (ADR 0111 decision 3): their profile outlived a removal, and the invite
     *  that brought them back is a new join — rooted by its signer [joinSignerUserId], and endorsed by nobody yet: an
     *  endorsement covers the record it was made over, never this one (ADR 0111 decision 21).
     *  The only writer of the record besides [create]; the profile op never is. */
    @Transactional
    fun setJoinRecord(userId: String, tenantId: String, sealedJoinRecord: String, joinSignerUserId: String) {
        TenantProfileEntity.update(
            "sealedJoinRecord = ?1, joinSignerUserId = ?2, joinEndorsement = null where userId = ?3 and tenantId = ?4",
            sealedJoinRecord, joinSignerUserId, userId, tenantId,
        )
    }

    /** Every join record [signerUserId] rooted, in **every** tenant (ADR 0111 decision 21) — [tenantIds], when given, narrows them
     *  to those tenants — by tenant, then member. */
    fun rootedBy(signerUserId: String, tenantIds: Collection<String>? = null): List<RootedJoinRecord> {
        val rows = if (tenantIds == null) {
            TenantProfileEntity.find("joinSignerUserId = ?1 and sealedJoinRecord is not null", signerUserId).list()
        } else {
            if (tenantIds.isEmpty()) return emptyList()
            TenantProfileEntity.find("joinSignerUserId = ?1 and sealedJoinRecord is not null and tenantId in ?2", signerUserId, tenantIds).list()
        }
        return rows.mapNotNull { RootedJoinRecord(it.tenantId, it.userId, it.sealedJoinRecord ?: return@mapNotNull null, it.joinEndorsement) }
            .sortedWith(compareBy({ it.tenantId }, { it.userId }))
    }

    /** The tenants in which [signerUserId] rooted a join record — read before the publish takes their scopes' locks. */
    fun tenantsRootedBy(signerUserId: String): Set<String> =
        TenantProfileEntity.getEntityManager().createQuery(
            "select distinct p.tenantId from TenantProfileEntity p where p.joinSignerUserId = :signer and p.sealedJoinRecord is not null",
            String::class.java,
        ).setParameter("signer", signerUserId).resultList.toSet()

    /** Write [signerUserId]'s endorsement of [userId]'s join record in [tenantId] — or clear it, for a declared record
     *  ([endorsement] null) — in the publish's transaction, under the tenant scope's lock (ADR 0111 decision 21). */
    fun setJoinEndorsement(tenantId: String, userId: String, signerUserId: String, endorsement: String?) {
        if (endorsement == null) {
            TenantProfileEntity.update(
                "joinEndorsement = null where tenantId = ?1 and userId = ?2 and joinSignerUserId = ?3", tenantId, userId, signerUserId,
            )
        } else {
            TenantProfileEntity.update(
                "joinEndorsement = ?1 where tenantId = ?2 and userId = ?3 and joinSignerUserId = ?4", endorsement, tenantId, userId, signerUserId,
            )
        }
    }

    /**
     * Whether [epoch] is the tenant scope's current epoch, under that scope's row lock taken here for the caller's
     * transaction (ADR 0115 decision 3): a field sealed under the tenant key lands only at the epoch the rotation's
     * commit, which holds the same lock, re-seals every profile to. The lock is the tenant scope's, the first a writer
     * of these rows takes.
     */
    fun atCurrentEpoch(tenantId: String, epoch: Int): Boolean = ScopeEntity.lockRow(tenantId)?.currentEpoch == epoch

    /** Update the caller's profile in a tenant — change the username and/or the sealed name (each optional). */
    @Transactional
    fun update(userId: String, tenantId: String, username: String?, sealedName: String?) {
        val profile = find(userId, tenantId) ?: return
        if (username != null) profile.username = norm(username)
        if (sealedName != null) profile.sealedName = sealedName
        profile.persist()
    }

    /**
     * Store (or overwrite) the member's picture and bump the revision, one guarded UPDATE: the counter moves in the
     * database, so two overlapping sets cannot both write the same next revision over different pictures. Returns
     * whether the member has a profile in the tenant.
     */
    @Transactional
    fun setAvatar(userId: String, tenantId: String, sealedAvatar: String, epoch: Int): AvatarSet {
        // Sealed under the tenant key at [epoch]: only while it is the current one, under the tenant scope's lock.
        if (!atCurrentEpoch(tenantId, epoch)) return AvatarSet.STALE_EPOCH
        val set = TenantProfileEntity.update(
            "sealedAvatar = ?1, avatarRevision = avatarRevision + 1 where userId = ?2 and tenantId = ?3",
            sealedAvatar, userId, tenantId,
        ) > 0
        return if (set) AvatarSet.SET else AvatarSet.NO_PROFILE
    }

    /** What [setAvatar] did. */
    enum class AvatarSet { SET, NO_PROFILE, STALE_EPOCH }

    /**
     * Remove the member's picture and bump the revision (a client holding the old one revalidates and finds none).
     * Idempotent; the username and the sealed name stay. Also the removal cascade's step (ADR 0084 decision 8), so a
     * removed member's picture is gone while the profile row that old Memories resolve a handle from outlives it.
     */
    @Transactional
    fun clearAvatar(userId: String, tenantId: String): Boolean =
        TenantProfileEntity.update(
            "sealedAvatar = null, avatarRevision = avatarRevision + 1 where userId = ?1 and tenantId = ?2",
            userId, tenantId,
        ) > 0

    /**
     * The identity (username + sealed name + picture revision + sealed join record and its endorsement) for every member of a tenant, keyed
     * by userId — for the directory. A projection, so a picture's ~11 KB is never read into the directory (ADR 0084): the database
     * answers `0` for a null picture and the counter otherwise.
     */
    fun byUserInTenant(tenantId: String): Map<String, ProfileView> =
        TenantProfileEntity.getEntityManager().createQuery(
            "select p.userId, p.username, p.sealedName, " +
                "case when p.sealedAvatar is null then 0 else p.avatarRevision end, p.sealedJoinRecord, p.joinEndorsement " +
                "from TenantProfileEntity p where p.tenantId = ?1",
            Array<Any?>::class.java,
        ).setParameter(1, tenantId).resultList
            .associate { r ->
                r[0] as String to ProfileView(r[0] as String, r[1] as String, r[2] as String?, (r[3] as Number).toInt(), r[4] as String?, r[5] as String?)
            }
}
