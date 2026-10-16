package me.raddatz.veyq.core.account.identitypins

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * The caller's operator-blind identity-pin store (en.account.identity-pinning, cr-0098, ADR 0028): one blob
 * sealed under the Master Key (`symWrap(masterKey, blob, "identity-pins")`) the server stores + relays verbatim
 * and never opens — no userId, no key, no count. Keyed by `accountId` (one row per account). `revision` is the
 * monotonic optimistic-concurrency counter (born 0, bumped on each successful PUT). A born-empty account has no
 * row and reads as `{ blob: null, revision: 0 }`.
 */
@Entity
@Table(name = "veyq_identity_pins")
@DynamicUpdate
class IdentityPinsEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Column(name = "sealed_blob", columnDefinition = "MEDIUMTEXT")
    var sealedBlob: String? = null

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    companion object : PanacheCompanionBase<IdentityPinsEntity, String>
}
