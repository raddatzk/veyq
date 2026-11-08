package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.session.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The people panel's rules (the web's `PeoplePanel.vue` and `people.ts`): how a participant reads (a member by the
 * directory's current name, a former member by the sealed one), and what may be done to whom — each rule beside the
 * case that shows its other side.
 */
class PotViewPeopleTest {
    private val anna = "0e000000-0000-4000-8000-000000000001"
    private val ben = "0e000000-0000-4000-8000-000000000002"
    private val cleo = "0e000000-0000-4000-8000-000000000003"
    private val dora = "0e000000-0000-4000-8000-000000000004"
    private val eve = "0e000000-0000-4000-8000-000000000005"
    private val dinner = "0e000000-0000-4000-9000-000000000001"
    private val userA = "6e000000-0000-4000-8000-000000000001"
    private val userC = "6e000000-0000-4000-8000-000000000003"
    private val userX = "6e000000-0000-4000-8000-000000000009"
    private val base = Ledger.BaseCurrency("EUR", 2)

    private fun view(revisions: List<Pair<String, RevisionContent>>, members: List<Person>?, canWrite: Boolean = true): PotView {
        var t = 0L
        val counts = HashMap<String, Long>()
        val held = revisions.map { (id, c) ->
            val n = (counts[id] ?: 0) + 1
            counts[id] = n
            HeldRevision(id, n, t++, "", RevisionBody(emptyList(), c), null)
        }
        val ledger = Ledger.build(held, base)
        val named = held.flatMap { r -> (r.body?.content as? MoneyContent)?.let(Ledger::namedParticipants).orEmpty() }.toSet()
        return PotView(PotView.Source("Trip", PotMeta(base, emptyList()), ledger, LedgerCheck.of(held), members, named, canWrite))
    }

    private fun expense(amount: Long, payer: String, people: List<String>) =
        Expense("Dinner", "food", "2026-07-01", "EUR", 2, "1", amount, emptyList(), listOf(Contribution(payer, amount)), Allocation(emptyList(), Split.Equal(people)))

    private val pot = listOf(
        anna to ParticipantBody("Anna (as added)", true, 1, userA),
        ben to ParticipantBody("Ben", true, 2),
        cleo to ParticipantBody("Cleo (as added)", true, 3, userC),
        dora to ParticipantBody("Dora", false, 4),
        eve to ParticipantBody("Eve", true, 5),
        dinner to expense(300, anna, listOf(anna, ben, eve)),
        eve to Erasure(5),
    )

    private val directory = listOf(Person(userA, "anna", "Anna Adler"), Person(userX, "xavier", "Xavier"))

    private fun transfer(amount: Long, from: String, to: String) = Transfer(null, "2026-07-02", "EUR", 2, "1", amount, emptyList(), from, to)

    @Test
    fun aBalanceRowSaysWhatTransfersMovedAndTheErasedAreOneRowWhileTheyHaveCosts() {
        val v = view(
            listOf(
                anna to ParticipantBody("Anna", true, 1),
                ben to ParticipantBody("Ben", true, 2),
                dora to ParticipantBody("Dora", false, 3),
                eve to ParticipantBody("Eve", true, 4),
                dinner to expense(300, anna, listOf(anna, ben, eve)),
                cleo to transfer(100, ben, anna),
                eve to Erasure(4),
            ),
            null,
        )
        // Anna paid 300, consumed 100 and received 100; Ben consumed 100 and sent 100 (settled, still shown: active);
        // Dora, deactivated with nothing, is left out; Eve's erased share is the one "erased" row.
        assertEquals(listOf(anna, ben, "erased"), v.balances.map { it.id })
        val (a, b, e) = v.balances
        assertEquals(listOf(300L, 100L, 100L, -100L), listOf(a.paid, a.share, a.balance, a.settled))
        assertEquals(listOf(0L, 100L, 0L, 100L), listOf(b.paid, b.share, b.balance, b.settled))
        assertEquals(listOf(0L, 100L, -100L, 0L), listOf(e.paid, e.share, e.balance, e.settled))
        // An erased person whose balance is zero but who has costs is still shown, summed.
        val settledErased = view(
            listOf(
                anna to ParticipantBody("Anna", true, 1),
                eve to ParticipantBody("Eve", true, 2),
                dinner to expense(200, eve, listOf(anna, eve)),
                cleo to transfer(100, anna, eve),
                eve to Erasure(2),
            ),
            null,
        ).balances.single { it.id == "erased" }
        assertEquals(listOf(200L, 100L, 0L, -100L), listOf(settledErased.paid, settledErased.share, settledErased.balance, settledErased.settled))
        // A deactivated person stays while they have costs (or a balance), only one with nothing leaves.
        val deactivated = view(
            listOf(
                anna to ParticipantBody("Anna", true, 1),
                ben to ParticipantBody("Ben", true, 2),
                dinner to expense(200, anna, listOf(anna, ben)),
                cleo to transfer(100, ben, anna),
                ben to ParticipantBody("Ben", false, 2),
            ),
            null,
        ).balances
        assertEquals(listOf(anna, ben), deactivated.map { it.id })
        assertEquals(false to 0L, deactivated[1].active to deactivated[1].balance)
        // Nobody erased with anything: no such row.
        assertTrue(view(listOf(anna to ParticipantBody("Anna", true, 1), eve to ParticipantBody("Eve", true, 2), eve to Erasure(2)), null).balances.none { it.id == "erased" })
    }

    @Test
    fun aMemberReadsByTheCurrentNameAndAFormerOneByTheSealed() {
        val v = view(pot, directory)
        val byId = v.personEntries.associateBy { it.id }
        assertEquals("Anna Adler", byId.getValue(anna).name)
        assertEquals(PotView.Standing.MEMBER, byId.getValue(anna).standing)
        assertEquals("Cleo (as added)", byId.getValue(cleo).name)
        assertEquals(PotView.Standing.FORMER, byId.getValue(cleo).standing)
        assertEquals(PotView.Standing.NAME, byId.getValue(ben).standing)
        // Everywhere a name shows, the same rule.
        assertTrue(v.people.any { it.name == "Anna Adler" })
        assertEquals("Anna Adler", v.balances.first { it.id == anna }.name)
        // Without a directory a member is sealed, by the name sealed at adding.
        val sealed = view(pot, null).personEntries.first { it.id == anna }
        assertEquals(PotView.Standing.SEALED, sealed.standing)
        assertEquals("Anna (as added)", sealed.name)
    }

    @Test
    fun anErasedPersonIsNoRowAndCountsOnlyWhileAnEntryNamesThem() {
        val v = view(pot, directory)
        assertFalse(v.personEntries.any { it.id == eve })
        assertEquals(1, v.deletedPeople)
        // Erased and named nowhere: removed, not counted.
        assertEquals(0, view(pot.filterNot { it.first == dinner }, directory).deletedPeople)
    }

    @Test
    fun onlyANameIsRenamed() {
        val byId = view(pot, directory).personEntries.associateBy { it.id }
        assertTrue(byId.getValue(ben).renamable)
        assertFalse(byId.getValue(anna).renamable)
        assertFalse(byId.getValue(cleo).renamable)
    }

    @Test
    fun anUnsettledPersonIsNeitherDeactivatedNorDeletedButMayBeActivated() {
        val byId = view(pot, directory).personEntries.associateBy { it.id }
        // Anna paid 3.00 for three: she is owed, Ben owes.
        assertFalse(byId.getValue(anna).settled)
        assertFalse(byId.getValue(anna).canToggle)
        assertFalse(byId.getValue(anna).canErase)
        assertFalse(byId.getValue(ben).canToggle)
        // Cleo is settled: deactivate and remove (no entry names her).
        assertTrue(byId.getValue(cleo).canToggle)
        assertTrue(byId.getValue(cleo).canErase)
        assertFalse(byId.getValue(cleo).named)
        // Dora is deactivated and settled: activate.
        assertTrue(byId.getValue(dora).canToggle)
        assertTrue(view(pot, directory).anyUnsettled)
        // A deactivated person with a balance may still be activated again.
        val owing = view(pot + (ben to ParticipantBody("Ben", false, 2)), directory).personEntries.first { it.id == ben }
        assertFalse(owing.settled)
        assertTrue(owing.canToggle)
        assertFalse(owing.canErase)
    }

    @Test
    fun aPersonAnEntryNamesIsDeletedNotRemoved() {
        val settled = pot + (dinner to expense(300, anna, listOf(anna)))
        val byId = view(settled, directory).personEntries.associateBy { it.id }
        assertTrue(byId.getValue(ben).named)
        assertTrue(byId.getValue(ben).canErase)
    }

    @Test
    fun withoutWriteNothingIsOffered() {
        val v = view(pot, directory, canWrite = false)
        assertTrue(v.personEntries.none { it.renamable || it.canToggle || it.canErase })
    }

    @Test
    fun aMemberWhoIsAParticipantIsNotOfferedAgain() {
        val v = view(pot, directory)
        assertEquals(listOf(userX), v.addableMembers.map { it.userId })
        // Once Anna's record is erased, she may be added again.
        val erased = view(pot.filterNot { it.first == dinner } + (anna to Erasure(1)), directory)
        assertEquals(listOf(userA, userX), erased.addableMembers.map { it.userId })
    }
}
