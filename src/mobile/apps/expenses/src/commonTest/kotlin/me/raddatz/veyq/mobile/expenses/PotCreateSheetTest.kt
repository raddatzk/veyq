package me.raddatz.veyq.mobile.expenses

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.FolderRef
import me.raddatz.veyq.mobile.navigation.Shell
import me.raddatz.veyq.mobile.navigation.SpaceRef
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "New pot" both apps drew (the web's `PotCreateDialog.vue`), now core's: its defaults, its enable rule, its members read
 * again when the space changes, and where the Pot lands — over a door the test plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PotCreateSheetTest {
    private val home = SpaceRef("home", "PERSONAL", "Home")
    private val club = SpaceRef("club", "SHARED", "Club")
    private val anna = Person("u-anna", "anna", "Anna")
    private val ben = Person("u-ben", "ben", "Ben")
    private val cleo = Person("u-cleo", "cleo", "Cleo")

    private class Created(val scopeId: String, val parentId: String?, val name: String, val base: Ledger.BaseCurrency, val people: List<String>)

    private class Door : PotCreateSheet.Door {
        val membersOf = mutableMapOf<String, List<Person>>()
        val asked = mutableListOf<String>()
        /** A space whose members' answer the test holds back. */
        val held = mutableMapOf<String, CompletableDeferred<Unit>>()
        val created = mutableListOf<Created>()
        var outcome = PotCreate.Outcome.CREATED

        override suspend fun members(scopeId: String): List<Person>? {
            asked += scopeId
            held[scopeId]?.await()
            return membersOf[scopeId]
        }

        override suspend fun create(scopeId: String, parentId: String?, name: String, base: Ledger.BaseCurrency, people: List<PotCreate.NewPerson>): PotCreate.Result {
            created += Created(scopeId, parentId, name, base, people.map { it.userId ?: it.name })
            return PotCreate.Result(outcome, if (outcome == PotCreate.Outcome.CREATED || outcome == PotCreate.Outcome.PEOPLE_NOT_ADDED) "new" else null)
        }
    }

    private fun TestScope.sheet(door: Door, place: Shell, language: String = "en", spaces: List<SpaceRef> = listOf(home, club)) =
        PotCreateSheet.open(door, place, spaces, language, backgroundScope, UnconfinedTestDispatcher(testScheduler))

    private val atHomeRoot = Shell(application = null, space = "home")
    private val inAFolderOfClub = Shell(application = null, space = "club", path = listOf(FolderRef("f-trips", "Trips")))

    @Test
    fun itStartsOnTheSpaceShownInTheLanguagesCurrency() = runTest {
        val door = Door()
        val s = sheet(door, inAFolderOfClub, language = "de")
        assertEquals("club", s.current.scopeId)
        assertEquals("EUR", s.current.currency)
        assertEquals("USD", sheet(Door(), atHomeRoot, language = "en").current.currency)
        // a desktop hands its locale's tag: the language is its first part
        assertEquals("EUR", sheet(Door(), atHomeRoot, language = "de-DE").current.currency)
        assertEquals("EUR", sheet(Door(), atHomeRoot, language = "de_DE.UTF-8").current.currency)
        assertEquals("USD", sheet(Door(), atHomeRoot, language = "en-US").current.currency)
        // no space shown: the first
        assertEquals("home", sheet(Door(), Shell(application = null)).current.scopeId)
    }

    @Test
    fun itMayCreateOnlyWithANameASpaceAndNoCreateRunning() = runTest {
        val door = Door()
        val s = sheet(door, atHomeRoot)
        assertFalse(s.canCreate(""))
        assertFalse(s.canCreate("   "))
        assertTrue(s.canCreate("Rome"))
        val none = sheet(Door(), Shell(application = null), spaces = emptyList())
        assertNull(none.current.scopeId)
        assertFalse(none.canCreate("Rome"), "a Pot without a space")
        assertFalse(none.create("Rome"))
        assertTrue(door.created.isEmpty())
    }

    @Test
    fun theMembersAreReadAgainWhenTheSpaceChangesAndAnotherSpacesMemberGoes() = runTest {
        val door = Door().apply {
            membersOf["home"] = listOf(anna, ben)
            membersOf["club"] = listOf(anna, cleo)
        }
        val s = sheet(door, atHomeRoot)
        runCurrent()
        assertEquals(listOf("home"), door.asked)
        assertEquals(listOf(anna, ben), s.current.offered)
        s.addMember(ben.userId)
        s.addMember(anna.userId)
        assertTrue(s.addTyped("Dora"))
        assertEquals(listOf(ben.userId, anna.userId, null), s.current.people.map { it.userId })
        assertEquals(emptyList(), s.current.offered)
        s.space("club")
        runCurrent()
        assertEquals(listOf("home", "club"), door.asked)
        // Ben is no member of the club: he goes; Anna is one and stays; a name typed stays
        assertEquals(listOf(anna.userId, null), s.current.people.map { it.userId })
        assertEquals(listOf(cleo), s.current.offered)
    }

    @Test
    fun anAnswerForASpaceLeftSinceIsDropped() = runTest {
        val door = Door().apply {
            membersOf["home"] = listOf(anna, ben)
            membersOf["club"] = listOf(cleo)
            held["home"] = CompletableDeferred()
        }
        val s = sheet(door, atHomeRoot)
        runCurrent()
        s.space("club")
        runCurrent()
        assertEquals(listOf(cleo), s.current.offered)
        door.held.getValue("home").complete(Unit)
        runCurrent()
        assertEquals(listOf(cleo), s.current.offered, "home's late answer was taken")
    }

    @Test
    fun aPotLandsInTheFolderShownOnlyInTheSpaceShown() = runTest {
        val door = Door()
        val s = sheet(door, inAFolderOfClub)
        assertTrue(s.create("Rome"))
        assertEquals("f-trips", door.created.last().parentId)
        assertEquals("club", door.created.last().scopeId)
        val t = sheet(door, inAFolderOfClub)
        t.space("home")
        assertTrue(t.create("Paris"))
        assertNull(door.created.last().parentId, "a Pot in another space than the one shown landed in its folder")
        assertTrue(t.current.done)
    }

    @Test
    fun aNameStillTypedJoinsAndTheCurrencysDigitsAreTheCores() = runTest {
        val door = Door()
        val s = sheet(door, atHomeRoot)
        s.currency("JPY")
        assertTrue(s.create("Tokyo", typed = "Eve"))
        assertEquals(listOf("Eve"), door.created.last().people)
        assertEquals(Ledger.BaseCurrency("JPY", 0), door.created.last().base)
    }

    @Test
    fun aRefusalIsSaidAndAPotWithoutItsPeopleExistsAndSaysSo() = runTest {
        val door = Door().apply { outcome = PotCreate.Outcome.NAME_TAKEN }
        val s = sheet(door, atHomeRoot)
        assertFalse(s.create("Rome"))
        assertEquals(Notice(Msg.CREATE_POT_NAME_TAKEN), s.current.notice)
        assertFalse(s.current.done)
        door.outcome = PotCreate.Outcome.PEOPLE_NOT_ADDED
        assertTrue(s.create("Rome 2"))
        assertEquals(Notice(Msg.CREATE_POT_PEOPLE_FAILED), s.current.notice)
        assertTrue(s.current.done)
    }
}
