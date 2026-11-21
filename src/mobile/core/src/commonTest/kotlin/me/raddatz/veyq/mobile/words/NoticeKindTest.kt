package me.raddatz.veyq.mobile.words

import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ShareLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How long a raised notice lives (ADR 0154 decision 4, the web's `useToast`): a success said briefly, a refusal or a
 * failure held until closed, a trash offered as an undo. Each raising site of [Notices] is read here by its outcome.
 */
class NoticeKindTest {
    @Test fun lifetimesAreTheWebs() {
        assertEquals(4_000L, NoticeKind.SAID.lifeMs)
        assertNull(NoticeKind.HELD.lifeMs)
        assertNull(NoticeKind.UNDO.lifeMs)
        assertEquals(2_000L, Notice.COPIED_MS)
        assertEquals(5_000L, Notice.NOTHING_PLAYS_MS)
    }

    @Test fun aTrashIsAnUndoAndItsRefusalsAreHeld() {
        assertEquals(NoticeKind.UNDO, Notices.trashed(NodeTrash.Deleted.TRASHED, "a").kind)
        for (o in NodeTrash.Deleted.entries - NodeTrash.Deleted.TRASHED) assertEquals(NoticeKind.HELD, Notices.trashed(o, "a").kind, "$o")
        assertEquals(NoticeKind.HELD, Notices.trashUndoFailed("a").kind)
    }

    @Test fun everyRefusalIsHeld() {
        val refusals = NodeMove.Outcome.entries.mapNotNull(Notices::moveRefused) +
            listOf(SubtreeCopy.Outcome.NAME_TAKEN, SubtreeCopy.Outcome.NOTHING_TO_COPY, null).mapNotNull(Notices::subtreeCopyRefused) +
            ShareLinks.Outcome.entries.mapNotNull(Notices::linkRefused) + ShareLinks.Closed.entries.mapNotNull(Notices::linkClosed) +
            listOf(Notices.folderRefused(null), Notices.downloaded("a", null), Notices.trashUndoFailed("a"))
        assertEquals(emptyList(), refusals.filter { it.kind != NoticeKind.HELD })
    }

    @Test fun aSuccessIsSaid() {
        assertEquals(NoticeKind.SAID, Notices.downloaded("a", emptyList()).kind)
        for (m in listOf(Msg.COPY_DONE, Msg.MOVE_MOVED, Msg.IMPORT_IMPORTED, Msg.OFFLINE_SENT, Msg.DOWNLOAD_CANCELLED, Msg.LINK_KEPT, Msg.PROFILE_SAVED, Msg.RECEIPTS_SAVED_GALLERY)) assertEquals(NoticeKind.SAID, NoticeKind.of(m), "$m")
    }

    /** The desktops' own success toasts (phase 7: Linux raises them by key, their kind the core's table). */
    @Test fun aDesktopsOwnSuccessIsSaid() {
        for (m in listOf(Msg.PROFILE_SAVED, Msg.RECEIPTS_SAVED, Msg.LINK_KEPT)) assertEquals(NoticeKind.SAID, NoticeKind.of(m), "$m")
        for (m in listOf(Msg.SHARE_FAILED, Msg.RECEIPTS_NOT_SAVED, Msg.BOOKMARK_CREATE_FAILED)) assertEquals(NoticeKind.HELD, NoticeKind.of(m), "$m")
    }

    @Test fun aSiteSaysOtherwiseAndTheKindIsPartOfTheNotice() {
        val undo = Notice(Msg.MOVE_MOVED, "a", "b").living(NoticeKind.UNDO)
        assertEquals(NoticeKind.UNDO, undo.kind)
        assertEquals(Notice(Msg.MOVE_MOVED, listOf("a", "b"), NoticeKind.UNDO), undo)
        assertEquals(false, undo == Notice(Msg.MOVE_MOVED, "a", "b"))
    }
}
