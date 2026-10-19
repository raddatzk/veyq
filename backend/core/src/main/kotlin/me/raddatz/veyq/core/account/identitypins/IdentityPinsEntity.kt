package me.raddatz.veyq.core.account.identitypins

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.io.Serializable

/**
 * The HEAD of the caller's operator-blind identity-pin store (en.account.identity-pinning, ADR 0028, ADR 0104): one
 * blob sealed under the Master Key at its revision — the store's version, its entry count and the digest of the whole
 * entry set — that the server stores and relays verbatim and never opens. Keyed by `accountId` (one row per account).
 * `revision` is the store's one optimistic-concurrency counter (born 0, bumped on each successful write of entries and
 * head). A born-empty account has no row and reads as `{ blob: null, revision: 0 }`.
 */
@Entity
@Table(name = "veyq_identity_pins")
@DynamicUpdate
class IdentityPinsEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Column(name = "sealed_blob")
    var sealedBlob: String? = null

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    companion object : PanacheCompanionBase<IdentityPinsEntity, String>
}

/**
 * One pin of the store (ADR 0104 decisions 1-2): sealed under the Master Key, bound to `pinId` — the opaque id
 * `HMAC-SHA256(K_pinIndex, utf8(userId))` as 64 lowercase hex characters, which the server cannot tell whose it is.
 * Written only together with the head, in its compare-and-set ([IdentityPinsStore.write]).
 */
@Entity
@Table(name = "veyq_identity_pin_entry")
@IdClass(IdentityPinEntryKey::class)
@DynamicUpdate
class IdentityPinEntryEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Id
    @Column(name = "pin_id", length = 64)
    lateinit var pinId: String

    @Column(name = "sealed_entry", nullable = false, columnDefinition = "TEXT")
    lateinit var sealedEntry: String

    companion object : PanacheCompanionBase<IdentityPinEntryEntity, IdentityPinEntryKey>
}

/** The entry's composite key. Defaults give JPA its no-argument constructor. */
data class IdentityPinEntryKey(var accountId: String = "", var pinId: String = "") : Serializable
