package me.raddatz.veyq.core.spaces.scope

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
 * One epoch's **succession statement** (ADR 0115 decision 5): for epoch `n ≥ 1`, the epoch's succession public key
 * (Ed25519, derived from `E_n` as `HKDF(E_n, "veyq/v1/scope-succession")`) and the signature of epoch `n - 1`'s
 * succession key over `(scopeId, n, successionPublicKey)`. It proves the new key's minter held the previous one and
 * opens nothing: a scope has one key at a time, and no older key is sealed under a newer one (decision 1). The server
 * stores and serves both public values as the rotation sent them and never verifies them — a member's device does,
 * from the epoch it held up. Written once by the rotation's commit (`ScopesStore.rotate`), erased with its scope.
 */
@Entity
@Table(name = "veyq_scope_succession")
@IdClass(ScopeSuccessionKey::class)
@DynamicUpdate
class ScopeSuccessionEntity : PanacheEntityBase {
    @Id
    @Column(name = "scope_id", columnDefinition = "uuid")
    lateinit var scopeId: String

    @Id
    @Column(name = "epoch")
    var epoch: Int = 0

    @Column(name = "succession_public_key", nullable = false, length = 44)
    lateinit var successionPublicKey: String

    @Column(name = "succession_signature", nullable = false, length = 88)
    lateinit var successionSignature: String

    companion object : PanacheCompanionBase<ScopeSuccessionEntity, ScopeSuccessionKey>
}

/** The succession statement's key, (scope, epoch). Defaults give JPA its no-argument constructor. */
data class ScopeSuccessionKey(var scopeId: String = "", var epoch: Int = 0) : Serializable

/** One succession statement as the wire serves it ([ScopeSuccessionEntity]). */
data class ScopeSuccession(val epoch: Int, val successionPublicKey: String, val successionSignature: String)
