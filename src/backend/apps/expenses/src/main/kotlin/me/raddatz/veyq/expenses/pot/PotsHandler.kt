package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.content.NodesStore
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.identity.PrincipalKey
import me.raddatz.veyq.core.items.ItemsCursor
import me.raddatz.veyq.core.items.ItemsRead
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.moderation.block.ContentView
import me.raddatz.veyq.core.moderation.block.ModerationBlocks
import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.platform.capabilities.ApplicationSwitch
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.sharing.ShareIdentityEntity
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import jakarta.enterprise.context.ApplicationScoped

/** A link guest who wrote a revision of a member's page, with the sealed display name the server relays. */
data class GuestWriterRow(val identityId: String, val shareId: String, val sealedName: String)

sealed interface PotListOutcome {
    /** The client's `If-None-Match` still equals the Pot's item revision: `304`, no revision query ran. */
    object NotModified : PotListOutcome

    data class Page(
        val revisions: List<PotRevisionEntity>,
        val ledgerRevision: Int,
        val deleteRevision: Int,
        val refs: List<RevisionRef>?,
        val more: Boolean,
        val cursor: String?,
        val guestWriters: List<GuestWriterRow>,
    ) : PotListOutcome

    /** No grant for the scope (or no such scope) — the gate's 404, the same for a real id as for an invented one. */
    object NoScope : PotListOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : PotListOutcome, HttpFailure by Failures.tenantSuspended
    /** No `EXPENSES_POT` of that id in the scope. */
    object NoPot : PotListOutcome, HttpFailure by Failures.nodeNotFound
}

/** What an append or an erasure answers. Nothing is stored on any answer but [Done]. */
sealed interface PotWriteOutcome {
    object Done : PotWriteOutcome
    /** `409 stale_revision` with every stale record and its latest revision (0: none). */
    data class Stale(val stale: List<RevisionRef>) : PotWriteOutcome, HttpFailure by Failures.staleRevision
    object Invalid : PotWriteOutcome, HttpFailure by Failures.invalidRequest
    object NoScope : PotWriteOutcome, HttpFailure by Failures.scopeNotFound
    object Suspended : PotWriteOutcome, HttpFailure by Failures.tenantSuspended
    object Deleting : PotWriteOutcome, HttpFailure by Failures.scopeDeleting
    object NoPot : PotWriteOutcome, HttpFailure by Failures.nodeNotFound
    object IdTaken : PotWriteOutcome, HttpFailure by Failures.idTaken
    /** A dropped receipt is no receipt of its record in this Pot. */
    object ReceiptNotHeld : PotWriteOutcome, HttpFailure by Failures.invalidRequest
    object StaleEpoch : PotWriteOutcome, HttpFailure by Failures.staleEpoch
    object RekeyPending : PotWriteOutcome, HttpFailure by Failures.nodeRekeyPending
    /** A moderator blocked the Pot, a node above it, or the record a write edits (`451 blocked_by_operator`, [PotBlocks]). */
    object Blocked : PotWriteOutcome, HttpFailure by Failures.blockedByOperator
}

/**
 * What a moderator's content block refuses of a Pot's writers, a member and a guest alike (ADR 0108 decision 6, inbox
 * #542) — ADR 0093 § 6's rule for blocked content, "deleted but not replaced or restored", in a Pot's terms: a block of
 * the Pot (or a node above it) refuses every revision but a tombstone, and a block of one entry refuses every revision
 * of that entry but a tombstone. A tombstone is the record's deletion, which a block never refuses; a member's erasure
 * of a participant is a removal too and is not asked. A receipt is new content, so core's blob service refuses it on a
 * blocked Pot or a blocked entry, its owner (ADR 0112). Asked before the write and outside its lock, as Depot's replace
 * asks it: a block landing while a write runs is the next write's to meet.
 */
internal object PotBlocks {
    fun refusesWrites(view: ContentView, nodeId: String, writes: List<RevisionWrite>): Boolean =
        !view.isEmpty && writes.any { !it.tombstone && view.itemBlocked(it.entryId, nodeId) }
}

/** The request checks the contract cannot write: one size class per body, one revision per record in a batch, each
 *  dropped receipt named once. */
internal object PotRequests {
    fun valid(writes: List<RevisionWrite>): Boolean =
        writes.map { it.entryId }.distinct().size == writes.size && writes.all { PotBodies.sizeClassOf(it.sealedBody.length) != null } &&
            writes.flatMap { it.droppedReceipts }.let { it.distinct().size == it.size }

    fun outcome(result: PotWriteResult): PotWriteOutcome = when (result) {
        is PotWriteResult.Stored -> PotWriteOutcome.Done
        is PotWriteResult.Stale -> PotWriteOutcome.Stale(result.stale)
        PotWriteResult.IdTaken -> PotWriteOutcome.IdTaken
        PotWriteResult.ReceiptNotHeld -> PotWriteOutcome.ReceiptNotHeld
        PotWriteResult.NotFound -> PotWriteOutcome.NoPot
        PotWriteResult.StaleEpoch -> PotWriteOutcome.StaleEpoch
        PotWriteResult.RekeyPending -> PotWriteOutcome.RekeyPending
    }
}

