package me.raddatz.veyq.memories.itemfavorites

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * One member's per-node item-favorites blob (en.memory.favorites, ADR 0068). Operator-blind: `ciphertext` is
 * that member's marked-media-id set for ONE Memory, AEAD-sealed under the scope key `K_SC` — stored verbatim, never
 * interpreted. Every rotation re-seals every row under its new key (ADR 0115 decision 2,
 * [ItemFavoritesScopeRekeyParticipant]), so a row is always under the scope's current one.
 *
 * Unlike [me.raddatz.veyq.memories.favorites.FavoritesEntity] this blob is deliberately **shared**: the
 * read returns every member's row for the node, because the gallery's "everyone's / only mine" filter and its
 * per-item count are computed on device — a blind server cannot aggregate them.
 *
 * The natural key is (user, scope, node), encoded as one synthetic id so upsert is a plain findById. That id spells the
 * member's account id, so the rotator reads and stages a row by [markId] instead: an opaque random id of its own, so
 * the re-seal attributes a mark no more than the node's read does (ADR 0068 § 9).
 */
@Entity
@Table(name = "veyq_scope_item_favorites")
@DynamicUpdate
class ItemFavoritesEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 110)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    @Column(name = "ciphertext", nullable = false)
    lateinit var ciphertext: String

    /** The row's opaque address for a rotation's re-seal: random, minted with the row, never changed. */
    @Column(name = "mark_id", nullable = false, columnDefinition = "uuid")
    lateinit var markId: String

    companion object : PanacheCompanionBase<ItemFavoritesEntity, String> {
        fun key(userId: String, scopeId: String, nodeId: String): String = "$userId|$scopeId|$nodeId"
    }
}
