package com.veyq.app.features.spaces.scope

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One epoch-chain link of a scope (ADR 0024, cr-0088). For epoch `n ≥ 1`, `wrappedPrev = symWrap(E_n, E_{n-1})` —
 * the previous epoch key sealed under this epoch's key (opaque ciphertext). A member holding the latest epoch
 * (via their grant) peels these links down to `E_0` to read content of any epoch. Operator-blind: no key here.
 */
@Entity
@Table(name = "veyq_scope_epoch")
class ScopeEpochEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "wrapped_prev", nullable = false)
    lateinit var wrappedPrev: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ScopeEpochEntity, String>
}
