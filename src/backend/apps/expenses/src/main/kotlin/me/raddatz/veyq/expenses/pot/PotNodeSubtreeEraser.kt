package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.block.MeasureTargets
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import me.raddatz.veyq.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * The revisions of the Pots of an erased node subtree (the trash purge, ADR 0061 decision 6) — a Pot's deleted entries
 * live inside it and go with it. Phase 150: free between Memories' media (100) and core's attachments (200); the table
 * names no blob, so any phase before core's counters (300) would do. The rows are locked first, as every other writer
 * of them locks them (the record rows before the node). Returns the link identities that wrote them, for the caller to
 * drop once nothing else names them; the guest-write records naming the rows go with them, and a content block on an
 * entry ends with its last revision (ADR 0093 § 6).
 */
@ApplicationScoped
class PotNodeSubtreeEraser(
    private val guestWriteRecords: GuestWriteRecords,
    private val measures: MeasureTargets,
) : NodeSubtreeEraser {
    override val phase = 150

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        val rows = PotRevisionEntity.find("nodeId in ?1 order by entryId, revision", nodeIds).withLock(LockModeType.PESSIMISTIC_WRITE).list()
        if (rows.isEmpty()) return emptySet()
        val guests = rows.mapNotNullTo(HashSet()) { it.writerIdentityId }
        guestWriteRecords.forget(rows.map { it.id })
        PotRevisionEntity.delete("nodeId in ?1", nodeIds)
        measures.erased(listOf(BlockKind.CONTENT_ITEM), rows.map { it.entryId }.distinct())
        return guests
    }
}
