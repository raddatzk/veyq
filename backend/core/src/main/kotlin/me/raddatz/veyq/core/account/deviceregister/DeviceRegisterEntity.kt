package me.raddatz.veyq.core.account.deviceregister

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * The caller's operator-blind device register (en.account.device-register, ADR 0004): one blob sealed under the
 * Master Key (`symWrap(masterKey, blob, "device-register")`) the server stores + relays verbatim and never opens.
 * It holds a stable client-side device id, its label, and the device<->session-family join — the last of which is
 * precisely what the server must not learn, since it would cluster N deliberately-unlinkable families into
 * per-device groups. Keyed by `accountId` (one row per account). `revision` is the monotonic
 * optimistic-concurrency counter (born 0, bumped on each successful PUT). A born-empty account has no row and
 * reads as `{ blob: null, revision: 0 }`.
 *
 * `sealed_blob` is plain `TEXT`, unlike the sibling identity-pin store: the register is small (a handful of short
 * labels and joins) and its contract cap sits inside the MariaDB `TEXT` ceiling, so it needs no widening.
 */
@Entity
@Table(name = "veyq_device_register")
@DynamicUpdate
class DeviceRegisterEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Column(name = "sealed_blob")
    var sealedBlob: String? = null

    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    companion object : PanacheCompanionBase<DeviceRegisterEntity, String>
}
