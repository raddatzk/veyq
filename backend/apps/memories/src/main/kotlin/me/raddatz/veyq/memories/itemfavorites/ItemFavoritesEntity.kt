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
 * that member's marked-media-id set for ONE Memory, AEAD-sealed under `K_SC` — stored verbatim, never
 * interpreted.
 *
 * Unlike [me.raddatz.veyq.memories.favorites.FavoritesEntity] this blob is deliberately **shared**: the
 * read returns every member's row for the node, because the gallery's "everyone's / only mine" filter and its
 * per-item count are computed on device — a blind server cannot aggregate them.
 *
 * The natural key is (user, scope, node), encoded as one synthetic id so upsert is a plain findById.
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

    companion object : PanacheCompanionBase<ItemFavoritesEntity, String> {
        fun key(userId: String, scopeId: String, nodeId: String): String = "$userId|$scopeId|$nodeId"
    }
}
