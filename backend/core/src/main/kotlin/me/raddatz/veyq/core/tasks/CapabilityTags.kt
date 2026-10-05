package me.raddatz.veyq.core.tasks

/**
 * The opaque capability tokens the backend pins from the public registry (`contracts/tasks/tags/v1.json`,
 * ADR 0021). A tag is the value that rides the job envelope + a worker's claim on the wire and in the DB, so a
 * bare dump sees `02f271ff`, not `grant-delivery`. Each is the first 4 bytes of SHA-256 over
 * `veyq/v1/task-capability/<name>`; a conformance test asserts these equal the registry (drift-proofing, the
 * same guard the crypto vectors get). The residual "what kind of work" leak is correlation-bounded, not
 * label-bounded — see [docs/crosscutting/background-tasks.md] — so these tokens only keep plaintext labels out
 * of the store.
 */
object CapabilityTags {
    /** grant-delivery (cr-0076 / ADR 0020): wrap K_SC to a pending member's identity — the first consumer. */
    const val GRANT_DELIVERY = "02f271ff"

    /**
     * scope-rotation (cr-0092 / ADR 0025, ADR 0060): complete a **queued scope-key rotation** the event causing it
     * could not perform — a co-shared member cut by a tenant owner who need not hold that group's key, or an account
     * rotation after a compromise. Offered only to an **owner** of the scope (ADR 0021 §4: only an owner may commit
     * a rotation), who claims it, reads the directory, mints `E_{n+1}` + re-grants the survivors on-device (the
     * cr-0089 rotation-commit), then completes — and the completion lands only on that holder's own commit during
     * its lease ([JobEntity.rotatedEpoch]). Unlike grant-delivery this is **not** reconcilable from DB state (a
     * removal cut advances no epoch), so it is originated **explicitly** (`resourceRef == scopeId`). An event joins a
     * job only while it is `PENDING`; once claimed, a later event gets a job of its own ([TasksStore]).
     */
    const val SCOPE_ROTATION = "663157d8"

    /**
     * Every tag the registry names as server-originated (`server-structural`, `server-event`): the server alone
     * creates these jobs, so a client's enqueue of one is refused. `QueuedRotationSliceTest` derives the same set from
     * `contracts/tasks/tags/v1.json` and asserts each member is refused, so a server tag missing here goes red there.
     */
    val SERVER_ORIGINATED: Set<String> = setOf(GRANT_DELIVERY, SCOPE_ROTATION)
}
