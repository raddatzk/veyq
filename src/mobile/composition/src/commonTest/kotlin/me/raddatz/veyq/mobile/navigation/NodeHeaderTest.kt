package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.navigation.HeaderAction.BANNER
import me.raddatz.veyq.mobile.navigation.HeaderAction.COPY
import me.raddatz.veyq.mobile.navigation.HeaderAction.COVER
import me.raddatz.veyq.mobile.navigation.HeaderAction.DELETE
import me.raddatz.veyq.mobile.navigation.HeaderAction.DOWNLOAD
import me.raddatz.veyq.mobile.navigation.HeaderAction.FAVORITE
import me.raddatz.veyq.mobile.navigation.HeaderAction.MOVE
import me.raddatz.veyq.mobile.navigation.HeaderAction.RENAME
import me.raddatz.veyq.mobile.navigation.HeaderAction.REPLACE
import me.raddatz.veyq.mobile.navigation.HeaderAction.REPORT
import me.raddatz.veyq.mobile.navigation.HeaderAction.SHARE
import me.raddatz.veyq.mobile.navigation.HeaderAction.UPLOAD
import me.raddatz.veyq.mobile.navigation.NodeHeader.Plan
import me.raddatz.veyq.mobile.navigation.NodeHeader.Rights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The web's header rules (`nodeHeader.ts`, `coreNodeActions.ts`, ADR 0110) as the apps' one header plans them. */
class NodeHeaderTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val all = Rights(favorite = true, share = true, rename = true, move = true, cover = true, copy = true, download = true, report = true, delete = true)
    private val folderOrder = AppKinds.headerOrder("FOLDER")

    @Test
    fun everyActionInTheKindsPriorityTheMoveCopyReportAndDeleteLast() {
        assertEquals(
            Plan(
                buttons = listOf(FAVORITE, SHARE),
                menu = listOf(DOWNLOAD, RENAME, COVER),
                last = listOf(MOVE, COPY, REPORT, DELETE),
            ),
            NodeHeader.plan(all, folderOrder, maxButtons = 2),
        )
    }

    @Test
    fun theDeleteEndsTheMenuWhateverAnOrderSays() {
        val plan = NodeHeader.plan(all, listOf(DELETE, REPORT, FAVORITE), maxButtons = 3)
        assertEquals(DELETE, plan.last.last())
        assertFalse(DELETE in plan.buttons || REPORT in plan.buttons, "delete and report are never buttons")
    }

    @Test
    fun theRenameAndTheCoverAreNeverButtons() {
        val plan = NodeHeader.plan(all, listOf(RENAME, COVER), maxButtons = 9)
        assertEquals(listOf(FAVORITE, SHARE, DOWNLOAD), plan.buttons)
        assertEquals(listOf(RENAME, COVER), plan.menu)
    }

    @Test
    fun aRightNotGrantedIsNoActionAnywhere() {
        val plan = NodeHeader.plan(all.copy(share = false, copy = false, report = false), folderOrder, maxButtons = 2)
        assertEquals(listOf(FAVORITE, DOWNLOAD), plan.buttons)
        assertEquals(listOf(MOVE, DELETE), plan.last)
    }

    @Test
    fun noButtonsPutsTheButtonsFirstInTheMenu() {
        // iOS at a folder's level: one "more" menu, its title keeping the bar's room
        val plan = NodeHeader.plan(all, folderOrder, maxButtons = 0)
        assertEquals(emptyList(), plan.buttons)
        assertEquals(listOf(FAVORITE, SHARE, DOWNLOAD, RENAME, COVER), plan.menu)
    }

    @Test
    fun theCopyFollowsTheMoveAndCoresActionsSurroundTheApplications() {
        val own = listOf(UPLOAD, BANNER)
        val plan = NodeHeader.plan(all, order = emptyList(), maxButtons = 9, own = own)
        // the heart before them all; rename, share, move (+ copy beside it), delete, the page's own; cover, download, report after
        assertEquals(listOf(FAVORITE, SHARE, UPLOAD, DOWNLOAD), plan.buttons)
        assertEquals(listOf(RENAME, BANNER, COVER), plan.menu)
        assertEquals(listOf(MOVE, COPY, REPORT, DELETE), plan.last)
    }

    @Test
    fun anActionNoOrderNamesStillAppears() {
        val plan = NodeHeader.plan(all, listOf(SHARE), maxButtons = 1, own = listOf(REPLACE))
        assertEquals(listOf(SHARE), plan.buttons)
        assertEquals(listOf(FAVORITE, RENAME, REPLACE, COVER, DOWNLOAD), plan.menu)
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
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.NODE_UNFAVORITE, NodeHeader.label(HeaderAction.FAVORITE, "FOLDER", pinned = true, hasCover = false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.NODE_FAVORITE, NodeHeader.label(HeaderAction.FAVORITE, "FOLDER", pinned = null, hasCover = false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.COVER_CHANGE, NodeHeader.label(HeaderAction.COVER, "FOLDER", null, hasCover = true))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.COVER_SET, NodeHeader.label(HeaderAction.COVER, "FOLDER", null, hasCover = false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.session.ShareWords.of("EXPENSES_POT").title, NodeHeader.label(HeaderAction.SHARE, "EXPENSES_POT", null, false))
        kotlin.test.assertNull(NodeHeader.label(HeaderAction.DELETE, "FOLDER", null, false))
        kotlin.test.assertNull(NodeHeader.label(UPLOAD, "MEMORY", null, false))
    }

    @Test
    fun a_kept_links_report_is_its_names_and_its_own_entries_join_the_kinds_order() {
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.BOOKMARK_REPORT_NAME, NodeHeader.label(REPORT, "SHARE_BOOKMARK", null, false))
        kotlin.test.assertEquals(me.raddatz.veyq.mobile.words.Msg.REPORT_ACTION, NodeHeader.label(REPORT, "FOLDER", null, false))
        val bookmark = Rights(favorite = true, share = false, rename = true, move = true, cover = false, copy = false, download = false, report = true, delete = true)
        val plan = NodeHeader.plan(bookmark, AppKinds.headerOrder("SHARE_BOOKMARK"), maxButtons = 2, own = listOf(HeaderAction.OPEN_OWN_PAGE, HeaderAction.REPORT_SHARED))
        assertEquals(listOf(HeaderAction.OPEN_OWN_PAGE, FAVORITE), plan.buttons)
        assertEquals(listOf(RENAME), plan.menu)
        // the share's report beside the name's, the delete last: never after it (Windows drew them after the delete)
        assertEquals(listOf(MOVE, REPORT, HeaderAction.REPORT_SHARED, DELETE), plan.last)
    }

    @Test
    fun a_memorys_banner_is_a_menu_entry_where_its_order_puts_it() {
        val plan = NodeHeader.plan(all, AppKinds.headerOrder("MEMORY"), maxButtons = 2, own = listOf(UPLOAD, HeaderAction.MUSIC, BANNER))
        assertEquals(listOf(FAVORITE, UPLOAD), plan.buttons)
        assertEquals(listOf(SHARE, HeaderAction.MUSIC, DOWNLOAD, BANNER, RENAME, COVER), plan.menu)
        assertEquals(DELETE, plan.last.last())
    }

    @Test
    fun a_rows_menu_opens_with_rename_and_move_and_ends_with_report_and_the_delete() {
        assertEquals(
            LevelRow.Plan(menu = listOf(RENAME, MOVE, FAVORITE, SHARE, COPY, COVER, DOWNLOAD), last = listOf(REPORT, DELETE)),
            LevelRow.plan(all),
        )
        val none = Rights(false, false, false, false, false, false, false, false, false)
        kotlin.test.assertTrue(LevelRow.plan(none).isEmpty)
        assertEquals(listOf(REPORT, HeaderAction.REPORT_SHARED, DELETE), LevelRow.plan(all, own = listOf(HeaderAction.REPORT_SHARED)).last)
    }

    @Test
    fun a_levels_plan_puts_the_trash_before_the_view_switch() {
        assertEquals(listOf(LevelTool.TRASH, LevelTool.VIEW), LevelPlan.of(emptyList(), trash = true, views = true).tools)
        assertEquals(listOf(LevelTool.VIEW), LevelPlan.of(emptyList(), trash = false, views = true).tools)
        assertEquals(emptyList(), LevelPlan.of(emptyList(), trash = false, views = false).tools)
    }

    @Test
    fun the_drawers_new_comes_first_where_it_offers_any() {
        val empty = ArrangedList(emptyList(), emptyList(), emptyList())
        assertEquals(listOf(DrawerSection.NEW, DrawerSection.APPLICATIONS), DrawerSection.shown(empty, listOf(ApplicationId.MEMORIES), creates = true))
        assertEquals(emptyList(), DrawerSection.shown(empty, emptyList(), creates = false))
    }
}
