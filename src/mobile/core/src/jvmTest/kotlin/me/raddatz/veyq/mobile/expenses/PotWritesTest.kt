package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.crypto.BouncyCastlePrimitives
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ledger check and the write rules over real seals, ports of the web's `heads.test.ts` and the rule cases of
 * `potApi.wire.test.ts`: each refusal beside the control that is sealed, so a rule removed turns a case red.
 */
class PotWritesTest {
    private val suite = V1(BouncyCastlePrimitives)
    private val codec = RevisionCodec(suite)
    private val scope = "5c000000-0000-4000-8000-000000000001"
    private val pot = "5c000000-0000-4000-8000-000000000002"
    private val key = ByteArray(32) { it.toByte() }
    private val base = Ledger.BaseCurrency("EUR", 2)

    private val anna = "0e000000-0000-4000-8000-000000000001"
    private val ben = "0e000000-0000-4000-8000-000000000002"
    private val dinner = "0e000000-0000-4000-9000-000000000001"
    private val taxi = "0e000000-0000-4000-9000-000000000002"

    private fun expense(amount: Long, payer: String = anna, people: List<String> = listOf(anna, ben), receipts: List<String> = emptyList()) =
        Expense("Dinner", "food", "2026-07-01", "EUR", 2, "1", amount, receipts, listOf(Contribution(payer, amount)), Allocation(emptyList(), Split.Equal(people)))

    /** A Pot written step by step: each revision sealed at its address and naming the heads before it, as a writer does. */
    private inner class Pot {
        val held = mutableListOf<HeldRevision>()
        private var t = 0L

        fun add(
            entryId: String, content: RevisionContent,
            revision: Long = (held.filter { it.entryId == entryId }.maxOfOrNull { it.revision } ?: 0) + 1,
            heads: List<Head> = Revisions.headsToName(held),
        ): Pot {
            val sealed = codec.seal(RevisionAddress(scope, pot, entryId, revision), key, RevisionBody(heads, content))
            held += Revisions.open(codec, scope, pot, key, listOf(ServedRevision(entryId, revision, sealed, t++)))
            return this
        }

        fun without(entryId: String, revision: Long) = held.filterNot { it.entryId == entryId && it.revision == revision }
    }

    private fun people() = Pot().add(anna, ParticipantBody("Anna", true, 1)).add(ben, ParticipantBody("Ben", true, 2))

    // The ledger check.

    @Test
    fun aFullListPasses() {
        val p = people().add(dinner, expense(300)).add(taxi, expense(100, ben))
        assertTrue(LedgerCheck.of(p.held).complete)
    }

    @Test
    fun aRevisionSomebodyWroteAfterIsMissing() {
        val p = people().add(dinner, expense(300)).add(taxi, expense(100, ben))
        val check = LedgerCheck.of(p.without(dinner, 1))
        assertEquals(listOf(LedgerCheck.Ref(dinner, 1)), check.missing)
        assertEquals(listOf(dinner), check.entries)
    }

    @Test
    fun aHoleInARecordIsMissingWhereNothingNamesItAnyMore() {
        // Revision 2 names no head (only the hole rule can see revision 1 gone).
        val p = people().add(dinner, expense(300)).add(dinner, expense(400), heads = emptyList())
        val check = LedgerCheck.of(p.without(dinner, 1))
        assertEquals(listOf(LedgerCheck.Ref(dinner, 1)), check.missing)
    }

    @Test
    fun theNewestRevisionHeldBackIsNotSeen() {
        val p = people().add(dinner, expense(300)).add(taxi, expense(100, ben))
        // The freshness limit, stated: nothing names the newest revision yet.
        assertTrue(LedgerCheck.of(p.without(taxi, 1)).complete)
    }

    @Test
    fun anErasureCoversOnlyItsOwnRecordsEarlierRevisions() {
        val p = people().add(ben, ParticipantBody("Ben B.", true, 2)).add(ben, Erasure(2))
            .add(anna, ParticipantBody("Anna A.", true, 1)).add(dinner, expense(300, anna, listOf(anna)))
        // The server holds none of Ben's earlier revisions after his erasure.
        val served = p.held.filterNot { it.entryId == ben && it.revision < 3 }
        assertTrue(LedgerCheck.of(served).complete)
        // Anna's revision 2 is below Ben's erasure by number, but no erasure of HER record covers it: the dinner names it.
        assertEquals(listOf(LedgerCheck.Ref(anna, 2)), LedgerCheck.of(served.filterNot { it.entryId == anna && it.revision == 2L }).missing)
        // The erasure itself is named by Anna's rename, and nothing covers it.
        assertEquals(listOf(LedgerCheck.Ref(ben, 3)), LedgerCheck.of(served.filterNot { it.entryId == ben }).missing)
    }

