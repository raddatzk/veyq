package com.veyq.app.shared.identity

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant
import java.util.UUID

/** A member's per-tenant identity (username + sealed real name), for the directory + profile reads. */
data class ProfileView(val userId: String, val username: String, val sealedName: String?)

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

    /** Create the member's tenant profile (username set now; the sealed name is written later via the profile op). */
    @Transactional
    fun create(userId: String, tenantId: String, username: String, sealedName: String? = null): String {
        val id = UUID.randomUUID().toString()
        TenantProfileEntity().apply {
            this.id = id
            this.userId = userId
            this.tenantId = tenantId
            this.username = norm(username)
            this.sealedName = sealedName
            createdAt = Instant.now()
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

    /** The identity (username + sealed name) for every member of a tenant, keyed by userId — for the directory. */
    fun byUserInTenant(tenantId: String): Map<String, ProfileView> =
        TenantProfileEntity.find("tenantId", tenantId).list()
            .associate { it.userId to ProfileView(it.userId, it.username, it.sealedName) }
}
