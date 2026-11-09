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
}
