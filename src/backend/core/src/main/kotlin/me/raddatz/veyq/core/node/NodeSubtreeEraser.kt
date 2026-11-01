package me.raddatz.veyq.core.node

/**
 * One step of a node subtree's hard-erasure (the trash purge, ADR 0061 Decision 6): the package that owns rows
 * hanging off a node erases them here, so `NodeSubtreePurge` names no table of the Memories application
 * (ADR 0079 phase 1, step 6).
 *
 * **Order is [phase], sorted by the caller — never CDI bean order**, as for `ScopeEraser`; distinct phases, so order
 * never rests on a tie. Core erases nothing between them of its own any more: the standalone cover blobs it once
 * erased at phase 500 are attachments since inbox #341, erased by `AttachmentNodeSubtreeEraser`.
 * `NodeSubtreeEraserOrderSliceTest` pins the table.
 *
 * [erase] runs inside the transaction of a `NodeSubtreePurge` entry (the scheduled `eraseExpiredIn`, a member's
 * `eraseTrashRoot`, …), **after** `ScopeEntity.lockRow` and the selection of [nodeIds] under it. An implementation takes its own row locks as the rows' other erasers do, records
 * store objects through `StoreReclaimStore.record` only (never the blob store), and returns the **guest identity
 * ids it may have orphaned** — read while its rows still name them, i.e. before it deletes them. The caller
 * unions the sets and hands them to `SharingStore.dropDeadIdentities` only after every eraser and the link
 * tombstones have run, because that re-counts references ([me.raddatz.veyq.core.sharing.GuestIdentityUsage]).
 */
interface NodeSubtreeEraser {
    val phase: Int

    fun erase(scopeId: String, nodeIds: List<String>): Set<String>

    /**
     * The blobs [erase] will delete the rows of, with the locks on its own item rows taken first (item rows before blob
     * rows, `StoredObjectLocks`): the caller locks every eraser's objects **at once**, in the one lock order, before any
     * eraser runs - one by one, two erasers' objects would be locked in phase order, crosswise to a measure-taker's.
     * None for an eraser that deletes no blob row.
     */
    fun blobIdsOf(scopeId: String, nodeIds: List<String>): Collection<String> = emptyList()
}