    @Test
    fun aServerMadeErasureCoversNothing() {
        val p = people().add(ben, ParticipantBody("Ben B.", true, 2)).add(dinner, expense(300, anna, listOf(anna)))
        // A row the server claims is an erasure but that does not open as one: it is unreadable and covers nothing.
        val forged = HeldRevision(ben, 3, 99, "x".repeat(43), null, RevisionUnreadable.Reason.KEY)
        val check = LedgerCheck.of(p.held.filterNot { it.entryId == ben } + forged)
        assertTrue(LedgerCheck.Ref(ben, 1) in check.missing)
        assertTrue(LedgerCheck.Ref(ben, 2) in check.missing)
        assertEquals(listOf(LedgerCheck.Ref(ben, 3)), check.unreadable)
    }

    @Test
    fun aSwappedBodyIsAltered() {
        val p = people().add(dinner, expense(300)).add(taxi, expense(100, ben))
        val swapped = codec.seal(RevisionAddress(scope, pot, dinner, 1), key, RevisionBody(emptyList(), expense(999)))
        val other = Revisions.open(codec, scope, pot, key, listOf(ServedRevision(dinner, 1, swapped, 0)))
        val check = LedgerCheck.of(p.without(dinner, 1) + other)
        assertEquals(listOf(LedgerCheck.Ref(dinner, 1)), check.altered)
        assertTrue(check.missing.isEmpty())
    }

    @Test
    fun aRevisionMovedToAnotherAddressIsUnreadableAndItsPlaceMissing() {
        val p = people().add(dinner, expense(300)).add(taxi, expense(100, ben))
        val moved = p.held.first { it.entryId == dinner }
        val sealed = codec.seal(RevisionAddress(scope, pot, dinner, 1), key, moved.body!!)
        val served = Revisions.open(codec, scope, pot, key, listOf(ServedRevision(dinner, 2, sealed, 0)))
        val check = LedgerCheck.of(p.without(dinner, 1) + served)
        assertEquals(listOf(LedgerCheck.Ref(dinner, 2)), check.unreadable)
        assertEquals(listOf(LedgerCheck.Ref(dinner, 1)), check.missing)
    }

    // The writes.

    private fun seal(p: Pot, vararg writes: PotWrite) = PotWrites.seal(codec, scope, pot, key, p.held, writes.toList(), base)

    private fun refused(p: Pot, vararg writes: PotWrite): PotWriteRefused.Reason =
        assertFailsWith<PotWriteRefused> { seal(p, *writes) }.reason

    @Test
    fun aWriteIsRevisionNPlusOneNamingTheHeadsItRead() {
        val p = people().add(dinner, expense(300))
        val sent = seal(p, PotWrite(dinner, expense(400)), PotWrite(taxi, expense(100, ben)))
        assertEquals(listOf(2L, 1L), sent.map { it.revision })
        val body = codec.open(RevisionAddress(scope, pot, dinner, 2), key, SealedRevision(unb64(sent[0].sealedBody), unb64(sent[0].wrappedRevisionKey)))
        assertEquals(Revisions.headsToName(p.held), body.heads)
        assertEquals(expense(400), body.content)
        assertFalse(sent[0].tombstone!!)
    }

    @Test
    fun anEditOnTheRevisionItWasShownStatesThatOne() {
        val p = people().add(dinner, expense(300)).add(dinner, expense(400))
        assertEquals(2L, seal(p, PotWrite(dinner, expense(500), basedOn = 1)).single().revision)
    }

    @Test
    fun aDeleteIsATombstone() {
        val p = people().add(dinner, expense(300))
        assertTrue(seal(p, PotWrite(dinner, Deleted)).single().tombstone!!)
    }

    @Test
    fun aRevisionNamesTheReceiptsItDropsAndNeverOneItKeeps() {
        val r1 = "0e000000-0000-4000-a000-000000000001"
        val r2 = "0e000000-0000-4000-a000-000000000002"
        val p = people().add(dinner, expense(300, receipts = listOf(r1, r2)))
        assertEquals(listOf(r1), seal(p, PotWrite(dinner, expense(300, receipts = listOf(r2)), droppedReceipts = listOf(r1))).single().droppedReceipts)
        assertNull(seal(p, PotWrite(dinner, expense(300, receipts = listOf(r1, r2)))).single().droppedReceipts)
        assertEquals(PotWriteRefused.Reason.RECEIPT_DROPPED_AND_KEPT, refused(p, PotWrite(dinner, expense(300, receipts = listOf(r1)), droppedReceipts = listOf(r1))))
    }

    @Test
    fun theBatchRules() {
        val p = people().add(dinner, expense(300))
        assertEquals(PotWriteRefused.Reason.EMPTY, refused(p))
        assertEquals(PotWriteRefused.Reason.RECORD_TWICE, refused(p, PotWrite(taxi, expense(100)), PotWrite(taxi, expense(200))))
        assertEquals(PotWriteRefused.Reason.ERASURE_THROUGH_APPEND, refused(p, PotWrite(ben, Erasure(2))))
    }

