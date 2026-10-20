package me.raddatz.veyq.core.favorites

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * The caller's per-(user, scope) pin blob (en.memory.favorites, ADR 0068; core's since the pin covers a node of any
 * kind). Operator-blind: `ciphertext` is the pinned-node-id set AEAD-sealed under the member's master key (not
 * `K_SC`) — the server stores it verbatim and never sees which nodes are pinned. The natural key is (user, scope); we encode it as a single synthetic id (`userId|scopeId`)
 * so upsert is a plain findById, no composite-key plumbing.
 */
@Entity
@Table(name = "veyq_scope_favorites")
@DynamicUpdate
class FavoritesEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 73)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "ciphertext", nullable = false)
    lateinit var ciphertext: String

    companion object : PanacheCompanionBase<FavoritesEntity, String> {
        fun key(userId: String, scopeId: String): String = "$userId|$scopeId"
    }
}
