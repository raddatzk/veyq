package com.veyq.app.features.account.identitypins

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The caller's operator-blind identity-pin store (en.account.identity-pinning, cr-0098, ADR 0028): one blob
 * sealed under the Master Key (`symWrap(masterKey, blob, "identity-pins")`) the server stores + relays verbatim
 * and never opens — no userId, no key, no count. Keyed by `accountId` (one row per account). `version` is the
 * monotonic optimistic-concurrency counter (born 0, bumped on each successful PUT). A born-empty account has no
 * row and reads as `{ blob: null, version: 0 }`.
 */
@Entity
@Table(name = "veyq_identity_pins")
class IdentityPinsEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", length = 36)
    lateinit var accountId: String

    @Column(name = "sealed_blob", columnDefinition = "MEDIUMTEXT")
    var sealedBlob: String? = null

    @Column(name = "version", nullable = false)
    var version: Long = 0

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    companion object : PanacheCompanionBase<IdentityPinsEntity, String>
}