    @Test
    fun aMoneyEntryMustAddUpAndNameParticipants() {
        val p = people()
        val stranger = "0e000000-0000-4000-8000-000000000009"
        val unbalanced = Expense("Dinner", "food", "2026-07-01", "EUR", 2, "1", 300, emptyList(), listOf(Contribution(anna, 200)), Allocation(emptyList(), Split.Equal(listOf(anna))))
        assertEquals(PotWriteRefused.Reason.ENTRY_INVALID, refused(p, PotWrite(dinner, unbalanced)))
        assertEquals(PotWriteRefused.Reason.ENTRY_INVALID, refused(p, PotWrite(dinner, expense(300, people = listOf(anna, stranger)))))
        // Control: one this batch adds may be named.
        assertEquals(2, seal(p, PotWrite(stranger, ParticipantBody("Cleo", true, 3)), PotWrite(dinner, expense(300, people = listOf(anna, stranger)))).size)
    }

    @Test
    fun aParticipantKeepsItsJoinTimeAndKindAndIsDeactivatedOnlyWhenSettled() {
        val member = "6e000000-0000-4000-8000-000000000001"
        val p = Pot().add(anna, ParticipantBody("Anna", true, 1, member)).add(ben, ParticipantBody("Ben", true, 2)).add(dinner, expense(300))
        assertEquals(PotWriteRefused.Reason.JOINED_MOVED, refused(p, PotWrite(ben, ParticipantBody("Ben", true, 5))))
        assertEquals(PotWriteRefused.Reason.KIND_CHANGED, refused(p, PotWrite(anna, ParticipantBody("Anna", true, 1))))
        assertEquals(PotWriteRefused.Reason.KIND_CHANGED, refused(p, PotWrite(ben, ParticipantBody("Ben", true, 2, member))))
        assertEquals(PotWriteRefused.Reason.NOT_SETTLED, refused(p, PotWrite(ben, ParticipantBody("Ben", false, 2))))
        // Controls: a rename keeps everything else; once settled, the deactivation is sealed.
        assertEquals(1, seal(p, PotWrite(ben, ParticipantBody("Benjamin", true, 2))).size)
        val settled = p.add(taxi, Transfer(null, "2026-07-02", "EUR", 2, "1", 150, emptyList(), ben, anna))
        assertEquals(1, seal(settled, PotWrite(ben, ParticipantBody("Ben", false, 2))).size)
    }

    @Test
    fun aMemberIsOneParticipantAtMost() {
        val member = "6e000000-0000-4000-8000-000000000001"
        val p = Pot().add(anna, ParticipantBody("Anna", true, 1, member))
        val cleo = "0e000000-0000-4000-8000-000000000003"
        val dora = "0e000000-0000-4000-8000-000000000004"
        assertEquals(PotWriteRefused.Reason.MEMBER_TWICE, refused(p, PotWrite(cleo, ParticipantBody("Anna again", true, 3, member))))
        val other = "6e000000-0000-4000-8000-000000000002"
        assertEquals(
            PotWriteRefused.Reason.MEMBER_TWICE,
            refused(p, PotWrite(cleo, ParticipantBody("Cleo", true, 3, other)), PotWrite(dora, ParticipantBody("Cleo too", true, 4, other))),
        )
        // Control: once Anna's record is erased, the member may be added again.
        val erased = p.add(anna, Erasure(1))
        assertEquals(1, seal(erased, PotWrite(cleo, ParticipantBody("Anna again", true, 3, member))).size)
        assertEquals(PotWriteRefused.Reason.NOT_A_PARTICIPANT, refused(erased, PotWrite(anna, ParticipantBody("Anna", true, 1, member))))
    }

    @Test
    fun anErasureIsSealedRevisionNPlusOneOfASettledParticipant() {
        val p = people().add(ben, ParticipantBody("Ben B.", true, 2)).add(dinner, expense(300))
        fun erase(on: Pot, id: String) = PotWrites.erasure(codec, scope, pot, key, 7, on.held, id, base)
        assertEquals(PotWriteRefused.Reason.NOT_SETTLED, assertFailsWith<PotWriteRefused> { erase(p, ben) }.reason)
        assertEquals(PotWriteRefused.Reason.NOT_A_PARTICIPANT, assertFailsWith<PotWriteRefused> { erase(p, dinner) }.reason)
        val settled = p.add(taxi, Transfer(null, "2026-07-02", "EUR", 2, "1", 150, emptyList(), ben, anna))
        val request = erase(settled, ben)
        assertEquals(3L, request.revision)
        assertEquals(7, request.nodeEpoch)
        val body = codec.open(RevisionAddress(scope, pot, ben, 3), key, SealedRevision(unb64(request.sealedBody), unb64(request.wrappedRevisionKey)))
        assertEquals(Erasure(2), body.content)
        assertEquals(PotWriteRefused.Reason.NOT_A_PARTICIPANT, assertFailsWith<PotWriteRefused> { erase(settled.add(ben, Erasure(2)), ben) }.reason)
    }
}
