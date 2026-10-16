package me.raddatz.veyq.core.identity

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.UUID

/**
 * A member's per-tenant identity (username + sealed real name + the picture's revision), for the directory + profile
 * reads. **Never the picture's bytes** (ADR 0084): [avatarRevision] is `0` when there is no picture, else the
 * profile's stored counter.
 */
data class ProfileView(val userId: String, val username: String, val sealedName: String?, val avatarRevision: Int)

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
     * Create the member's tenant profile (username set now; the sealed name is written later via the profile op).
     * [sealedAvatar] rides only the OWNER join, where the invitee holds `K_tenant` at accept (ADR 0084 decision 7):
     * it is stored with revision 1, so a directory read at once shows it.
     */
    @Transactional
    fun create(userId: String, tenantId: String, username: String, sealedName: String? = null, sealedAvatar: String? = null): String {
        val id = UUID.randomUUID().toString()
        TenantProfileEntity().apply {
            this.id = id
            this.userId = userId
            this.tenantId = tenantId
            this.username = norm(username)
            this.sealedName = sealedName
            this.sealedAvatar = sealedAvatar
            this.avatarRevision = if (sealedAvatar != null) 1 else 0
        }.persist()
        return id
    }

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
    fun setAvatar(userId: String, tenantId: String, sealedAvatar: String): Boolean =
        TenantProfileEntity.update(
            "sealedAvatar = ?1, avatarRevision = avatarRevision + 1 where userId = ?2 and tenantId = ?3",
            sealedAvatar, userId, tenantId,
        ) > 0

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
     * The identity (username + sealed name + picture revision) for every member of a tenant, keyed by userId — for the
     * directory. A projection, so a picture's ~11 KB is never read into the directory (ADR 0084): the database
     * answers `0` for a null picture and the counter otherwise.
     */
    fun byUserInTenant(tenantId: String): Map<String, ProfileView> =
        TenantProfileEntity.getEntityManager().createQuery(
            "select p.userId, p.username, p.sealedName, " +
                "case when p.sealedAvatar is null then 0 else p.avatarRevision end " +
                "from TenantProfileEntity p where p.tenantId = ?1",
            Array<Any?>::class.java,
        ).setParameter(1, tenantId).resultList
            .associate { r -> r[0] as String to ProfileView(r[0] as String, r[1] as String, r[2] as String?, (r[3] as Number).toInt()) }
}
