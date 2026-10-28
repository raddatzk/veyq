package me.raddatz.veyq.core.identity.keychain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * One step of an account's key chain (ADR 0111 decision 7, cr-secure-the-account decision 2): public keys and
 * signatures, nothing secret. Step 0 ([idx] 0) holds the identity bundle and Secret-Key proof public key the account
 * was born with and no signature; step `i ≥ 1` holds the keys one securing of the account installed and the two
 * signatures of the keys it retired. [head] is the head this step makes — derived (`KeyChainStatements.head`), kept so a
 * directory read serves it without rebuilding the chain. Written only by [KeyChainStore]; cascades from the account.
 */
@Entity
@DynamicUpdate
@Table(name = "veyq_key_chain_step")
class KeyChainStepEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "idx", nullable = false)
    var idx: Int = 0

    @Column(name = "identity_public_key", nullable = false, columnDefinition = "TEXT")
    lateinit var identityPublicKey: String

    @Column(name = "secret_key_proof_public_key", nullable = false, columnDefinition = "TEXT")
    lateinit var secretKeyProofPublicKey: String

    @Column(name = "identity_signature", columnDefinition = "TEXT")
    var identitySignature: String? = null

    @Column(name = "proof_signature", columnDefinition = "TEXT")
    var proofSignature: String? = null

    @Column(name = "head", nullable = false, length = 44)
    lateinit var head: String

    /** The server-observed time of the step — served by `getKeyChain`, which the profile's "new identity … on <date>"
     *  reads. */
    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<KeyChainStepEntity, String>
}
