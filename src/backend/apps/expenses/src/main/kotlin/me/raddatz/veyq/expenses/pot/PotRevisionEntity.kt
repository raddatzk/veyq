package me.raddatz.veyq.expenses.pot

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
 * One revision of one record of a Pot (ADR 0108 decisions 3-5; the migration `V202610242200__expenses_revisions.sql`
 * says what each column holds). **Never overwritten**: the only column a write after the insert changes is
 * [wrappedRevisionKey], by a re-key ([PotNodeRekeyParticipant]) — the body and every digest of it stay byte-identical —
 * and [position], which every writer that moves the row to the list's end stamps. The only writer that removes a row
 * is a member's erasure of a participant ([PotRevisionStore.erase]) and the erasers of the Pot and its space.
 */
@Entity
@Table(name = "veyq_expenses_revision")
@DynamicUpdate
class PotRevisionEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    /** The record's client-minted id (ADR 0072 decision 3): a participant, an expense, a refund or a transfer — the
     *  server cannot tell which. */
    @Column(name = "entry_id", nullable = false, columnDefinition = "uuid")
    lateinit var entryId: String

    /** `n` of the record: the append takes `n + 1` only on `n` (`409 stale_revision`). */
    @Column(name = "revision", nullable = false)
    var revision: Long = 0

    /** The writer marked a delete — the one fact of a revision's content the server holds. */
    @Column(name = "tombstone", nullable = false)
    var tombstone: Boolean = false

    /** Written by [PotRevisionStore.erase]: the record's earlier revisions are gone. */
    @Column(name = "erasure", nullable = false)
    var erasure: Boolean = false

    /** The padded plaintext sealed under the revision's own K_rev (`expense-entry`), opaque base64 — under no node key,
     *  so a re-key leaves it (ADR 0108 decision 4). */
    @Column(name = "sealed_body", nullable = false)
    lateinit var sealedBody: String

    /** K_rev wrapped under the Pot's key (`expense-entry-key`), opaque base64 — the one field a re-key rewrites. */
    @Column(name = "wrapped_revision_key", nullable = false)
    lateinit var wrappedRevisionKey: String

    /** The padded plaintext's size class in bytes: 512, 2048, 8192 or 16384 ([PotBodies]). */
    @Column(name = "size_class", nullable = false)
    var sizeClass: Int = 0

    /** Who appended it: a member, or a link identity ([writerIdentityId]) — exactly one (`ck_expenses_revision_writer`). */
    @Column(name = "writer_user_id", columnDefinition = "uuid")
    var writerUserId: String? = null

    @Column(name = "writer_identity_id", columnDefinition = "uuid")
    var writerIdentityId: String? = null

    /** The Pot's item revision this row's last move to the list's end was stamped with ([me.raddatz.veyq.core.items.ItemsCursor]). */
    @Column(name = "item_position", nullable = false)
    var position: Int = 0

    /** When the server stored it — served with the revision as its history's time. */
    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    val writer: PrincipalKey
        get() = checkNotNull(PrincipalKey.of(writerUserId, writerIdentityId)) { "a revision without a writer: $id" }

    companion object : PanacheCompanionBase<PotRevisionEntity, String>
}
