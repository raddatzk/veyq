package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the shell says, finds and creates, both apps' once (ADR 0127); the two had drifted on two tab words. */
class ShellWordsTest {
    @Test
    fun the_switcher_names_the_tenant_under_a_space_unless_it_is_the_tenants_own() {
        assertEquals("veyq.localhost", ShellWords.switcherSubtitle(SpaceRef("t", "TENANT", "Familie"), "Familie", "veyq.localhost"))
        assertEquals("Familie · veyq.localhost", ShellWords.switcherSubtitle(SpaceRef("p", "PERSONAL", "Personal"), "Familie", "veyq.localhost"))
        assertEquals("Familie · veyq.localhost", ShellWords.switcherSubtitle(null, "Familie", "veyq.localhost"))
    }

    @Test
    fun a_space_is_named_by_its_kind_else_its_name_and_none_is_home() {
        assertEquals(Piece.Said(Notice(Msg.DRAWER_HOME)), ShellWords.space(null))
        assertEquals(Piece.Said(Notice(Msg.SPACE_PERSONAL)), ShellWords.space(SpaceRef("s", "PERSONAL", "x")))
        assertEquals(Piece.Said(Notice(Msg.SPACE_GROUP)), ShellWords.space(SpaceRef("s", "GROUP", "x")))
        assertEquals(Piece.Plain("Verein"), ShellWords.space(SpaceRef("s", "SHARED", "Verein")))
    }

    @Test
    fun search_finds_a_name_in_any_case_after_trimming_and_nothing_for_blank() {
        val objects = listOf(ObjectRef("s", "1", "EXPENSES_POT", ApplicationId.EXPENSES, "Amsterdam"), ObjectRef("s", "2", "FILE", ApplicationId.DEPOT, "notes.txt"))
        assertEquals(listOf("1"), ShellWords.search(objects, "  amSTER ").map { it.nodeId })
        assertEquals(emptyList(), ShellWords.search(objects, "   "))
    }

    @Test
    fun tabs_and_applications_have_their_words_and_new_offers_what_a_level_creates() {
        assertEquals(Msg.TAB_MEMORIES_ALL, AppKinds.tabWord(BarTab("memories.all"), null))
        assertEquals(Msg.TAB_EXPENSES_SETTINGS, AppKinds.tabWord(BarTab("expenses.settings"), null))
        assertEquals(Msg.TAB_DEPOT_DROPBOX, AppKinds.tabWord(BarTab("depot.file"), "DEPOT_DROPBOX"))
        assertEquals(Msg.TAB_DEPOT_FILE, AppKinds.tabWord(BarTab("depot.file"), "FILE"))
        ApplicationId.entries.forEach { AppKinds.applicationWord(it) }
        assertEquals(listOf(AppKinds.Create.FOLDER, AppKinds.Create.POT), AppKinds.drawerCreates)
        assertEquals(4, AppKinds.homeCreates.size)
        assertTrue(AppKinds.takesShared("EXPENSES_POT"))
        assertFalse(AppKinds.takesShared("FILE"))
    }

    @Test
    fun a_kinds_header_and_what_a_copy_leaves_behind_are_the_registrys() {
        val box = AppKinds.header("DEPOT_DROPBOX")!!
        assertFalse(box.renames)
        assertEquals(Notice(Msg.DROPBOX_DELETE_CONFIRM, "Box"), box.deleteQuestion("Box"))
        assertFalse(AppKinds.header("FILE")!!.takesCover)
        assertFalse(AppKinds.header("MEMORY")!!.shareable)
        assertEquals(Msg.MEMORY_DELETE_CHAPTER, AppKinds.header("CHAPTER")!!.deleteTitle)
        kotlin.test.assertNull(AppKinds.header("SHARE_BOOKMARK"))
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
