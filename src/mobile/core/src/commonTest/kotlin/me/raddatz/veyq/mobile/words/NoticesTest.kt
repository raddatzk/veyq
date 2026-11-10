package me.raddatz.veyq.mobile.words

import me.raddatz.veyq.mobile.depot.DropBoxes
import me.raddatz.veyq.mobile.depot.FolderUpload
import me.raddatz.veyq.mobile.expenses.PotCopy
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.ShareLinkChanges
import me.raddatz.veyq.mobile.session.ShareLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The outcome tables both apps used to write: every outcome raises its key with as many arguments as its words take. */
class NoticesTest {
    /** Each argument count matches the catalog's placeholders, a word inside a word too. */
    private fun fits(n: Notice?) {
        if (n == null) return
        assertEquals(n.msg.args, n.args.size, "${n.msg.key} takes ${n.msg.args} arguments, got ${n.args}")
        n.args.filterIsInstance<Notice>().forEach(::fits)
    }

    @Test
    fun every_outcome_raises_a_key_with_its_arguments() {
        NodeTrash.Deleted.entries.forEach { fits(Notices.trashed(it, "Pot")) }
        NodeMove.Outcome.entries.forEach { fits(Notices.moveRefused(it)) }
        PotCopy.Outcome.entries.forEach { fits(Notices.potCopyRefused(it)) }
        (SubtreeCopy.Outcome.entries + null).forEach { fits(Notices.subtreeCopyRefused(it)) }
        (NodeCreate.Reason.entries + null).forEach { fits(Notices.folderRefused(it)) }
        NodeReport.Outcome.entries.forEach { fits(Notices.reportRefused(it)) }
        ShareLinkChanges.Outcome.entries.forEach { fits(Notices.linkChanged(it)) }
        ShareLinks.Outcome.entries.forEach { fits(Notices.linkRefused(it)) }
        DropBoxes.Outcome.entries.forEach { fits(Notices.dropBoxRefused(it)) }
        FolderUpload.Outcome.entries.forEach { o ->
            Notices.folderUpload(FolderUpload.Report(o, "Up", 1234, failedFolders = listOf("Up/a"))).forEach(::fits)
        }
        fits(Notices.trashUndoFailed("Pot"))
        fits(Notices.replaceOffered("a.txt"))
        fits(Notices.replaceStale("a.txt"))
    }

    @Test
    fun what_went_through_says_nothing_and_what_did_not_says_why() {
        assertNull(Notices.moveRefused(NodeMove.Outcome.MOVED))
        assertNull(Notices.potCopyRefused(PotCopy.Outcome.COPIED))
        assertNull(Notices.subtreeCopyRefused(SubtreeCopy.Outcome.COPIED))
        assertNull(Notices.reportRefused(NodeReport.Outcome.SENT))
        assertNull(Notices.linkChanged(ShareLinkChanges.Outcome.DONE))
        assertNull(Notices.linkRefused(ShareLinks.Outcome.CREATED))
        assertNull(Notices.dropBoxRefused(DropBoxes.Outcome.CREATED))
        assertEquals(Notice(Msg.TRASH_MOVED, "Pot"), Notices.trashed(NodeTrash.Deleted.TRASHED, "Pot"))
        assertEquals(Notice(Msg.MOVE_REFUSED_REKEY_PENDING), Notices.potCopyRefused(PotCopy.Outcome.BUSY))
        assertEquals(Notice(Msg.COPY_FAILED_ROLLED_BACK), Notices.subtreeCopyRefused(null))
        assertEquals(listOf(Notice(Msg.DEPOT_FOLDER_NO_ROOM, "Up", Bytes(1234))), Notices.folderUpload(FolderUpload.Report(FolderUpload.Outcome.NO_ROOM, "Up", 1234)))
        assertTrue(Notices.folderUpload(FolderUpload.Report(FolderUpload.Outcome.NOTHING, "Up")).isEmpty())
    }

    @Test
    fun the_pot_tables_say_each_problem_and_refusal_once_and_a_left_out_node_with_why() {
        val problems = listOf("title", "date", "amount", "rate", "payers", "split", "split-shares", "split-percents", "split-amounts", "items",
            "transfer", "payers-unbalanced", "receivers-unbalanced", "items-above-total", "rest-unallocated", "percent-unbalanced",
            "amounts-unbalanced", "named-twice")
        assertEquals(problems.size, problems.map { Notices.entryProblem(it) }.toSet().size)
        assertEquals(Msg.PROBLEM_UNKNOWN_PARTICIPANT, Notices.entryProblem("unknown-participant:x"))
        assertEquals(Msg.ED_SAVE_FAILED, Notices.entryRefused("REFUSED"))
        assertEquals(Msg.PEOPLE_SETTLE_FIRST, Notices.potRefused("NOT_SETTLED"))
        assertEquals(Msg.CREATE_POT_NAME_TAKEN, Notices.potCreateRefused("NAME_TAKEN"))
        val left = Notices.leftOut(me.raddatz.veyq.mobile.export.NodeExport.Skipped("Old", "FOLDER", me.raddatz.veyq.mobile.export.SubtreeCopy.NOT_COPYABLE))
        fits(left)
        assertEquals(Notice(Msg.LEFT_OUT_LINE, "Old", Notice(Msg.LEFT_OUT_NOT_COPYABLE)), left)
    }

    @Test
    fun receipts_left_out_are_named_once_the_entry_is_saved() {
        assertNull(Notices.receiptsSkipped(emptyList()))
        val n = Notices.receiptsSkipped(listOf("a.jpg", "b.pdf"))
        fits(n)
        assertEquals(Notice(Msg.RECEIPTS_NO_ROOM, Joined(listOf("a.jpg", "b.pdf"), ", ")), n)
    }
}
