package me.raddatz.veyq.expenses.receipt

import me.raddatz.veyq.core.applicationblob.ApplicationBlobOwners
import me.raddatz.veyq.core.applicationblob.ApplicationBlobRole
import me.raddatz.veyq.core.applicationblob.OwnerAdmission
import me.raddatz.veyq.core.applicationblob.OwnerState
import me.raddatz.veyq.core.blob.ChunkFrames
import me.raddatz.veyq.core.blob.MemberBlobRoles
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.expenses.ExpensesNodeKinds
import me.raddatz.veyq.expenses.pot.PotRevisionEntity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType

/** Expenses' blob role — its value of the contract's open `BlobRole` token (16 characters, the column's limit). */
object ExpensesBlobRoles {
    /**
     * A receipt's bytes (ADR 0108 decision 7): a blob of core's blob service (ADR 0112), held by its entry as its owner.
     * The upload's `ownerId` is the Pot's node, so its `K_blob` wrap is sealed bound to (blob, Pot, `EXPENSES_RECEIPT`)
     * under the Pot's key — the shape of Depot's file content, with no new blob purpose. The entry's sealed revisions
     * list it by its id, which is the service's.
     */
    const val RECEIPT = "EXPENSES_RECEIPT"

    /** At most 10 MiB of plaintext (uc.expenses.attach-receipts E1) … */
    const val RECEIPT_MAX_PLAINTEXT_BYTES = 10L * 1024 * 1024

    /** … which is this many sealed bytes: 40 full frames, each with its nonce and tag. The server sees the sealed size,
     *  so this is the cap it holds (`BlobUploadHandler.admissibility`, through [ExpensesMemberBlobRoles.maxBytes]). */
    val RECEIPT_MAX_SEALED_BYTES: Long =
        ((RECEIPT_MAX_PLAINTEXT_BYTES + ChunkFrames.PLAINTEXT_BYTES - 1) / ChunkFrames.PLAINTEXT_BYTES) * ChunkFrames.OVERHEAD +
            RECEIPT_MAX_PLAINTEXT_BYTES

    /** How many receipts an entry holds at most — its sealed list's bound (`contracts/expenses/entry.v1.schema.json`),
     *  held by the service as the role's count per owner on every receipt not released. */
    const val RECEIPTS_PER_ENTRY = 100
}

/**
 * The role a member uploads an Expenses blob in, for core's member start (inbox #366): a receipt's bytes, and its cap —
 * 10 MiB of plaintext, held on every start of the role, a member's and a guest's ([ExpensesBlobRoles]).
 */
@ApplicationScoped
class ExpensesMemberBlobRoles : MemberBlobRoles {
    override fun roles(): Set<String> = setOf(ExpensesBlobRoles.RECEIPT)

    override fun application(): String = CapabilityIds.EXPENSES

    override fun maxBytes(role: String): Long? = if (role == ExpensesBlobRoles.RECEIPT) ExpensesBlobRoles.RECEIPT_MAX_SEALED_BYTES else null
}

/**
 * Expenses' half of core's blob service (ADR 0112 decision 2): a Pot's receipts are the service's blobs in the role
 * [ExpensesBlobRoles.RECEIPT], each held by an entry of the Pot as its owner — at most 100 not released per entry, and a
 * `READ_WRITE` guest adds one as a member does (ADR 0108 decision 6). What the service asks at a completion is Expenses'
 * rule: the entry is no other Pot's record, and the receipt's id names no record (both are reportable items' ids,
 * `PotReportableItems`) — else `id_taken`; and where the entry stands, read from its latest revision. Every write of an
 * entry tells the service which receipts it keeps and releases ([me.raddatz.veyq.expenses.pot.PotRevisionStore]).
 */
@ApplicationScoped
class ExpensesBlobOwners : ApplicationBlobOwners {
    override fun accepts(nodeKind: String): Boolean = nodeKind == ExpensesNodeKinds.POT

    override fun roles(): Map<String, ApplicationBlobRole> =
        mapOf(ExpensesBlobRoles.RECEIPT to ApplicationBlobRole(maxPerOwner = ExpensesBlobRoles.RECEIPTS_PER_ENTRY, guestMayAdd = true))

    /** The entry's revisions, so an append of its first revision into another Pot that holds them is seen. */
    override fun lockOwner(ownerId: String) {
        PotRevisionEntity.find("entryId = ?1 order by revision", ownerId).withLock(LockModeType.PESSIMISTIC_WRITE).list()
    }

    override fun admit(scopeId: String, nodeId: String, ownerId: String, blobRowId: String): OwnerAdmission {
        val record = PotRevisionEntity.list("entryId", ownerId)
        if (record.any { it.nodeId != nodeId }) return OwnerAdmission.Taken
        if (PotRevisionEntity.count("entryId", blobRowId) > 0) return OwnerAdmission.Taken
        val latest = record.maxByOrNull { it.revision } ?: return OwnerAdmission.Admitted(OwnerState.NEW)
        return OwnerAdmission.Admitted(if (latest.tombstone) OwnerState.ENDED else OwnerState.KEEPING)
    }
}
