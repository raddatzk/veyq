package me.raddatz.veyq.core.account.rotation

/** Why a scope's next epoch cannot be committed now — a write the scope refuses everyone (uc.spaces.scope-access). */
enum class RotationBlock { TENANT_SUSPENDED, SCOPE_DELETING }

/** How far one scope a publish re-granted got (uc.account.rotate-keys §9, ADR 0060 Decision 6). */
enum class RegrantState { WAITING, REKEYING, SECURED, BLOCKED }

/** The facts one scope's state is derived from — every one read from a row the server holds. */
data class RegrantFacts(
    /** The scope's epoch when the publish re-granted it (the pending re-key row). */
    val epochAtRotation: Int,
    val scopeCurrentEpoch: Int,
    val blocked: RotationBlock?,
    /** Whether a live or trashed node of the scope still carries a key minted before its current epoch (ADR 0109). */
    val nodesLag: Boolean,
)

/**
 * The state of one scope after the account was secured, derived — never stored — so it cannot drift from the rows it
 * reads. Pure, so every rule is unit-testable. First match wins:
 *  1. `BLOCKED` while the tenant is suspended or the scope is being deleted — the next epoch is a write it refuses.
 *  2. `WAITING` while the scope still runs on the epoch it had at the rotation: the account's next device mints it.
 *  3. `REKEYING` while a node lags the current epoch (ADR 0109 point 1: a rotation is done only once its re-key is).
 *  4. `SECURED` otherwise — someone committed the next epoch, which no grant to the old identity can carry (ADR 0111
 *     decision 10), and every node is re-keyed.
 * No job backs any of it, so there is no `stalled` or `failed`: nobody else is waited on.
 */
object RotationScopeStates {
    fun state(f: RegrantFacts): RegrantState = when {
        f.blocked != null -> RegrantState.BLOCKED
        f.scopeCurrentEpoch <= f.epochAtRotation -> RegrantState.WAITING
        f.nodesLag -> RegrantState.REKEYING
        else -> RegrantState.SECURED
    }
}
