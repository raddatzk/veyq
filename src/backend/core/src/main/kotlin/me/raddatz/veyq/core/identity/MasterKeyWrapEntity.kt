package me.raddatz.veyq.core.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/** One wrap of a user's Master Key, openable by a single unlock method. The wrapped key is opaque
 *  ciphertext the server cannot open. */
@Entity
@Table(name = "veyq_master_key_wrap")
@DynamicUpdate
class MasterKeyWrapEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "method", nullable = false, length = 16)
    lateinit var method: String

    @Column(name = "wrapped_key", nullable = false)
    lateinit var wrappedKey: String

    @Column(name = "kdf_params")
    var kdfParams: String? = null

    /** DEVICE_PIN only (ADR 0160 decision 3): the server share `S`, released only to a matching [pinVerifier]. */
    @Column(name = "pin_share", length = 44)
    var pinShare: String? = null

    /** DEVICE_PIN only: the PIN's verifier `V`, worthless without the device's hardware factor. */
    @Column(name = "pin_verifier", length = 44)
    var pinVerifier: String? = null

    /** DEVICE_PIN only: failed unlocks in a row; the tenth deletes the wrap, a success resets it. */
    @Column(name = "pin_failures", nullable = false)
    var pinFailures: Int = 0

    companion object : PanacheCompanionBase<MasterKeyWrapEntity, String>
}
