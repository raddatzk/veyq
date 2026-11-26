package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the shell says, finds and creates, both apps' once (ADR 0161); the two had drifted on two tab words. */
class ShellWordsTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    @Test
    fun the_switcher_names_the_tenant_under_a_space_unless_it_is_the_tenants_own() {
        assertEquals("veyq.localhost", ShellWords.switcherSubtitle(SpaceRef("t", "TENANT", "Familie"), "Familie", "veyq.localhost"))
        assertEquals("Familie · veyq.localhost", ShellWords.switcherSubtitle(SpaceRef("p", "PERSONAL", "Personal"), "Familie", "veyq.localhost"))
        assertEquals("Familie · veyq.localhost", ShellWords.switcherSubtitle(null, "Familie", "veyq.localhost"))
    }

    @Test
    fun every_switcher_row_says_its_domain_and_two_accounts_of_one_tenant_their_address() {
        val shared = TenantRef("t", "Throwaway", "tw.veyq.localhost")
        val other = TenantRef("o", "Verein", "")
        val owner = ShellWords.SwitcherAccount("veyq.localhost#u1", "veyq.localhost", "owner@example.test", listOf(shared, other))
        val member = ShellWords.SwitcherAccount("veyq.localhost#u2", "veyq.localhost", "member@example.test", listOf(shared))
        val rows = ShellWords.switcherEntries(listOf(owner, member), "veyq.localhost#u2", "t")
        assertEquals(
            listOf("tw.veyq.localhost · owner@example.test", "veyq.localhost", "tw.veyq.localhost · member@example.test"),
            rows.map { it.detail },
        )
        // only the member's row of the shared tenant is the one open, not the owner's of the same tenant
        assertEquals(listOf(false, false, true), rows.map { it.current })
        assertEquals("Throwaway · tw.veyq.localhost · member@example.test", rows[2].line)
        // one account alone: every row its domain, no address
        assertEquals(listOf("tw.veyq.localhost", "veyq.localhost"), ShellWords.switcherEntries(listOf(owner), null, null).map { it.detail })
    }

    @Test
    fun a_drawer_section_with_nothing_in_it_is_not_shown() {
        val ref = ObjectRef("s", "n", "MEMORY", ApplicationId.entries.first(), "Sommer")
        assertEquals(emptyList(), DrawerSection.shown(ArrangedList(emptyList(), emptyList(), emptyList()), emptyList()))
        assertEquals(
            listOf(DrawerSection.RECENT, DrawerSection.APPLICATIONS),
            DrawerSection.shown(ArrangedList(emptyList(), listOf(ref), emptyList()), ApplicationId.entries.toList()),
        )
        assertEquals(
            listOf(DrawerSection.FAVORITES, DrawerSection.APPLICATIONS),
            DrawerSection.shown(ArrangedList(listOf(ref), emptyList(), emptyList()), ApplicationId.entries.toList()),
        )
    }

    @Test
    fun a_space_is_named_by_its_kind_else_its_name_and_none_is_home() {
        assertEquals(Piece.Said(Notice(Msg.DRAWER_HOME)), ShellWords.space(null))
        assertEquals(Piece.Said(Notice(Msg.SPACE_PERSONAL)), ShellWords.space(SpaceRef("s", "PERSONAL", "x")))
        assertEquals(Piece.Said(Notice(Msg.SPACE_GROUP)), ShellWords.space(SpaceRef("s", "GROUP", "x")))
        assertEquals(Piece.Plain("Verein"), ShellWords.space(SpaceRef("s", "SHARED", "Verein")))
    }

    @Test
    fun tabs_and_applications_have_their_words_and_new_offers_what_a_level_creates() {
        assertEquals(Msg.TAB_MEMORIES_ALL, AppKinds.tabWord(BarTab("memories.all"), null))
        assertEquals(Msg.TAB_EXPENSES_SETTINGS, AppKinds.tabWord(BarTab("expenses.settings"), null))
        assertEquals(Msg.TAB_DEPOT_DROPBOX, AppKinds.tabWord(BarTab("depot.file"), "DEPOT_DROPBOX"))
        assertEquals(Msg.TAB_DEPOT_FILE, AppKinds.tabWord(BarTab("depot.file"), "FILE"))
        ApplicationId.entries.forEach { AppKinds.applicationWord(it) }
        // the orders are the web's, held by contracts/platform/plans/v1-vectors.json (`PlanVectorsTest`); a chapter only on a
        // Memory's page
        assertTrue(AppKinds.Create.CHAPTER !in AppKinds.createsWhere(true) + AppKinds.createsWhere(false))
        // a kept link only where the platform draws its dialog (#1020), last, on every level
        assertTrue(AppKinds.Create.BOOKMARK !in AppKinds.createsWhere(true) + AppKinds.createsWhere(false))
        assertEquals(AppKinds.Create.BOOKMARK, AppKinds.createsWhere(isHome = true, bookmarks = true).last())
        assertEquals(AppKinds.Create.BOOKMARK, AppKinds.createsWhere(isHome = false, bookmarks = true).last())
        assertEquals(me.raddatz.veyq.mobile.memories.MemoryCreate.offered(null), listOf(AppKinds.Create.MEMORY.name))
        assertEquals(me.raddatz.veyq.mobile.memories.MemoryCreate.offered("MEMORY"), listOf(AppKinds.Create.CHAPTER.name))
        assertSame(me.raddatz.veyq.mobile.expenses.PotShare.answer, AppKinds.share("EXPENSES_POT"))
        assertNull(AppKinds.share("FILE"))
    }

    @Test
    fun a_kinds_header_and_what_a_copy_leaves_behind_are_the_registrys() {
        val box = AppKinds.header("DEPOT_DROPBOX")!!
        assertFalse(box.renames)
        assertEquals(Notice(Msg.DROPBOX_DELETE_CONFIRM, "Box"), box.deleteQuestion("Box"))
        assertFalse(AppKinds.header("FILE")!!.takesCover)
        // a Memory and a chapter share through their sheet (READ, READ_WRITE, WRITE_ONLY), as the web's
        assertTrue(AppKinds.header("MEMORY")!!.shareable)
        assertTrue(AppKinds.header("CHAPTER")!!.shareable)
        assertEquals(Msg.MEMORY_DELETE_CHAPTER, AppKinds.header("CHAPTER")!!.deleteTitle)
        // a kept link: never shared on, no cover, its delete in its own words (never a folder's)
        val bookmark = AppKinds.header("SHARE_BOOKMARK")!!
        assertFalse(bookmark.shareable)
        assertFalse(bookmark.takesCover)
        assertEquals(Msg.BOOKMARK_DELETE, bookmark.deleteTitle)
        assertEquals(Notice(Msg.BOOKMARK_DELETE_CONFIRM, "Link"), bookmark.deleteQuestion("Link"))
        // each loss said once, the kinds in one order on both platforms
        assertEquals(
            listOf(Msg.COPY_LOSS_DROPBOX, Msg.COPY_LOSS_POT, Msg.COPY_LOSS_COVER, Msg.COPY_LOSS_FOLDER_COVER),
            AppKinds.lossesOfCopy(listOf("FOLDER", "EXPENSES_POT", "DEPOT_DROPBOX", "EXPENSES_POT", "FILE")),
        )
    }

    @Test
    fun a_recent_use_is_stored_as_one_line_and_a_line_that_is_none_is_dropped() {
        val u = UseKey("s", "n", 42)
        assertEquals(u, UseKey.decode(u.encode()))
        kotlin.test.assertNull(UseKey.decode("s\tn"))
        kotlin.test.assertNull(UseKey.decode("s\tn\tlater"))
    }

    @Test
    fun the_drawer_opens_only_with_nothing_chosen_and_a_new_pot_lands_where_the_shell_is() {
        assertTrue(Shell.start(null, null).drawerOpensAtStart)
        assertFalse(Shell.start(null, "t").drawerOpensAtStart)
        val inFolder = Shell.start(null, "t").enter(FolderRef("f", "F"))
        assertEquals("f", me.raddatz.veyq.mobile.expenses.PotCreate.parentIn(inFolder, "t"))
        kotlin.test.assertNull(me.raddatz.veyq.mobile.expenses.PotCreate.parentIn(inFolder, "other"))
        assertEquals("t", me.raddatz.veyq.mobile.expenses.PotCreate.defaultSpace(inFolder, listOf(SpaceRef("p", "PERSONAL", "Mine"))))
        assertEquals("p", me.raddatz.veyq.mobile.expenses.PotCreate.defaultSpace(Shell.start(null, null), listOf(SpaceRef("p", "PERSONAL", "Mine"))))
    }

    @Test
    fun manage_servers_keeps_the_current_tenant_shown_and_names_it_so() {
        val t = TenantRef("t", "Verein", "verein.example")
        val current = me.raddatz.veyq.mobile.session.SessionModel.ManagedTenant(t, hidden = true, current = true, spaces = null)
        assertTrue(current.shown)
        assertFalse(current.switchable)
        assertEquals(Piece.Said(Notice(Msg.MANAGE_CURRENT)), current.subtitle)
        val hidden = me.raddatz.veyq.mobile.session.SessionModel.ManagedTenant(t, hidden = true, current = false, spaces = null)
        assertFalse(hidden.shown)
        assertEquals(Piece.Plain("verein.example"), hidden.subtitle)
    }
}
