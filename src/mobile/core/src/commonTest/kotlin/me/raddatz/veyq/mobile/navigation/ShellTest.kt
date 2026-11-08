package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {
    private val pot = ObjectRef("s", "p", "EXPENSES_POT", ApplicationId.EXPENSES, "Sri Lanka")

    @Test
    fun opensOnTheLastUsedObject_orOnSpaces() {
        assertEquals(pot, Shell.start(pot).openObject)
        assertNull(Shell.start(null).application)
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
    fun anApplicationLevelBarIsAlwaysThere() {
        assertEquals(listOf("memories.all", "memories.map"), Shell(ApplicationId.MEMORIES).barTabs.map { it.key })
    }

    @Test
    fun choosingInTheDrawerClosesTheObjectAndSearch() {
        val s = Shell.start(pot).search(true).choose(ApplicationId.DEPOT)
        assertNull(s.openObject)
        assertEquals(false, s.searching)
    }
}
