package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.navigation.ShellModel

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.offline.PlayedTenant
import me.raddatz.veyq.mobile.offline.removeAll
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The shell's rules after a read and after a folder on Home's path changed, both apps' once (ADR 0161). */
class ShellModelTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val tenantSpace = SpaceRef("t", "TENANT", "Family")
    private val personal = SpaceRef("p", "PERSONAL", "Mine")
    private val pot = ObjectRef("t", "pot", "EXPENSES_POT", ApplicationId.EXPENSES, "Trip")

    private fun folder(id: String, parent: String?, name: String = id) = NodeRef("t", id, parent, NodeRef.FOLDER, name)

    private val played = mutableListOf<PlayedTenant>()

    @AfterTest
    fun cleanUp() = played.forEach { removeAll(it.root) }

    @Test
    fun a_kept_links_report_sheet_offers_its_name_alone_and_any_other_node_the_choice() = runTest {
        val t = PlayedTenant().also { played += it }
        t.scope("s1")
        t.remember()
        val a = t.resume()
        val shell = ShellModel(a.open(a.tenants.first()), Hidden({}, emptySet())) { 0L }
        // a kept link (ADR 0150 decision 6): no "it and everything in it" beside its name — the backend refuses a NODE report
        val bookmark = shell.reportSheet(NodeAt("s1", "b", AppKinds.BOOKMARK_KIND, "Link"))
        assertFalse(bookmark.offersName, "a kept link's sheet offers the whole beside its name")
        assertFalse(bookmark.current.whole)
        assertEquals(Msg.REPORT_SCOPE_NAME_HINT, bookmark.current.scopeHint)
        bookmark.whole(true)
        assertFalse(bookmark.current.whole, "a kept link's sheet takes the whole")
        // any other node: the choice, the whole preselected
        val folder = shell.reportSheet(NodeAt("s1", "f", "FOLDER", "Trip"))
        assertTrue(folder.offersName)
        assertTrue(folder.current.whole)
    }

    @Test
    fun a_levels_plan_the_drawers_new_and_a_rows_menu_are_the_shells_answers() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        t.node(s, "f", kind = NodeRef.FOLDER, name = "Trips")
        t.remember()
        val a = t.resume()
        val shell = ShellModel(a.open(a.tenants.first()), Hidden({}, emptySet())) { 0L }
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        val home = kotlin.test.assertNotNull(shell.shell)
        // a space's level (ADR 0154 decision 2, owner 2026-11-20): New in its head, Select (its folder moves and is deleted),
        // then the trash before the view switch
        val level = kotlin.test.assertNotNull(shell.levelPlan(home))
        assertEquals(shell.createsHere(isHome = true, bookmarks = true), level.creates)
        assertEquals(listOf(LevelTool.SELECT, LevelTool.TRASH, LevelTool.VIEW), level.tools)
        // the drawer's New comes first, where it offers anything: what the space's top takes
        assertEquals(DrawerSection.NEW, shell.drawerSections().first())
        assertEquals(AppKinds.createsWhere(isHome = false, bookmarks = true), shell.drawerCreates())
        // an object, a person or Search shows no level
        assertNull(shell.levelPlan(home.open(pot)))
        // a folder row's menu: rename and move first, the delete last
        val row = shell.rowPlan(NodeAt(s.id, t.id("f"), NodeRef.FOLDER, "Trips"))
        assertEquals(listOf(HeaderAction.RENAME, HeaderAction.MOVE), row.menu.take(2))
        assertEquals(HeaderAction.DELETE, row.last.last())
        // a kind this build has no page for offers nothing
        assertTrue(shell.rowPlan(NodeAt(s.id, "x", "UNKNOWN", "x")).isEmpty)
    }

    @Test
    fun a_levels_selection_is_the_levels_kept_on_what_it_holds_and_cleared_by_another_place() = runTest {
        val t = PlayedTenant().also { played += it }
        val s = t.scope("s1")
        val alpha = t.node(s, "a", kind = NodeRef.FOLDER, name = "Alpha")
        t.node(s, "b", kind = NodeRef.FOLDER, name = "Beta")
        // the folder entered holds a row of its own, so its level offers Select too: only the other place clears it
        t.node(s, "c", kind = NodeRef.FOLDER, parent = alpha, name = "Gamma")
        t.remember()
        val a = t.resume()
        val shell = ShellModel(a.open(a.tenants.first()), Hidden({}, emptySet())) { 0L }
        shell.load(openLastUsed = false)
        shell.switchSpace(s.id)
        assertNull(shell.current?.selection, "no Select mode before Select")
        shell.selectToggle(t.id("a"))
        assertNull(shell.current?.selection, "a turn outside Select mode selects nothing")
        shell.selectStart()
        val on = kotlin.test.assertNotNull(shell.current?.selection)
        assertEquals(emptySet(), on.selected)
        assertEquals(me.raddatz.veyq.mobile.words.Notice(Msg.SELECT_COUNT, 0), on.count)
        // the bar: what the level's rows allow, in the plan's order, nothing enabled before a row is selected
        assertEquals(listOf(SelectionAction.MOVE, SelectionAction.COPY, SelectionAction.DELETE), on.entries.map { it.action }.filter { it != SelectionAction.REPORT })
        assertTrue(on.entries.none { it.enabled })
        shell.selectToggle(t.id("a"))
        assertEquals(setOf(t.id("a")), shell.current?.selection?.selected)
        assertTrue(shell.current!!.selection!!.entries.all { it.enabled })
        assertEquals(listOf(NodeAt(s.id, t.id("a"), NodeRef.FOLDER, "Alpha")), shell.selected(SelectionAction.DELETE))
        shell.selectAll()
        assertEquals(setOf(t.id("a"), t.id("b")), shell.current?.selection?.selected)
        assertEquals(me.raddatz.veyq.mobile.words.Notice(Msg.SELECT_COUNT, 2), shell.current?.selection?.count)
        shell.selectNone()
        assertEquals(emptySet(), shell.current?.selection?.selected)
        shell.selectAll()
        // a read keeps it; another place (into a folder) clears it
        shell.refresh()
        assertEquals(2, shell.current?.selection?.selected?.size)
        shell.enter(FolderRef(t.id("a"), "Alpha"))
        assertNull(shell.current?.selection, "another place clears the selection")
        shell.goBack()
        shell.selectStart()
        shell.selectAll()
        shell.selectCancel()
        assertNull(shell.current?.selection)
        // the selection's actions reach only what is selected, and nothing without a selection
        assertNull(shell.selectionTrash {})
        assertNull(shell.selectionMoveSheet(copying = false, deletedName = "x") {})
    }

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
    fun a_spaces_top_level_on_home_is_where_its_share_offers_add_files_only() {
        val top = Shell.start(null, "t")
        assertEquals(true, ShellModel.topLevelShared(top, "t"))
        assertEquals(false, ShellModel.topLevelShared(top.enter(FolderRef("a", "A")), "t"), "a folder's level has the folder's own share")
        assertEquals(false, ShellModel.topLevelShared(top.open(pot), "t"), "an object open")
        assertEquals(false, ShellModel.topLevelShared(top.choose(ApplicationId.EXPENSES), "t"), "an application's list")
        assertEquals(false, ShellModel.topLevelShared(top.search(true), "t"), "Search")
        assertEquals(false, ShellModel.topLevelShared(top.openPerson("u-ana"), "t"), "a person's page opened from the top level")
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
    /** #1035: a kept link's page heads its new name after a rename, and is left after its trash — as an open object's. */
    @Test
    fun a_kept_links_page_follows_its_rename_and_leaves_after_its_trash() {
        val home = Shell(application = null, space = "s").enter(FolderRef("f", "Trips"))
        val kept = NodeAt("s", "bm", "SHARE_BOOKMARK", "Kept")
        val page = home.openBookmark(kept)
        val renamed = ShellModel.afterRename(page, NodeAt("s", "bm", "SHARE_BOOKMARK", "Kept 2"))
        assertEquals("Kept 2", renamed.bookmark?.name)
        assertEquals("Kept 2", renamed.headed?.name)
        assertSame(home, renamed.goBack(), "the rename lost where the page was opened from")
        assertSame(page, ShellModel.afterRename(page, NodeAt("s", "other", "SHARE_BOOKMARK", "Other")))
        // trashed: back to where it was opened from
        assertSame(home, ShellModel.afterTrash(page, "bm"))
        assertSame(page, ShellModel.afterTrash(page, "other"))
    }
}