/**
 * A member's Pot (ADR 0108; tag `expenses`): the scope-access gate first ([ScopeAccess], uc.spaces.scope-access — a
 * non-member gets the 404 before anything else is read), then the application switch on a write
 * ([ApplicationSwitch.requireWritable]: off, Expenses is read-only), then the Pot. The rules about money are the
 * client's; the server takes revisions on their predecessor and serves them back.
 */
@ApplicationScoped
class PotsHandler(
    private val access: ScopeAccess,
    private val nodes: NodesStore,
    private val store: PotRevisionStore,
    private val items: NodeItemsRevisions,
    private val applications: ApplicationSwitch,
    private val blocks: ModerationBlocks,
) {

    /** A Pot's revisions as core's conditional keyset read (ADR 0033): live or trashed, its trash view reads it too. */
    fun list(userId: String, scopeId: String, nodeId: String, ifNoneMatch: Int?, since: ItemsCursor?, sinceDelete: Int, limit: Int): PotListOutcome {
        access.check(userId, scopeId, READ).refusal(PotListOutcome.NoScope, PotListOutcome.Suspended)?.let { return it }
        potOf(scopeId, nodeId, liveOnly = false) ?: return PotListOutcome.NoPot
        return when (
            val r = items.read(nodeId, ifNoneMatch, sinceDelete, delta = { store.page(nodeId, since, limit) }, ids = { store.refs(nodeId) })
        ) {
            ItemsRead.NotModified -> PotListOutcome.NotModified
            is ItemsRead.Changed -> PotListOutcome.Page(
                r.delta.items, r.revisions.revision, r.revisions.deleteRevision, r.ids?.mapNotNull(RevisionRef::parse),
                r.delta.more, r.delta.cursor?.wire(), guestWriters(r.delta.items),
            )
        }
    }

    /** A member's batch of revisions (ADR 0108 decision 3). */
    fun append(userId: String, scopeId: String, nodeId: String, nodeEpoch: Int, writes: List<RevisionWrite>): PotWriteOutcome {
        if (!PotRequests.valid(writes)) return PotWriteOutcome.Invalid
        access.check(userId, scopeId, WRITE)
            .refusal(PotWriteOutcome.NoScope, PotWriteOutcome.Suspended, PotWriteOutcome.Deleting)?.let { return it }
        applications.requireWritable(CapabilityIds.EXPENSES, scopeId) // switched off, read-only (uc.platform.switch-application-off)
        potOf(scopeId, nodeId, liveOnly = true) ?: return PotWriteOutcome.NoPot
        if (PotBlocks.refusesWrites(blocks.contentView(scopeId), nodeId, writes)) return PotWriteOutcome.Blocked
        return PotRequests.outcome(store.append(scopeId, nodeId, nodeEpoch, writes, PrincipalKey.User(userId)))
    }

    /**
     * A member's erasure of a participant's record (ADR 0108 decision 3). Not asked of the application switch: an erasure is a
     * removal, which an application off never refuses (uc.platform.switch-application-off E2) — a participant's name
     * goes when a member says so.
     */
    fun erase(userId: String, scopeId: String, nodeId: String, nodeEpoch: Int, write: RevisionWrite): PotWriteOutcome {
        if (PotBodies.sizeClassOf(write.sealedBody.length) == null) return PotWriteOutcome.Invalid
        access.check(userId, scopeId, WRITE)
            .refusal(PotWriteOutcome.NoScope, PotWriteOutcome.Suspended, PotWriteOutcome.Deleting)?.let { return it }
        potOf(scopeId, nodeId, liveOnly = true) ?: return PotWriteOutcome.NoPot
        return PotRequests.outcome(store.erase(scopeId, nodeId, nodeEpoch, write, PrincipalKey.User(userId)))
    }

    /** The Pot [nodeId] of [scopeId] — a plain read deciding which answer a mistake gets; the write asks again under
     *  the node's lock ([PotRevisionStore]). */
    private fun potOf(scopeId: String, nodeId: String, liveOnly: Boolean): NodeEntity? {
        val node = if (liveOnly) nodes.findInScope(scopeId, nodeId) else NodeEntity.findById(nodeId)?.takeIf { it.scopeId == scopeId }
        return node?.takeIf { it.kind == ExpensesNodeKinds.POT }
    }

    /** The link identities among [rows]' writers that still exist, with their sealed names — one query a page. */
    private fun guestWriters(rows: List<PotRevisionEntity>): List<GuestWriterRow> {
        val ids = rows.mapNotNull { it.writerIdentityId }.distinct()
        if (ids.isEmpty()) return emptyList()
        return ShareIdentityEntity.list("id in ?1", ids).sortedBy { it.id }.map { GuestWriterRow(it.id, it.shareId, it.sealedDisplayName) }
    }
}
