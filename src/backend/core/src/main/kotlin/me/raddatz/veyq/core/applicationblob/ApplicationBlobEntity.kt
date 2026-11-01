package me.raddatz.veyq.core.applicationblob

import me.raddatz.veyq.core.identity.PrincipalKey
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * One blob an application stores through core's blob service (ADR 0112 decision 1; the migration
 * `V202611012215__core_application_blob.sql` says what each column holds): a blob of `veyq_blob`, the fourth arm that
 * references one. Written once by its completion ([ApplicationBlobs.attach]); the only columns a write after the insert
 * changes are [keptAt] and [releasedAt], set by its application's own writes ([ApplicationBlobs.keep],
 * [ApplicationBlobs.releaseOwners], [ApplicationBlobs.release]). Its `K_blob` wrap is the blob row's, so a re-key rewrites
 * that row ([ApplicationBlobRekeyParticipant]), never this one. Removed only by the purge after the trash retention
 * ([ApplicationBlobPurge]) and the erasers of its node and its space.
 *
 * **Its four states**, which [purgeAfter] reads: never kept nor released (completed before its owner's first write —
 * the purge's clock is [createdAt]); kept ([keptAt] set, [releasedAt] null — the purge leaves it); released with its
 * owner's end ([keptAt] still set — the owner's next keep clears [releasedAt]); released by name ([keptAt] null for good —
 * no keep reaches it again).
 */
@Entity
@Table(name = "veyq_application_blob")
@DynamicUpdate
class ApplicationBlobEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    /** The application's record that holds it — opaque to core, stored and compared, never resolved. */
    @Column(name = "owner_id", nullable = false, columnDefinition = "uuid")
    lateinit var ownerId: String

    /** The application's role token, as its `MemberBlobRoles` and [ApplicationBlobOwners.roles] declare it. */
    @Column(name = "role", nullable = false, length = 16)
    lateinit var role: String

    @Column(name = "blob_id", nullable = false, columnDefinition = "uuid")
    lateinit var blobId: String

    /** Who completed it: a member, or a link identity ([writerIdentityId]) — exactly one (`ck_application_blob_writer`). */
    @Column(name = "writer_user_id", columnDefinition = "uuid")
    var writerUserId: String? = null

    @Column(name = "writer_identity_id", columnDefinition = "uuid")
    var writerIdentityId: String? = null

    /** When the server stored it — served with the blob, and the purge's clock while nothing ever kept it. */
    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** When its owner first kept it; null while none did, and for good once it was released by name. */
    @Column(name = "kept_at")
    var keptAt: Instant? = null

    /** When it was released, by name or with its owner's end; the purge's clock. Null while it is not released. */
    @Column(name = "released_at")
    var releasedAt: Instant? = null

    val writer: PrincipalKey
        get() = checkNotNull(PrincipalKey.of(writerUserId, writerIdentityId)) { "an application blob without a writer: $id" }

    companion object : PanacheCompanionBase<ApplicationBlobEntity, String> {
        /**
         * When the purge erases [row] (ADR 0112 decision 2): its release plus the [retention]; else, while nothing ever
         * kept it, its completion plus it; null while it is kept. Derived, never stored, so it follows a reconfigured
         * retention (ADR 0061 decision 6) — and the purge decides by the same rule ([ApplicationBlobPurge]).
         */
        fun purgeAfter(row: ApplicationBlobEntity, retention: Duration): Instant? = when {
            row.releasedAt != null -> row.releasedAt!!.plus(retention)
            row.keptAt == null -> row.createdAt.plus(retention)
            else -> null
        }
    }
}
