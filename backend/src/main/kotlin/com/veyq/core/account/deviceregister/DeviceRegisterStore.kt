package com.veyq.core.account.deviceregister

import com.veyq.core.http.FailureStatus.CONFLICT
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpFailure
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.time.Instant

/** The caller's sealed device register + its version. `blob` is null for a born-empty (never-written) register. */
data class DeviceRegisterView(val blob: String?, val version: Long)

/** Outcome of a version-guarded write — the new version, or a stale-version conflict (client must re-read + merge). */
sealed interface PutResult {
    data class Written(val version: Long) : PutResult
    object Stale : PutResult, HttpFailure by httpFailure("stale_version", CONFLICT)
}

/**
 * Reads + version-guarded writes of the operator-blind device register (en.account.device-register). The stored
 * `sealedBlob` is one opaque ciphertext the server never opens; this store only bookkeeps the monotonic `version`
 * for optimistic concurrency. Keyed by the authenticated caller's account id — never an id in the request.
 *
 * The 409 this raises is load-bearing rather than a mere race guard: it is what forces the client through its
 * re-read-and-merge path, and that merge is where the register's correctness lives (en.account.device-register
 * §Sync & merge — an external removal is a frozen id-delta, an eviction is recomputed on the merged map).
 */
@ApplicationScoped
class DeviceRegisterStore {

    /** The caller's sealed register, or the born-empty `{ null, 0 }` when they have never written it. */
    @Transactional
    fun get(accountId: String): DeviceRegisterView =
        DeviceRegisterEntity.findById(accountId)?.let { DeviceRegisterView(it.sealedBlob, it.version) }
            ?: DeviceRegisterView(null, 0)

    /**
     * Store a new sealed blob, guarded by the `expectedVersion` the client last read. The write succeeds only if
     * it still matches the stored version, which is then bumped and returned; otherwise [PutResult.Stale] (→ 409,
     * the client re-reads, merges, retries). A born-empty register (no row) accepts only a first write against
     * version 0, inserting the row at version 1.
     *
     * The update path is an atomic compare-and-set (`where accountId = ? and version = ?`), so two concurrent
     * writers can never both win. The far rarer concurrent *first* write (two devices inserting the very first
     * row at once) loses the second insert to the primary-key constraint rather than a clean 409 — benign: the
     * loser retries, now sees version 1, and takes the normal re-read/merge path.
     */
    @Transactional
    fun put(accountId: String, blob: String, expectedVersion: Long): PutResult {
        val existing = DeviceRegisterEntity.findById(accountId)
        if (existing == null) {
            if (expectedVersion != 0L) return PutResult.Stale
            DeviceRegisterEntity().apply {
                this.accountId = accountId
                this.sealedBlob = blob
                this.version = 1
                this.updatedAt = Instant.now()
            }.persist()
            return PutResult.Written(1)
        }
        val newVersion = expectedVersion + 1
        val rows = DeviceRegisterEntity.update(
            "sealedBlob = ?1, version = ?2, updatedAt = ?3 where accountId = ?4 and version = ?5",
            blob, newVersion, Instant.now(), accountId, expectedVersion,
        )
        return if (rows == 1) PutResult.Written(newVersion) else PutResult.Stale
    }
}
