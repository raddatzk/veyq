package me.raddatz.veyq.core.account.identitypins

import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** The caller's sealed pin store + its revision. `blob` is null for a born-empty (never-written) store. */
data class IdentityPinsView(val blob: String?, val revision: Long)

/** Outcome of a revision-guarded write — the new revision, or a stale-revision conflict (client must re-read + merge). */
sealed interface PutResult {
    data class Written(val revision: Long) : PutResult
    object Stale : PutResult, HttpFailure by httpFailure("stale_revision", CONFLICT)
}

/**
 * Reads + revision-guarded writes of the operator-blind identity-pin store (en.account.identity-pinning, cr-0098).
 * The stored `sealedBlob` is one opaque ciphertext the server never opens; the store only bookkeeps the monotonic
 * `revision` for optimistic concurrency. Keyed by the authenticated caller's account id — never an id in the request.
 */
@ApplicationScoped
class IdentityPinsStore {

    /** The caller's sealed pin store, or the born-empty `{ null, 0 }` when they have never written it. */
    @Transactional
    fun get(accountId: String): IdentityPinsView =
        IdentityPinsEntity.findById(accountId)?.let { IdentityPinsView(it.sealedBlob, it.revision) }
            ?: IdentityPinsView(null, 0)

    /**
     * Store a new sealed blob, guarded by the `expectedRevision` the client last read. The write succeeds only if
     * it still matches the stored revision, which is then bumped and returned; otherwise [PutResult.Stale] (→ 409,
     * the client re-reads, union-merges, retries). A born-empty store (no row) accepts only a first write against
     * revision 0, inserting the row at revision 1.
     *
     * The update path is an atomic compare-and-set (`where accountId = ? and revision = ?`), so two concurrent
     * writers can never both win. The far rarer concurrent *first* write (two devices inserting the very first
     * row at once) loses the second insert to the primary-key constraint rather than a clean 409 — benign and
     * pre-production: the loser retries, now sees revision 1, and takes the normal re-read/merge path.
     */
    @Transactional
    fun put(accountId: String, blob: String, expectedRevision: Long): PutResult {
        val existing = IdentityPinsEntity.findById(accountId)
        if (existing == null) {
            if (expectedRevision != 0L) return PutResult.Stale
            IdentityPinsEntity().apply {
                this.accountId = accountId
                this.sealedBlob = blob
                this.revision = 1
            }.persist()
            return PutResult.Written(1)
        }
        val newRevision = expectedRevision + 1
        val rows = IdentityPinsEntity.update(
            "sealedBlob = ?1, revision = ?2 where accountId = ?3 and revision = ?4",
            blob, newRevision, accountId, expectedRevision,
        )
        return if (rows == 1) PutResult.Written(newRevision) else PutResult.Stale
    }
}
