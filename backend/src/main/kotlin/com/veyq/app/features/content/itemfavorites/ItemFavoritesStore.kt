package com.veyq.app.features.content.itemfavorites

import com.veyq.app.shared.scope.ScopeEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/**
 * One member's sealed item-mark blob for a node, as the read hands it out. [own] is the only identity that
 * crosses the wire (ADR 0068 §6): the array already separates the members and an item's count is how many
 * entries hold it, so a member id would be data the product has decided never to show.
 */
data class MemberItemFavorites(val own: Boolean, val ciphertext: String)

/**
 * Persistence for per-(member, scope, node) item favorites (ADR 0068). Operator-blind: stores the sealed
 * ciphertext only. Who may act on a scope is decided by the handler's `ScopeAccess` gate.
 */
@ApplicationScoped
class ItemFavoritesStore {

    /**
     * **Every** member's sealed blob for the node — not just the caller's. That is the point of this resource:
     * the aggregate the gallery renders is computed on device, so the client needs the per-member blobs.
     */
    fun listForNode(callerId: String, scopeId: String, nodeId: String): List<MemberItemFavorites> =
        ItemFavoritesEntity.list("scopeId = ?1 and nodeId = ?2", scopeId, nodeId)
            .map { MemberItemFavorites(own = it.userId == callerId, ciphertext = it.ciphertext) }

    /**
     * The caller's **own** rows, every scope and node, unfiltered by access: which of them the caller may still see
     * is the handler's question, because a row can outlive the membership it was written under.
     */
    fun listOwn(userId: String): List<ItemFavoritesEntity> = ItemFavoritesEntity.list("userId = ?1", userId)

    /** The scope row's lock, which every removal of a member holds while it deletes that member's rows. */
    fun lockScope(scopeId: String) {
        ScopeEntity.lockRow(scopeId)
    }

    /** Last-write-wins upsert of the **caller's own** blob for the node. */
    @Transactional
    fun upsert(userId: String, scopeId: String, nodeId: String, ciphertext: String) {
        val id = ItemFavoritesEntity.key(userId, scopeId, nodeId)
        val row = ItemFavoritesEntity.findById(id) ?: ItemFavoritesEntity().apply {
            this.id = id
            this.userId = userId
            this.scopeId = scopeId
            this.nodeId = nodeId
        }
        row.ciphertext = ciphertext
        row.updatedAt = Instant.now()
        row.persist()
    }
}
