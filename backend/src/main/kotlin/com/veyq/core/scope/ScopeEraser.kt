package com.veyq.core.scope

/**
 * One step of a group's hard-erasure (uc.spaces.delete-group, ADR 0014 §6): the package that owns an entity
 * with a `scope_id` deletes that entity's rows here, so `ScopePurge` names no table of the Memories
 * application (ADR 0079 phase 1, step 5).
 *
 * **Order is [phase], sorted by the caller — never CDI bean order.** `ScopePurge` collects every implementation
 * and runs them `sortedBy { phase }`; a phase is therefore a statement about a foreign key, and each
 * implementation says in its own KDoc which FK its number protects. Core's own blob and upload-session row
 * deletes sit at [BLOB_ROWS_PHASE]: an eraser below it runs while the blob rows still exist (anything whose rows
 * reference `veyq_blob`, i.e. media and its derivatives, must), one above it runs after. No implementation may
 * use [BLOB_ROWS_PHASE] itself. `ScopeEraserOrderSliceTest` pins the table.
 *
 * An implementation erases rows only. It must not record store objects for reclaim (`ScopePurge` does that for
 * blobs and sessions before any eraser runs) and must not call the blob store — `validate_store_reclaim_arity.py`
 * holds the second half. It runs inside `ScopePurge`'s per-scope transaction and the scope row's lock.
 *
 * `scripts/validate_scope_erasure_arity.py` unions the entities deleted across every implementation of this
 * interface, so a table that drops out of its eraser is red there.
 */
interface ScopeEraser {
    val phase: Int

    fun eraseScope(scopeId: String)

    companion object {
        /** Where core deletes `veyq_blob` and `veyq_upload_session` rows: after every eraser below, before every one above. */
        const val BLOB_ROWS_PHASE = 500
    }
}
