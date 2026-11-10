package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.NodeHeader.Action
import me.raddatz.veyq.mobile.navigation.NodeHeader.Plan
import me.raddatz.veyq.mobile.navigation.NodeHeader.Rights
import me.raddatz.veyq.mobile.navigation.NodeHeader.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The web's header rules (`nodeHeader.ts`, `coreNodeActions.ts`, ADR 0110) as the apps' one header plans them. */
class NodeHeaderTest {
    private val all = Rights(favorite = true, share = true, rename = true, move = true, cover = true, copy = true, download = true, report = true, delete = true)
    private val folderOrder = AppKinds.headerOrder("FOLDER")

    @Test
    fun everyActionInTheKindsPriorityTheMoveCopyReportAndDeleteLast() {
        assertEquals(
            Plan(
                buttons = listOf("favorite", "share"),
                menu = listOf("download", "rename", "cover"),
                last = listOf("move", "copy", "report", "delete"),
            ),
            NodeHeader.plan(all, folderOrder, maxButtons = 2),
        )
    }

    @Test
    fun theDeleteEndsTheMenuWhateverAnOrderSays() {
        val plan = NodeHeader.plan(all, listOf("delete", "report", "favorite"), maxButtons = 3)
        assertEquals("delete", plan.last.last())
        assertFalse("delete" in plan.buttons || "report" in plan.buttons, "delete and report are never buttons")
    }

    @Test
    fun theRenameAndTheCoverAreNeverButtons() {
        val plan = NodeHeader.plan(all, listOf("rename", "cover"), maxButtons = 9)
        assertEquals(listOf("favorite", "share", "download"), plan.buttons)
        assertEquals(listOf("rename", "cover"), plan.menu)
    }

    @Test
    fun aRightNotGrantedIsNoActionAnywhere() {
        val plan = NodeHeader.plan(all.copy(share = false, copy = false, report = false), folderOrder, maxButtons = 2)
        assertEquals(listOf("favorite", "download"), plan.buttons)
        assertEquals(listOf("move", "delete"), plan.last)
    }

    @Test
    fun noButtonsPutsTheButtonsFirstInTheMenu() {
        // iOS at a folder's level: one "more" menu, its title keeping the bar's room
        val plan = NodeHeader.plan(all, folderOrder, maxButtons = 0)
        assertEquals(emptyList(), plan.buttons)
        assertEquals(listOf("favorite", "share", "download", "rename", "cover"), plan.menu)
    }

    @Test
    fun theCopyFollowsTheMoveAndCoresActionsSurroundTheApplications() {
        val own = listOf(Action("entries"), Action("upload", Slot.MENU))
        val plan = NodeHeader.plan(all, order = emptyList(), maxButtons = 9, own = own)
        // the heart before them all; rename, share, move (+ copy beside it), delete, the application's own; cover, download, report after
        assertEquals(listOf("favorite", "share", "entries", "download"), plan.buttons)
        assertEquals(listOf("rename", "upload", "cover"), plan.menu)
        assertEquals(listOf("move", "copy", "report", "delete"), plan.last)
    }

    @Test
    fun anActionNoOrderNamesStillAppears() {
        val plan = NodeHeader.plan(all, listOf("share"), maxButtons = 1, own = listOf(Action("later")))
        assertEquals(listOf("share"), plan.buttons)
        assertEquals(listOf("favorite", "rename", "later", "cover", "download"), plan.menu)
    }

    @Test
    fun nothingGrantedIsNoMenu() {
        val none = Rights(false, false, false, false, false, false, false, false, false)
        assertFalse(NodeHeader.plan(none, folderOrder, 2).hasMenu)
    }

    @Test
    fun aGuestsFolderIsMovedOutButNotRenamedSharedCoveredOrDeleted() {
        val all = NodeHeader.Rights(
            favorite = true, share = true, rename = true, move = true, cover = true, copy = true, download = true, report = true, delete = true,
        )
        val folder = NodeHeader.inIdentityFolder(all, "FOLDER")
        assertEquals(
            NodeHeader.Rights(favorite = true, share = false, rename = false, move = true, cover = false, copy = true, download = true, report = true, delete = false),
            folder,
        )
        // a file in a guest's folder is deleted file by file
        kotlin.test.assertTrue(NodeHeader.inIdentityFolder(all, "FILE").delete)
        kotlin.test.assertFalse(NodeHeader.inIdentityFolder(all, "FILE").rename)
    }

    @Test
    fun the_rights_both_apps_built_come_from_the_kind_the_page_and_a_guests_folder() {
        val all = NodeHeader.rights("FOLDER", true, shareable = true, linksOn = true, rename = true, takesCover = true, coverOffered = true, copy = true, download = true, report = true, inGuestFolder = false)
        kotlin.test.assertEquals(NodeHeader.Rights(true, true, true, true, true, true, true, true, true), all)
        // links off for the tenant: no share; a kind without cover: none, whatever its page offers
        kotlin.test.assertFalse(NodeHeader.rights("FOLDER", true, true, linksOn = false, rename = true, takesCover = true, coverOffered = true, copy = true, download = true, report = true, inGuestFolder = false).share)
        kotlin.test.assertFalse(NodeHeader.rights("FILE", true, true, true, true, takesCover = false, coverOffered = true, copy = true, download = true, report = true, inGuestFolder = false).cover)
        // in a guest's folder: the narrowing
        val guest = NodeHeader.rights("FOLDER", true, true, true, true, true, true, true, true, true, inGuestFolder = true)
        kotlin.test.assertEquals(NodeHeader.inIdentityFolder(all, "FOLDER"), guest)
    }

    @Test
    fun a_core_actions_words_follow_its_state_and_the_delete_and_own_ones_are_the_kinds() {
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.NODE_UNFAVORITE, NodeHeader.label(NodeHeader.FAVORITE, "FOLDER", pinned = true, hasCover = false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.NODE_FAVORITE, NodeHeader.label(NodeHeader.FAVORITE, "FOLDER", pinned = null, hasCover = false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.COVER_CHANGE, NodeHeader.label(NodeHeader.COVER, "FOLDER", null, hasCover = true))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.COVER_SET, NodeHeader.label(NodeHeader.COVER, "FOLDER", null, hasCover = false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.session.ShareWords.of("EXPENSES_POT").title, NodeHeader.label(NodeHeader.SHARE, "EXPENSES_POT", null, false))
        kotlin.test.assertNull(NodeHeader.label(NodeHeader.DELETE, "FOLDER", null, false))
        kotlin.test.assertNull(NodeHeader.label("upload", "MEMORY", null, false))
    }
}
