package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {
    private val pot = ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Sri Lanka")
    private val memory = ObjectRef("s", "m", "MEMORY", ApplicationId.MEMORIES, "Ostsee 2026")

    @Test
    fun opensOnTheLastUsedObject_orOnHome() {
        assertEquals(pot, Shell.start(pot, "s").openObject)
        assertTrue(Shell.start(null, "s").isHome)
    }

    @Test
    fun anObjectLevelBarIsEmptyUntilAnObjectIsOpen() {
        val list = Shell(ApplicationId.EXPENSES)
        assertTrue(list.barTabs.isEmpty())
        val open = list.open(pot)
        assertEquals("expenses.entries", open.selectedTab?.key)
        assertTrue(open.close().barTabs.isEmpty())
    }

    @Test
    fun anApplicationLevelBarIsAlwaysThere_andHomeHasNone() {
        assertEquals(listOf("memories.all", "memories.map"), Shell(ApplicationId.MEMORIES).barTabs.map { it.key })
        assertTrue(Shell(null, space = "s").barTabs.isEmpty(), "Home's filters sit at the top; its bar is Search alone")
    }

    /** The owner's bug (2026-11-08): Expenses, then Spaces, open a Memory there, back — landed in Memories. */
    @Test
    fun backReturnsToWhereTheObjectWasOpenedFrom() {
        val home = Shell(ApplicationId.EXPENSES, space = "s").choose(null)
        val opened = home.open(memory)
        assertEquals(ApplicationId.MEMORIES, opened.application, "the object opens in its own application")
        val back = opened.close()
        assertTrue(back.isHome, "back is Home again, not Memories")
    }

    @Test
    fun backWalksBackThroughObjectsOpenedOneFromAnother() {
        val list = Shell(ApplicationId.EXPENSES)
        val first = list.open(pot)
        val second = first.open(memory)
        assertEquals(pot, second.close().openObject)
        assertEquals(list, second.close().close())
    }

    @Test
    fun anObjectTheAppOpenedOnGoesBackToItsApplicationsList() {
        val back = Shell.start(pot, "s").close()
        assertEquals(ApplicationId.EXPENSES, back.application)
        assertNull(back.openObject)
    }

    @Test
    fun backInHomeLeavesTheObjectThenWalksUpTheFolders() {
        val trips = FolderRef("f1", "Reisen")
        val y2026 = FolderRef("f2", "2026")
        val deep = Shell(null, space = "s").enter(trips).enter(y2026)
        assertEquals(y2026, deep.folder)
        val opened = deep.open(memory)
        assertTrue(opened.canGoBack)
        val closed = opened.goBack()
        assertEquals(listOf(trips, y2026), closed.path, "closing the object returns to the folder it was opened in")
        assertEquals(listOf(trips), closed.goBack().path)
        val root = closed.goBack().goBack()
        assertTrue(root.path.isEmpty())
        assertFalse(root.canGoBack, "back at a space's root leaves the app to the system")
    }

    @Test
    fun theSpaceSurvivesTheDrawer_andSwitchingSpaceStartsAtItsRoot() {
        val home = Shell(null, space = "s1").enter(FolderRef("f", "Reisen"))
        assertEquals("s1", home.choose(ApplicationId.MEMORIES).choose(null).space)
        val other = home.switchSpace("s2")
        assertEquals("s2", other.space)
        assertTrue(other.path.isEmpty())
    }

    @Test
    fun choosingInTheDrawerClosesTheObjectAndSearch() {
        val s = Shell.start(pot, "s").search(true).choose(ApplicationId.DEPOT)
        assertNull(s.openObject)
        assertEquals(false, s.searching)
    }
}
