package com.veyq.core.account.identitypins

import com.veyq.core.http.FailureStatus.CONFLICT
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/** The caller's sealed pin store + its version. `blob` is null for a born-empty (never-written) store. */
data class IdentityPinsView(val blob: String?, val version: Long)

/** Outcome of a version-guarded write — the new version, or a stale-version conflict (client must re-read + merge). */
sealed interface PutResult {
    data class Written(val version: Long) : PutResult
    object Stale : PutResult, HttpFailure by httpFailure("stale_version", CONFLICT)
}

/**
 * Reads + version-guarded writes of the operator-blind identity-pin store (en.account.identity-pinning, cr-0098).
 * The stored `sealedBlob` is one opaque ciphertext the server never opens; the store only bookkeeps the monotonic
 * `version` for optimistic concurrency. Keyed by the authenticated caller's account id — never an id in the request.
 */
@ApplicationScoped
class IdentityPinsStore {

    /** The caller's sealed pin store, or the born-empty `{ null, 0 }` when they have never written it. */
    @Transactional
    fun get(accountId: String): IdentityPinsView =
        IdentityPinsEntity.findById(accountId)?.let { IdentityPinsView(it.sealedBlob, it.version) }
            ?: IdentityPinsView(null, 0)

    /**
     * Store a new sealed blob, guarded by the `expectedVersion` the client last read. The write succeeds only if
     * it still matches the stored version, which is then bumped and returned; otherwise [PutResult.Stale] (→ 409,
     * the client re-reads, union-merges, retries). A born-empty store (no row) accepts only a first write against
     * version 0, inserting the row at version 1.
     *
     * The update path is an atomic compare-and-set (`where accountId = ? and version = ?`), so two concurrent
     * writers can never both win. The far rarer concurrent *first* write (two devices inserting the very first
     * row at once) loses the second insert to the primary-key constraint rather than a clean 409 — benign and
     * pre-production: the loser retries, now sees version 1, and takes the normal re-read/merge path.
     */
    @Transactional
    fun put(accountId: String, blob: String, expectedVersion: Long): PutResult {
        val existing = IdentityPinsEntity.findById(accountId)
        if (existing == null) {
            if (expectedVersion != 0L) return PutResult.Stale
            IdentityPinsEntity().apply {
                this.accountId = accountId
                this.sealedBlob = blob
                this.version = 1
                this.updatedAt = Instant.now()
            }.persist()
            return PutResult.Written(1)
        }
        val newVersion = expectedVersion + 1
        val rows = IdentityPinsEntity.update(
            "sealedBlob = ?1, version = ?2, updatedAt = ?3 where accountId = ?4 and version = ?5",
            blob, newVersion, Instant.now(), accountId, expectedVersion,
        )
        return if (rows == 1) PutResult.Written(newVersion) else PutResult.Stale
    }
}
