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
