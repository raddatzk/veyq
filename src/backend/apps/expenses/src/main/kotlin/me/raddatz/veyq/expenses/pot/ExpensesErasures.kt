package me.raddatz.veyq.expenses.pot

import me.raddatz.veyq.core.backup.ApplicationErasures
import me.raddatz.veyq.core.items.NodeItemsRevisions
import me.raddatz.veyq.core.moderation.guestwrite.GuestWriteRecords
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/**
 * Expenses' half of the erasure ledger (ADR 0056 decision 8): a participant's erasure ([PotRevisionStore.erase]) deletes
 * its record's earlier revisions — its name and every earlier spelling of it — and a restored backup would bring them
 * back. So the erasure writes a line, and this re-applies it at start. The `when` over [Kind] is the sealed set's
 * property: a kind added without its re-applier does not compile.
 */
@ApplicationScoped
class ExpensesErasures(
    private val items: NodeItemsRevisions,
    private val guestWriteRecords: GuestWriteRecords,
) : ApplicationErasures {

    /** What Expenses erases for good: a record's revisions below its erasure revision (the id `entryId:revision`). */
    enum class Kind { EXPENSES_RECORD }

    override val kinds: Set<String> = Kind.entries.map { it.name }.toSet()

    override fun reapply(kind: String, id: String, scopeId: String?): Boolean = when (Kind.valueOf(kind)) {
        Kind.EXPENSES_RECORD -> RevisionRef.parse(id)?.let(::reapplyRecord) ?: false
    }

    /**
     * Every revision of the record below [erasure], back after a restore, deleted again — the lock order of the erasure
     * itself (the record's rows, then the item revision): an open list drops them on its next poll. The erasure revision
     * stays where the restore holds it; where it predates the erasure, the record's earlier revisions still go, since
     * the name they carry was erased (the reader shows the gap as a missing revision, ADR 0108 decision 5).
     */
    private fun reapplyRecord(erasure: RevisionRef): Boolean {
        val earlier = PotRevisionEntity.find("entryId = ?1 and revision < ?2 order by revision", erasure.entryId, erasure.revision)
            .withLock(LockModeType.PESSIMISTIC_WRITE).list()
        if (earlier.isEmpty()) return false
        guestWriteRecords.forget(earlier.map { it.id })
        PotRevisionEntity.delete("id in ?1", earlier.map { it.id })
        items.bump(earlier.first().scopeId, earlier.first().nodeId, deleted = true)
        return true
    }
}
