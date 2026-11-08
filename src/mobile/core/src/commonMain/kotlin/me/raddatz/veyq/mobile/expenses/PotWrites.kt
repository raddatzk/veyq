package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.api.expenses.models.PotErasureRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionWrite
import me.raddatz.veyq.mobile.crypto.b64

/**
 * One revision a writer means to append: the next revision of [entryId] — of its latest held one, or of [basedOn] when
 * the writer edits the revision it was shown (so a concurrent revision is a `409`, never overwritten unseen).
 * [droppedReceipts] names the receipts this revision stops listing.
 */
class PotWrite(
    val entryId: String,
    val content: RevisionContent,
    val basedOn: Long? = null,
    val droppedReceipts: List<String> = emptyList(),
)

/** A write the client refuses before sealing anything: the server cannot see the rule it breaks. */
class PotWriteRefused(val reason: Reason) : Exception("pot_write_refused: $reason") {
    enum class Reason {
        EMPTY,
        RECORD_TWICE,
        ERASURE_THROUGH_APPEND,
        RECEIPT_DROPPED_AND_KEPT,
        /** A money entry's sums do not add up, or it names someone who is no participant ([Ledger.entryProblem]). */
        ENTRY_INVALID,
        NOT_A_PARTICIPANT,
        JOINED_MOVED,
        KIND_CHANGED,
        NOT_SETTLED,
        MEMBER_TWICE,
    }
}

/**
 * The sealing half of a Pot's writes, a port of `sealPotWrites` and `eraseParticipant` in
 * `src/web/src/features/expenses/domain/potApi.ts`: pure but for the randomness of the seals, so the rules are tested
 * without a server.
 */
object PotWrites {
    private fun latestOf(held: List<HeldRevision>): Map<String, Long> =
        Revisions.byRecord(held).mapValues { (_, revs) -> revs.last().revision }

    private fun isSettled(ledger: Ledger.Pot, id: String) = (ledger.balances[id] ?: Big.ZERO).signum == 0

    /**
     * Seals a batch on what is held: each record's revision `n + 1`, every revision naming the heads the writer read.
     * Refuses ([PotWriteRefused]) before sealing anything: two writes of one record, an erasure, a revision that keeps a
     * receipt it drops, a money entry whose sums do not add up or that names someone who is neither a participant nor one
     * this batch adds, a participant whose join time moved, whose kind changed, who is deactivated while unsettled, and a
     * member added twice.
     */
    fun seal(
        codec: RevisionCodec, scopeId: String, nodeId: String, potKey: ByteArray,
        held: List<HeldRevision>, writes: List<PotWrite>, base: Ledger.BaseCurrency,
    ): List<PotRevisionWrite> {
        fun refuse(r: PotWriteRefused.Reason): Nothing = throw PotWriteRefused(r)
        if (writes.isEmpty()) refuse(PotWriteRefused.Reason.EMPTY)
        if (writes.map { it.entryId }.toSet().size != writes.size) refuse(PotWriteRefused.Reason.RECORD_TWICE)
        if (writes.any { it.content is Erasure }) refuse(PotWriteRefused.Reason.ERASURE_THROUGH_APPEND)
        for (w in writes) {
            val kept = (w.content as? MoneyContent)?.receipts.orEmpty().toSet()
            if (w.droppedReceipts.any { it in kept }) refuse(PotWriteRefused.Reason.RECEIPT_DROPPED_AND_KEPT)
        }
        val ledger = Ledger.build(held, base)
        val known = ledger.participants.associateBy { it.id }
        val people = known.keys + writes.filter { it.content is ParticipantBody }.map { it.entryId }
        for (w in writes) {
            when (val c = w.content) {
                is MoneyContent -> if (Ledger.entryProblem(c) { it in people } != null) refuse(PotWriteRefused.Reason.ENTRY_INVALID)
                is ParticipantBody -> {
                    // A participant keeps its join order for life (the rounding's tie-break), never changes kind, and is
                    // deactivated only when settled.
                    val before = known[w.entryId] ?: continue
                    if (before.erased) refuse(PotWriteRefused.Reason.NOT_A_PARTICIPANT)
                    if (before.joined != c.joined) refuse(PotWriteRefused.Reason.JOINED_MOVED)
                    if (before.member != c.member) refuse(PotWriteRefused.Reason.KIND_CHANGED)
                    if (before.active && !c.active && !isSettled(ledger, w.entryId)) refuse(PotWriteRefused.Reason.NOT_SETTLED)
                }
                else -> {}
            }
        }
        // A member is one participant at most; an erased record names no one (its participant carries no member,
        // [Ledger.build]), so the member may be added again.
        val members = ledger.participants.mapNotNull { it.member }.toMutableSet()
        for (w in writes) {
            val c = w.content as? ParticipantBody ?: continue
            val member = c.member ?: continue
            if (w.entryId in known) continue
            if (!members.add(member)) refuse(PotWriteRefused.Reason.MEMBER_TWICE)
        }
        val latest = latestOf(held)
        val heads = Revisions.headsToName(held)
        return writes.map { w ->
            val revision = (w.basedOn ?: latest[w.entryId] ?: 0) + 1
            val sealed = codec.seal(RevisionAddress(scopeId, nodeId, w.entryId, revision), potKey, RevisionBody(heads, w.content))
            PotRevisionWrite(
                w.entryId, revision, w.content == Deleted, b64(sealed.sealedBody), b64(sealed.wrappedRevisionKey),
                w.droppedReceipts.takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * A participant's sealed erasure revision `n + 1` (ADR 0108 decisions 3 and 6), after which the server holds none of
     * the record's earlier revisions; it keeps the join order and no name. Refused unless the record is a participant not
     * yet erased whose balance is settled.
     */
    fun erasure(
        codec: RevisionCodec, scopeId: String, nodeId: String, potKey: ByteArray, nodeEpoch: Int,
        held: List<HeldRevision>, participantId: String, base: Ledger.BaseCurrency,
    ): PotErasureRequest {
        val ledger = Ledger.build(held, base)
        val person = ledger.participants.firstOrNull { it.id == participantId }
        if (person == null || person.erased) throw PotWriteRefused(PotWriteRefused.Reason.NOT_A_PARTICIPANT)
        if (!isSettled(ledger, participantId)) throw PotWriteRefused(PotWriteRefused.Reason.NOT_SETTLED)
        val revision = (latestOf(held)[participantId] ?: 0) + 1
        val sealed = codec.seal(
            RevisionAddress(scopeId, nodeId, participantId, revision), potKey,
            RevisionBody(Revisions.headsToName(held), Erasure(person.joined)),
        )
        return PotErasureRequest(nodeEpoch, participantId, revision, b64(sealed.sealedBody), b64(sealed.wrappedRevisionKey))
    }
}
