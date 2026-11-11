package me.raddatz.veyq.mobile.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The shell's rules after a read and after a folder on Home's path changed, both apps' once (ADR 0127). */
class ShellModelTest {
    private val tenantSpace = SpaceRef("t", "TENANT", "Family")
    private val personal = SpaceRef("p", "PERSONAL", "Mine")
    private val pot = ObjectRef("t", "pot", "EXPENSES_POT", ApplicationId.EXPENSES, "Trip")

    private fun folder(id: String, parent: String?, name: String = id) = NodeRef("t", id, parent, NodeRef.FOLDER, name)

    @Test
    fun an_applications_list_holds_its_favorites_then_its_recent_then_the_rest() {
        val rome = ObjectRef("t", "rome", "EXPENSES_POT", ApplicationId.EXPENSES, "Rome")
        val oslo = ObjectRef("t", "oslo", "EXPENSES_POT", ApplicationId.EXPENSES, "Oslo")
        val album = ObjectRef("t", "album", "MEMORY", ApplicationId.MEMORIES, "Album")
        val objects = listOf(pot, rome, oslo, album)
        val lists = ShellModel.Lists(
            listOf(tenantSpace), emptyList(), objects, favorites = listOf(pot),
            applications = ApplicationId.entries, filters = listOf(null), drawer = ArrangedList(emptyList(), emptyList(), emptyList()),
            uses = listOf(Use(album, 30), Use(oslo, 20), Use(pot, 10)),
        )
        val expenses = lists.applicationList(ApplicationId.EXPENSES)
        assertEquals(listOf(pot), expenses.favorites)
        // its own Recent: Oslo was opened, the Memory is another application's, the favorite is listed once
        assertEquals(listOf(oslo), expenses.recent)
        assertEquals(listOf(rome), expenses.all)
        // nothing opened: no Recent, the rest by name
        val fresh = ShellModel.Lists(listOf(tenantSpace), emptyList(), objects, emptyList(), ApplicationId.entries, listOf(null), ArrangedList(emptyList(), emptyList(), emptyList()))
        assertEquals(emptyList(), fresh.applicationList(ApplicationId.EXPENSES).recent)
        assertEquals(listOf(oslo, rome, pot), fresh.applicationList(ApplicationId.EXPENSES).all)
    }

    @Test
    fun the_app_starts_on_the_object_last_used_else_on_the_default_space() {
        val onPot = ShellModel.next(null, listOf(personal, tenantSpace), pot)
        assertEquals(pot, onPot.openObject)
        assertEquals("t", onPot.space, "the default space is the tenant's")
        val home = ShellModel.next(null, listOf(personal, tenantSpace), null)
        assertNull(home.openObject)
        assertNull(home.application)
        assertEquals("t", home.space)
    }

    @Test
    fun a_read_keeps_the_shell_but_a_space_no_longer_listed_gives_way() {
        val prior = Shell.start(null, "p").enter(FolderRef("a", "A"))
        assertSame(prior, ShellModel.next(prior, listOf(personal, tenantSpace), pot))
        val gone = ShellModel.next(prior, listOf(tenantSpace), pot)
        assertEquals("t", gone.space)
        assertEquals(emptyList(), gone.path)
    }

    @Test
    fun a_folder_on_the_path_renamed_or_moved_is_shown_where_it_is_and_one_trashed_gives_way_to_its_parent() {
        val shell = Shell.start(null, "t").enter(FolderRef("a", "A")).enter(FolderRef("b", "B"))
        val renamed = ShellModel.relocated(shell, listOf(folder("a", null), folder("b", "a", "B2")), "b")
        assertEquals(listOf("a", "B2"), renamed.path.map { it.name })
        val moved = ShellModel.relocated(shell, listOf(folder("a", null), folder("c", null), folder("b", "c")), "b")
        assertEquals(listOf("c", "b"), moved.path.map { it.nodeId })
        val trashed = ShellModel.relocated(shell, listOf(folder("a", null)), "b")
        assertEquals(listOf("a"), trashed.path.map { it.nodeId })
        val rootTrashed = ShellModel.relocated(shell, emptyList(), "a")
        assertEquals(emptyList(), rootTrashed.path)
        assertSame(shell, ShellModel.relocated(shell, emptyList(), "elsewhere"), "a node off the path leaves it as it is")
    }

