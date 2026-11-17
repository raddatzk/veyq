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
    fun homeAtAFolderReadsItsPathFromTheTree() {
        val tree = listOf(
            NodeRef("s", "a", null, NodeRef.FOLDER, "Reisen"),
            NodeRef("s", "b", "a", NodeRef.FOLDER, "Rom renamed"),
            NodeRef("s", "p", "b", "EXPENSES_POT", "Pot"),
        )
        val shell = Shell(null, space = "s").enter(FolderRef("b", "Rom"))
        assertEquals(listOf(FolderRef("a", "Reisen"), FolderRef("b", "Rom renamed")), shell.at(tree, "b").path)
        assertTrue(shell.at(tree, null).path.isEmpty())
        // a folder the tree does not hold ends the path there
        assertTrue(shell.at(tree, "gone").path.isEmpty())
        // a served cycle ends instead of spinning
        val cycle = listOf(NodeRef("s", "x", "y", NodeRef.FOLDER, "X"), NodeRef("s", "y", "x", NodeRef.FOLDER, "Y"))
        assertEquals(2, shell.at(cycle, "x").path.size)
        assertEquals(NodeAt("s", "b", NodeRef.FOLDER, "Rom"), shell.folderAt("s"))
    }

    @Test
    fun choosingInTheDrawerClosesTheObjectAndSearch() {
        val s = Shell.start(pot, "s").search(true).choose(ApplicationId.DEPOT)
        assertNull(s.openObject)
        assertEquals(false, s.searching)
    }

    // ---- a person's profile (ADR 0144 decision 11) ----

    @Test
    fun aProfileOpenedFromAMemorysParticipantsReturnsToTheMemory() {
        val onMemory = Shell(ApplicationId.MEMORIES, space = "s").open(memory)
        val profile = onMemory.openPerson("u-ada")
        assertEquals("u-ada", profile.person)
        assertNull(profile.openObject, "the profile is a screen of its own")
        assertTrue(profile.barTabs.isEmpty(), "no application's bar on a profile")
        assertFalse(profile.isHome)
        assertTrue(profile.canGoBack)
        assertEquals(onMemory, profile.goBack(), "back returns to the Memory it was opened from")
        assertEquals(onMemory, profile.close())
    }

    /** The lightbox is the open Memory's: a photo's uploader opens the profile over the Memory, back returns to it. */
    @Test
    fun aProfileOpenedFromAPhotosUploaderReturnsThere() {
        val fromHome = Shell(null, space = "s").enter(FolderRef("a", "Reisen")).open(memory)
        val profile = fromHome.openPerson("u-ben")
        assertEquals(fromHome, profile.goBack())
        assertEquals(Shell(null, space = "s").enter(FolderRef("a", "Reisen")), profile.goBack().goBack(), "and the Memory's own back is kept")
        // a Memory chosen at the top of a wide window keeps the profile's back there too
        val top = Shell(ApplicationId.MEMORIES, space = "s").openAtTop(memory)
        assertEquals(top, top.openPerson("u-ben").goBack())
    }

    @Test
    fun aMemoryOpenedFromAProfileReturnsToTheProfileAndTheProfileToWhereItWasOpened() {
        val onMemory = Shell(ApplicationId.MEMORIES, space = "s").open(memory)
        val profile = onMemory.openPerson("u-ada")
        val other = ObjectRef("g", "m2", "MEMORY", ApplicationId.MEMORIES, "Harz")
        val opened = profile.open(other)
        assertEquals(other, opened.openObject)
        assertNull(opened.person, "the Memory takes the profile's place")
        assertEquals(profile, opened.goBack(), "back from the Memory is the profile")
        assertEquals(onMemory, opened.goBack().goBack(), "and back from the profile the Memory it was opened from")
        // the list beside a profile on a wide window opens over it the same way
        assertEquals(profile, profile.openBeside(other).goBack())
    }

    @Test
    fun theDrawerAndASpaceSwitchLeaveAProfile() {
        val profile = Shell(ApplicationId.MEMORIES, space = "s").open(memory).openPerson("u-ada")
        assertNull(profile.choose(ApplicationId.EXPENSES).person)
        assertNull(profile.switchSpace("t").person)
        assertEquals("u-ada", profile.search(true).person, "Search over a profile keeps it beneath")
    }

    @Test
    fun onlyMemoriesClaimsASectionOnAProfile() {
        assertEquals(listOf(ApplicationId.MEMORIES), personSections(emptySet()))
        assertEquals(emptyList(), personSections(setOf("memories")), "an application switched off adds no section")
    }
}
