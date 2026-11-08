package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.b64url

/** Where a revision lives — everything its two seals bind. */
data class RevisionAddress(val scopeId: String, val nodeId: String, val entryId: String, val revision: Long)

/** One revision as the wire carries it: the sealed body and the wrapped `K_rev`, raw bytes. */
class SealedRevision(val sealedBody: ByteArray, val wrappedRevisionKey: ByteArray)

/** Why a served revision did not open: its wrap or body does not open at this address, or it is no padded v1 body. */
class RevisionUnreadable(val reason: Reason) : Exception("revision_unreadable: $reason") {
    enum class Reason { KEY, BODY, FORMAT }
}

/**
 * The format v1 codec of one Expenses revision, a port of `src/web/src/features/expenses/domain/codec.ts` (ADR 0108
 * decision 4): the plaintext padded to a size class, sealed as an `expense-entry` field under a fresh `K_rev`, and `K_rev`
 * sealed under the Pot's `K_node` as `expense-entry-key`. Both bind the ADDRESS the revision is read by — never a field of
 * the row that carries it — so a body served at another entry, revision or Pot does not open.
 */
class RevisionCodec(private val suite: V1) {
    private fun keyContext(a: RevisionAddress) =
        Context("expense-entry-key", "scopeId" to a.scopeId, "nodeId" to a.nodeId, "entryId" to a.entryId, "revision" to a.revision)

    private fun bodyContext(a: RevisionAddress) =
        Context("expense-entry", "scopeId" to a.scopeId, "nodeId" to a.nodeId, "entryId" to a.entryId, "revision" to a.revision)

    /** Seals one revision under a fresh `K_rev`, wrapped under the Pot's key; past 16 KiB throws `pad_too_large`. */
    fun seal(address: RevisionAddress, potKey: ByteArray, body: RevisionBody): SealedRevision {
        val kRev = suite.p.randomBytes(32)
        try {
            val padded = Pad.padToClass(RevisionFormat.encode(body), SIZE_CLASSES)
            return SealedRevision(suite.sealField(bodyContext(address), kRev, padded), suite.seal(keyContext(address), potKey, kRev))
        } finally {
            kRev.fill(0)
        }
    }

    /** Opens one revision read at [address] with the Pot's key; throws [RevisionUnreadable]. */
    fun open(address: RevisionAddress, potKey: ByteArray, sealed: SealedRevision): RevisionBody {
        val kRev = try {
            suite.open(keyContext(address), potKey, sealed.wrappedRevisionKey)
        } catch (e: Exception) {
            throw RevisionUnreadable(RevisionUnreadable.Reason.KEY)
        }
        val padded = try {
            suite.openField(bodyContext(address), kRev, sealed.sealedBody)
        } catch (e: Exception) {
            throw RevisionUnreadable(RevisionUnreadable.Reason.BODY)
        } finally {
            kRev.fill(0)
        }
        return try {
            RevisionFormat.decode(Pad.unpad(padded))
        } catch (e: Exception) {
            throw RevisionUnreadable(RevisionUnreadable.Reason.FORMAT)
        }
    }

    /** The digest a later revision's heads name for this one: SHA-256 of its sealed body as served, base64url. */
    fun digest(sealedBody: ByteArray): String = b64url(suite.p.sha256(sealedBody))

    companion object {
        /** The padded plaintext sizes: 512 B, 2 KiB, 8 KiB, 16 KiB. */
        val SIZE_CLASSES = listOf(512, 2048, 8192, 16384)
    }
}

/** One served row, decoded from base64 but not opened. */
class ServedRevision(
    val entryId: String,
    val revision: Long,
    val sealed: SealedRevision,
    /** When the server stored it, epoch ms. */
    val createdAt: Long,
    /** `user:{id}` or `share:{identityId}` on a member's read. */
    val writer: String? = null,
)

/** One revision as held: the row, its body's digest, and what it opened to — or why it did not. */
class HeldRevision(
    val entryId: String,
    val revision: Long,
    val createdAt: Long,
    val digest: String,
    val body: RevisionBody?,
    val unreadable: RevisionUnreadable.Reason?,
    val writer: String? = null,
) {
    /** The one id of a revision in a Pot's list. */
    val id: String get() = "$entryId:$revision"
}

object Revisions {
    /** Opens every served row of a Pot with its key; a row that does not open is held as unreadable, never dropped. */
    fun open(codec: RevisionCodec, scopeId: String, nodeId: String, potKey: ByteArray, served: List<ServedRevision>): List<HeldRevision> =
        served.map { row ->
            val digest = codec.digest(row.sealed.sealedBody)
            try {
                val body = codec.open(RevisionAddress(scopeId, nodeId, row.entryId, row.revision), potKey, row.sealed)
                HeldRevision(row.entryId, row.revision, row.createdAt, digest, body, null, row.writer)
            } catch (e: RevisionUnreadable) {
                HeldRevision(row.entryId, row.revision, row.createdAt, digest, null, e.reason, row.writer)
            }
        }

    /** The held revisions by record, each record's ascending, the records in the order they first appear. */
    fun byRecord(held: List<HeldRevision>): Map<String, List<HeldRevision>> {
        val records = LinkedHashMap<String, MutableList<HeldRevision>>()
        for (r in held) records.getOrPut(r.entryId) { mutableListOf() } += r
        return records.mapValues { (_, list) -> list.sortedBy { it.revision } }
    }

    /** Every held revision no held revision names as a head. */
    fun currentHeads(held: List<HeldRevision>): List<HeldRevision> {
        val named = held.flatMap { r -> r.body?.heads.orEmpty().map { "${it.entryId}:${it.revision}" } }.toSet()
        return held.filter { it.id !in named }
    }

    /** The heads a revision written now names: the current heads, the [RevisionFormat.MAX_HEADS] oldest by the time the
     *  server stored them (then entry id, then revision). */
    fun headsToName(held: List<HeldRevision>): List<Head> =
        currentHeads(held)
            .sortedWith(compareBy<HeldRevision> { it.createdAt }.thenBy { it.entryId }.thenBy { it.revision })
            .take(RevisionFormat.MAX_HEADS)
            .map { Head(it.entryId, it.revision, it.digest) }
}
