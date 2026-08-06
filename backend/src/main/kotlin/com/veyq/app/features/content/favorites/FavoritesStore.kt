package com.veyq.app.features.content.favorites

import com.veyq.app.shared.membership.MembershipStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Persistence for per-(user, scope) favorites (cr-0031). Operator-blind: stores the sealed ciphertext only.
 * Scope access = the caller holds a membership for the scope (same gate as the Memory tree, cr-0026/0027).
 */
@ApplicationScoped
class FavoritesStore(private val membership: MembershipStore) {

    fun isMember(userId: String, scopeId: String): Boolean = membership.isScopeMember(userId, scopeId)

    /** The caller's sealed favorites blob for the scope, or null if none was ever stored. */
    fun get(userId: String, scopeId: String): String? =
        FavoritesEntity.findById(FavoritesEntity.key(userId, scopeId))?.ciphertext

    /** Last-write-wins upsert of the caller's sealed favorites blob. */
    @Transactional
    fun upsert(userId: String, scopeId: String, ciphertext: String) {
        val id = FavoritesEntity.key(userId, scopeId)
        val row = FavoritesEntity.findById(id) ?: FavoritesEntity().apply {
            this.id = id
            this.userId = userId
            this.scopeId = scopeId
        }
        row.ciphertext = ciphertext
        row.updatedAt = Instant.now()
        row.persist()
    }
}
