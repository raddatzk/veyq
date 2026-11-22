package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.composition.ShellModel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

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

    /**
     * #1062: Home's path numbers a folder whose name a sibling folder shares, as the web's breadcrumb and heading do
     * (`browse.ts displayNameOf`): in creation order, among the folders only; the node a header acts on keeps the name.
     */
    @Test
    fun homesPathNumbersAFolderOfARepeatedName_andIsRelabelledOnARebuild() {
        val tree = listOf(
            NodeRef("s", "box", null, "DEPOT_DROPBOX", "Box"),
            NodeRef("s", "a1", "box", NodeRef.FOLDER, "Anna", identity = true, createdAt = 1.0),
            NodeRef("s", "a2", "box", NodeRef.FOLDER, "Anna", identity = true, createdAt = 2.0),
            NodeRef("s", "x", "a2", NodeRef.FOLDER, "Fotos", createdAt = 3.0),
            NodeRef("s", "m", "a2", "MEMORY", "Fotos", createdAt = 0.0),
        )
        val shell = Shell(null, space = "s").at(tree, "x")
        assertEquals(listOf("Anna (2)", "Fotos"), shell.path.map { it.displayName })
        assertEquals(listOf(FolderRef("a2", "Anna", "Anna (2)"), FolderRef("x", "Fotos")), shell.path)
        assertEquals(NodeAt("s", "x", NodeRef.FOLDER, "Fotos"), shell.folderAt("s"))
        assertEquals("Anna", Shell(null, space = "s").at(tree, "a1").folder?.displayName)
        // an older sibling of the name comes back (restored): the path's labels are read anew
        val restored = tree + NodeRef("s", "a0", "box", NodeRef.FOLDER, "Anna", identity = true, createdAt = 0.5)
        assertEquals(listOf("Anna (3)", "Fotos"), shell.relabelled(restored).path.map { it.displayName })
        // nothing changed: the same shell
        assertTrue(shell.relabelled(tree) === shell)
        // the first one gone: the second keeps the name
        assertEquals(listOf("Anna", "Fotos"), shell.relabelled(tree.filter { it.nodeId != "a1" }).path.map { it.displayName })
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

    // ---- a kept link's page (ADR 0150 decision 6, #1032) ----

    @Test
    fun aKeptLinksPageOpensFromItsLevelAndBackReturnsThere() {
        val link = NodeAt("s", "b", "SHARE_BOOKMARK", "Ein Link")
        val level = Shell(null, space = "s").enter(FolderRef("a", "Reisen"))
        val page = level.openBookmark(link)
        assertEquals(link, page.bookmark)
        assertEquals(link, page.headed, "the header is not the bookmark's")
        assertNull(page.openObject, "a kept link is no application's object")
        assertNull(page.application)
        assertFalse(page.isHome)
        assertTrue(page.canGoBack)
        assertTrue(page.barTabs.isEmpty())
        assertEquals(level, page.goBack(), "back does not return to the level it was opened from")
        assertEquals(level, page.leave(), "its delete does not return to the level")
        assertEquals(FolderRef("a", "Reisen").let { NodeAt("s", it.nodeId, NodeRef.FOLDER, it.name) }, level.headed)
        // the drawer leaves it
        assertNull(page.choose(ApplicationId.MEMORIES).bookmark)
    }

    @Test
    fun aSearchHitOnAKeptLinkOpensItsPageNotTheFolderAboveIt() {
        val nodes = listOf(
            NodeRef("s", "f", null, NodeRef.FOLDER, "Reisen"),
            NodeRef("s", "b", "f", "SHARE_BOOKMARK", "Ein Link"),
            NodeRef("s", "m", "f", "MEMORY", "Ostsee"),
        )
        val searching = Shell(null, space = "t").search(true)
        val (onLink, used) = ShellModel.found(searching, nodes, "s", "b")
        assertEquals(NodeAt("s", "b", "SHARE_BOOKMARK", "Ein Link"), onLink.bookmark, "a kept link's hit opened something else")
        assertEquals("s", onLink.space)
        assertNull(used, "a kept link's open was recorded as an object's use")
        assertEquals(searching, onLink.goBack())
        // an object and a folder as before
        val (onMemory, memory) = ShellModel.found(searching, nodes, "s", "m")
        assertEquals("m", onMemory.openObject?.nodeId)
        assertEquals(onMemory.openObject, memory)
        assertEquals(listOf("f"), ShellModel.found(searching, nodes, "s", "f").first.path.map { it.nodeId })
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
