package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.identity.PrincipalKey
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * One receipt of an entry of a Pot (ADR 0108 decision 7; the migration `V202610250946__expenses_receipts.sql` says what
 * each column holds): a blob of `veyq_blob`, the fourth arm that references one. Written once by its completion
 * ([PotReceiptStore.attach]); the only column a write after the insert changes is [droppedAt], stamped by the revision
 * that drops it ([PotReceiptStore.drop]). Its `K_blob` wrap is the blob row's, so a re-key rewrites that row
 * ([me.raddatz.veyq.expenses.pot.PotNodeRekeyParticipant]), never this one. Removed only by the purge after the trash
 * retention ([PotReceiptPurge]) and the erasers of the Pot and its space.
 */
@Entity
@Table(name = "veyq_expenses_receipt")
@DynamicUpdate
class PotReceiptEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    /** The record it is kept with — which may have no revision yet (a new entry's receipts come first). */
    @Column(name = "entry_id", nullable = false, columnDefinition = "uuid")
    lateinit var entryId: String

    @Column(name = "blob_id", nullable = false, columnDefinition = "uuid")
    lateinit var blobId: String

    /** Who completed it: a member, or a link identity ([writerIdentityId]) — exactly one (`ck_expenses_receipt_writer`). */
    @Column(name = "writer_user_id", columnDefinition = "uuid")
    var writerUserId: String? = null

    @Column(name = "writer_identity_id", columnDefinition = "uuid")
    var writerIdentityId: String? = null

    /** When the server stored it — served with the receipt, and the orphan's clock in the purge. */
    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** When a revision of its entry dropped it; the purge's clock. Null while none did. */
    @Column(name = "dropped_at")
    var droppedAt: Instant? = null

    val writer: PrincipalKey
        get() = checkNotNull(PrincipalKey.of(writerUserId, writerIdentityId)) { "a receipt without a writer: $id" }

    companion object : PanacheCompanionBase<PotReceiptEntity, String>
}
