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
}
