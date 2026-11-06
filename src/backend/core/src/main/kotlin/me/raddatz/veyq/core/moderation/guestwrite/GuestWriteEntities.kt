package me.raddatz.veyq.core.moderation.guestwrite

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/** What a guest-write record names as written (`object_kind`): a share identity, a node, an application's item. */
enum class GuestWriteObject { IDENTITY, NODE, ITEM }

/**
 * One guest write (`veyq_guest_write_record`, en.moderation.guest-write-record): the identity that wrote, the object,
 * and what the server saw of the client — the resolver's address in text form, its source port where known — and when,
 * in milliseconds UTC, with the `Origin` header the request carried ([me.raddatz.veyq.core.http.RequestOrigin]: a
 * serialized origin, `null`, `invalid`, or no value when none was sent). The origin is the one field a client states — a
 * browser truthfully, any other client freely — and it is kept only in those shapes. Never content.
 */
@Entity
@Table(name = "veyq_guest_write_record")
@DynamicUpdate
class GuestWriteRecordEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "share_identity_id", nullable = false, columnDefinition = "uuid")
    lateinit var shareIdentityId: String

    @Column(name = "object_kind", nullable = false, length = 16)
    lateinit var objectKind: String

    @Column(name = "object_id", nullable = false, columnDefinition = "uuid")
    lateinit var objectId: String

    @Column(name = "field_group", length = 16)
    var fieldGroup: String? = null

    @Column(name = "client_ip", nullable = false, length = 45)
    lateinit var clientIp: String

    @Column(name = "client_port")
    var clientPort: Int? = null

    @Column(name = "request_origin", length = 255)
    var requestOrigin: String? = null

    @Column(name = "observed_at", nullable = false)
    lateinit var observedAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<GuestWriteRecordEntity, String>
}

/** The retention the Server Owner set (`veyq_guest_write_retention`, one row, id 1); no row reads as the default. */
@Entity
@Table(name = "veyq_guest_write_retention")
@DynamicUpdate
class GuestWriteRetentionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = ROW

    @Column(name = "retention_days", nullable = false)
    var retentionDays: Int = GuestWriteRecords.DEFAULT_DAYS

    companion object : PanacheCompanionBase<GuestWriteRetentionEntity, Int> {
        const val ROW = 1
    }
}
