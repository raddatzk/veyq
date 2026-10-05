package me.raddatz.veyq.core.node

/**
 * One step of a node subtree's hard-erasure (the trash purge, ADR 0061 Decision 6): the package that owns rows
 * hanging off a node erases them here, so `NodeSubtreePurge` names no table of the Memories application
 * (ADR 0079 phase 1, step 6).
 *
 * **Order is [phase], sorted by the caller — never CDI bean order**, as for `ScopeEraser`. Core's own cover-blob
 * erase sits at [COVER_BLOBS_PHASE]: an eraser below it runs before the covers (media, whose blob rows must go
 * through the FK-ordered body), one above it after. No implementation may use [COVER_BLOBS_PHASE] itself.
 * `NodeSubtreeEraserOrderSliceTest` pins the table.
 *
 * [erase] runs inside `NodeSubtreePurge.eraseExpiredIn`'s transaction, **after** `ScopeEntity.lockRow` and the
 * selection of [nodeIds] under it. An implementation takes its own row locks as the rows' other erasers do, records
 * store objects through `StoreReclaimStore.record` only (never the blob store), and returns the **guest identity
 * ids it may have orphaned** — read while its rows still name them, i.e. before it deletes them. The caller
 * unions the sets and hands them to `SharingStore.dropDeadIdentities` only after every eraser and the link
 * tombstones have run, because that re-counts references ([me.raddatz.veyq.core.sharing.GuestIdentityUsage]).
 */
interface NodeSubtreeEraser {
    val phase: Int

    fun erase(scopeId: String, nodeIds: List<String>): Set<String>

    companion object {
        /** Where core erases the standalone cover blobs: after every eraser below, before every one above. */
        const val COVER_BLOBS_PHASE = 500
    }
}
