package me.raddatz.veyq.memories.favorites

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * Persistence for per-(user, scope) favorites (cr-0031). Operator-blind: stores the sealed ciphertext only.
 * Who may act on a scope is decided before this store is reached, by the handler's `ScopeAccess` gate.
 */
@ApplicationScoped
class FavoritesStore {

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
