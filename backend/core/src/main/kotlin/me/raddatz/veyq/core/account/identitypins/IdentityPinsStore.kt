package me.raddatz.veyq.core.account.identitypins

import io.quarkus.panache.common.Sort
import me.raddatz.veyq.core.http.FailureStatus.CONFLICT
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.identity.AccountStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional

/** The caller's sealed head + its revision. `blob` is null for a born-empty (never-written) store. */
data class IdentityPinsView(val blob: String?, val revision: Long)

/** One sealed pin at its opaque id, as stored and as listed. */
data class PinEntry(val pinId: String, val entry: String)

/** One page of a listing, in ascending `pinId` order; `more` when entries follow it. */
data class PinEntryPage(val entries: List<PinEntry>, val more: Boolean)

/**
 * One write of the store (ADR 0104 decision 4): `delete` and `put` applied to the entries and `head` stored, only if
 * `expectedRevision` is still the head's. `replaceAll` removes every entry the write does not put.
 */
data class PinWrite(
    val expectedRevision: Long,
    val head: String,
    val put: List<PinEntry>,
    val delete: List<String>,
    val replaceAll: Boolean,
) {
    /**
     * Whether the write contradicts itself — refused before anything is read, `400 invalid_request`: one `pinId` put
     * twice (which would the store keep?), deleted twice, or both put and deleted, and a `delete` beside `replaceAll`
     * (which already removes everything not put). The bounds of each field are the contract's.
     */
    fun contradictory(): Boolean {
        val putIds = put.map { it.pinId }
        return putIds.toSet().size != putIds.size || delete.toSet().size != delete.size ||
            putIds.any { it in delete.toSet() } || (replaceAll && delete.isNotEmpty())
    }
}

/** Outcome of a write — the new revision, or a stale-revision conflict (the client re-reads and re-applies). */
sealed interface PutResult {
    data class Written(val revision: Long) : PutResult
    object Stale : PutResult, HttpFailure by httpFailure("stale_revision", CONFLICT)
    /** The write would leave more entries than the one write that carries them all may hold ([MAX_ENTRIES]). */
    object Full : PutResult, HttpFailure by Failures.invalidRequest
}

/**
 * Reads and compare-and-set writes of the operator-blind identity-pin store (en.account.identity-pinning, ADR 0104).
 * The head and every entry are opaque ciphertext the server never opens; the store bookkeeps the head's monotonic
 * `revision` and the entries' opaque ids. Keyed by the authenticated caller's account id — never an id in the request.
 */
@ApplicationScoped
class IdentityPinsStore(private val accounts: AccountStore) {

    /** The caller's sealed head, or the born-empty `{ null, 0 }` when they have never written the store. */
    @Transactional
    fun get(accountId: String): IdentityPinsView =
        IdentityPinsEntity.findById(accountId)?.let { IdentityPinsView(it.sealedBlob, it.revision) }
            ?: IdentityPinsView(null, 0)

    /**
     * Up to `limit` of the caller's entries after `after` (absent = from the first), in ascending `pinId` order —
     * keyset paging, so a page is the same whatever was written before it. One row more than asked says whether
     * another page follows. Not in the head's transaction: a write between two pages moves the head, and the client
     * that finds the set digest wrong re-reads the head and sees it moved (ADR 0104 decision 4).
     */
    @Transactional
    fun list(accountId: String, after: String?, limit: Int): PinEntryPage {
        val rows = if (after == null) {
            IdentityPinEntryEntity.find("accountId = ?1", Sort.by("pinId"), accountId)
        } else {
            IdentityPinEntryEntity.find("accountId = ?1 and pinId > ?2", Sort.by("pinId"), accountId, after)
        }.range(0, limit).list()
        return PinEntryPage(rows.take(limit).map { PinEntry(it.pinId, it.sealedEntry) }, rows.size > limit)
    }

    /**
     * Apply one write — entries and head — in ONE transaction, compare-and-set on the head's revision (ADR 0104
     * decision 4). Joins the caller's transaction where there is one: the rotation applies its re-keyed set inside
     * its own (ADR 0060 § Consequences).
     *
     * **The account row is locked first** (`AccountStore.lock`), so every writer of one account's store queues behind
     * it: the head's existence is decided under the lock, and two first writes racing at revision 0 answer one
     * `Written` and one [PutResult.Stale] — never a primary-key violation, which is what the single-blob store gave
     * the loser (a 500). Lock order: account row, head row, entry rows.
     *
     * The head moves first: a stale revision returns [PutResult.Stale] before any entry is touched, so nothing of the
     * write lands. Then the deletes (all of the account's entries for `replaceAll`, else the listed ids and the ids
     * about to be put, so a put replaces), then the puts. A write that would leave more than [MAX_ENTRIES] entries
     * throws [StoreFull]: the transaction it leaves rolls everything back, the head included, and the caller maps it to
     * [PutResult.Full].
     */
    @Transactional
    fun write(accountId: String, w: PinWrite): PutResult {
        accounts.lock(accountId) ?: return PutResult.Stale
        val existing = IdentityPinsEntity.findById(accountId)
        val revision = if (existing == null) {
            if (w.expectedRevision != 0L) return PutResult.Stale
            IdentityPinsEntity().apply {
                this.accountId = accountId
                this.sealedBlob = w.head
                this.revision = 1
            }.persist()
            1L
        } else {
            val next = w.expectedRevision + 1
            val rows = IdentityPinsEntity.update(
                "sealedBlob = ?1, revision = ?2 where accountId = ?3 and revision = ?4",
                w.head, next, accountId, w.expectedRevision,
            )
            if (rows != 1) return PutResult.Stale
            next
        }

        if (w.replaceAll) {
            IdentityPinEntryEntity.delete("accountId", accountId)
        } else {
            (w.delete + w.put.map { it.pinId }).chunked(CHUNK).forEach { ids ->
                IdentityPinEntryEntity.delete("accountId = ?1 and pinId in ?2", accountId, ids)
            }
        }
        insert(accountId, w.put)
        if (!w.replaceAll && IdentityPinEntryEntity.count("accountId", accountId) > MAX_ENTRIES) throw StoreFull()
        return PutResult.Written(revision)
    }

    /** The entries as multi-row inserts of [CHUNK] rows: a rotation carries up to [MAX_ENTRIES] of them in one write. */
    private fun insert(accountId: String, entries: List<PinEntry>) {
        val em = IdentityPinEntryEntity.getEntityManager()
        entries.chunked(CHUNK).forEach { chunk ->
            val query = em.createNativeQuery(
                "INSERT INTO veyq_identity_pin_entry (account_id, pin_id, sealed_entry) VALUES " +
                    chunk.joinToString(", ") { "(?, ?, ?)" },
            )
            chunk.forEachIndexed { i, e ->
                query.setParameter(3 * i + 1, accountId)
                query.setParameter(3 * i + 2, e.pinId)
                query.setParameter(3 * i + 3, e.entry)
            }
            query.executeUpdate()
        }
    }

    /** Thrown inside [write]'s transaction to roll the whole write back; never escapes [IdentityPinsHandler]. */
    class StoreFull : RuntimeException(null, null, false, false)

    companion object {
        /** The most entries a store holds — the contract's `put` bound, so the one write that carries every entry (a
         *  rotation, a reset) always fits (ADR 0104 § Consequences, measured against the request body limit). */
        const val MAX_ENTRIES = 25_000L
        private const val CHUNK = 500
    }
}