    @Test
    fun an_object_chosen_beside_the_open_one_takes_its_place_and_back_leads_where_that_was_opened_from() {
        val home = Shell.start(null, "t")
        val other = ObjectRef("t", "pot2", "EXPENSES_POT", ApplicationId.EXPENSES, "Rome")
        val first = home.open(pot)
        val beside = first.openBeside(other)
        assertEquals(other, beside.openObject)
        assertSame(home, beside.close())
        // nothing open: it is an open like any other
        assertSame(home, home.openBeside(pot).close())
        kotlin.test.assertTrue(beside.showsListBeside)
        kotlin.test.assertFalse(home.showsListBeside)
        kotlin.test.assertFalse(beside.search(true).showsListBeside)
    }

    @Test
    fun an_object_chosen_from_the_sidebar_has_no_back_and_one_opened_from_it_returns_to_it() {
        val home = Shell(application = null, space = "s").enter(FolderRef("f", "Trips"))
        val pot = ObjectRef("t", "pot", "EXPENSES_POT", ApplicationId.EXPENSES, "Paris")
        val other = ObjectRef("t", "pot2", "EXPENSES_POT", ApplicationId.EXPENSES, "Rome")
        val top = home.openAtTop(pot)
        assertEquals(pot, top.openObject)
        assertEquals("s", top.space)
        kotlin.test.assertFalse(top.canGoBack)
        assertSame(top, top.goBack())
        // its places keep it at the top
        kotlin.test.assertFalse(top.select(top.barTabs.last()).canGoBack)
        // an object opened from it goes back to it
        val inner = top.open(other)
        kotlin.test.assertTrue(inner.canGoBack)
        assertSame(top, inner.goBack())
        // opened as before, back still leads to where it was opened from
        assertSame(home, home.open(pot).goBack())
    }

    @Test
    fun an_object_trashed_leaves_and_one_chosen_at_the_top_leaves_for_its_applications_list() {
        val home = Shell(application = null, space = "s").enter(FolderRef("f", "Trips"))
        val pot = ObjectRef("t", "pot", "EXPENSES_POT", ApplicationId.EXPENSES, "Paris")
        // chosen from the sidebar: back does not leave it, the trash does — to Expenses' list, nothing open
        val gone = ShellModel.afterTrash(home.openAtTop(pot), "pot")
        kotlin.test.assertNull(gone.openObject)
        assertEquals(ApplicationId.EXPENSES, gone.application)
        assertEquals("s", gone.space)
        kotlin.test.assertFalse(gone.top)
        // opened from Home: back to Home's folder
        assertSame(home, ShellModel.afterTrash(home.open(pot), "pot"))
        // another node trashed: the open object stays
        val open = home.openAtTop(pot)
        assertSame(open, ShellModel.afterTrash(open, "other"))
    }

    @Test
    fun an_object_renamed_shows_its_new_name_where_it_was_and_one_chosen_at_the_top_stays_at_the_top() {
        val home = Shell(application = null, space = "s").enter(FolderRef("f", "Trips"))
        val pot = ObjectRef("t", "pot", "EXPENSES_POT", ApplicationId.EXPENSES, "Paris")
        val renamed = ObjectRef("t", "pot", "EXPENSES_POT", ApplicationId.EXPENSES, "Paris 2026")
        // chosen from the sidebar: still at the top, no back, its place kept
        val top = home.openAtTop(pot).let { it.select(it.barTabs.last()) }
        val after = ShellModel.afterRename(top, renamed)
        assertEquals(renamed, after.openObject)
        kotlin.test.assertTrue(after.top)
        kotlin.test.assertFalse(after.canGoBack)
        assertEquals(top.selectedTab, after.selectedTab)
        // opened from Home: back still leads to Home's folder
        val opened = ShellModel.afterRename(home.open(pot), renamed)
        assertEquals(renamed, opened.openObject)
        assertSame(home, opened.goBack())
        // another object renamed: the open one stays as it is
        val open = home.openAtTop(pot)
        assertSame(open, ShellModel.afterRename(open, ObjectRef("t", "other", "EXPENSES_POT", ApplicationId.EXPENSES, "Rome")))
    }
}
