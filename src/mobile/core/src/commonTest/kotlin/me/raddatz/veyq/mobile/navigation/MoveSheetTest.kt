package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.session.NodeMove
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The move and copy sheet both apps drew (ADR 0129 decision 2, the header CR's D1 and D4) — over a door the test plays. */
class MoveSheetTest {
    private fun n(id: String, parent: String?, kind: String = "FOLDER", identity: Boolean = false) = NodeRef("s", id, parent, kind, id, identity)

    // root: trips/ (2026/ (rome), guest*), box (DEPOT_DROPBOX), pot-b
    private val tree = listOf(
        n("trips", null), n("2026", "trips"), n("rome", "2026", "EXPENSES_POT"),
        n("guest", "trips", identity = true), n("pot-b", null, "EXPENSES_POT"),
    )
    private val rome = NodeAt("s", "rome", "EXPENSES_POT", "rome")
    private val trips = NodeAt("s", "trips", "FOLDER", "trips")
    private val guest = NodeAt("s", "guest", "FOLDER", "guest")

    private class Door(val tree: List<NodeRef>) : MoveSheet.Door {
        val moves = mutableListOf<Pair<String, String?>>()
        val copies = mutableListOf<String>()
        var moved = NodeMove.Outcome.MOVED
        var copyAnswer: Notice? = null
        var room: Long? = null
        var preview: SubtreeCopy.Preview? = null
        var changes = 0
        override suspend fun tree(scopeId: String) = tree
        override val placement = NodeMove.PlacementAllows { _, parent -> parent == null || parent == "FOLDER" }
        override fun rootName(scopeId: String): Piece = Piece.Said(Notice(Msg.SPACE_PERSONAL))
        override suspend fun move(ref: NodeAt, to: String?): NodeMove.Outcome { moves += ref.nodeId to to; return moved }
        override suspend fun duplicateOpen(ref: NodeAt, to: String?): Notice? { copies += "open:${ref.nodeId}->$to"; return copyAnswer }
        override suspend fun duplicateSubtree(ref: NodeAt, to: String?): Notice? { copies += "subtree:${ref.nodeId}->$to"; return copyAnswer }
        override suspend fun preview(ref: NodeAt) = preview
        override suspend fun noRoom(ref: NodeAt) = room
        override fun changed() { changes++ }
    }

    private suspend fun TestScope.sheet(node: NodeAt, copying: Boolean = false, door: Door = Door(tree)) =
        MoveSheet(node, copying, door, backgroundScope).also { it.load(); runCurrent() }

    @Test
    fun the_browser_opens_at_the_place_and_blocks_where_the_node_is_and_below_it() = runTest {
        val s = sheet(trips)
        // opens where the folder is: the root, which it is already in
        assertTrue(s.current.loaded)
        assertNull(s.current.at)
        assertEquals(NodeMove.Block.CURRENT, s.current.block)
        assertEquals(Msg.MOVE_HERE_CURRENT, s.hereWord(s.current))
        assertFalse(s.current.canChoose)
        assertEquals(listOf(MoveSheet.Crumb(null, Piece.Said(Notice(Msg.SPACE_PERSONAL)))), s.current.trail)
        // the node itself is listed, blocked, and cannot be opened
        assertEquals(NodeMove.Block.ITSELF, s.current.places.single { it.node.nodeId == "trips" }.blocked)
        s.go("trips")
        assertNull(s.current.at, "the node itself is not opened")
        s.go("2026")
        assertNull(s.current.at, "what lies below it is not opened")
        // a Pot opens at its folder; up the trail to the root, where it may go
        val p = sheet(rome)
        assertEquals("2026", p.current.at)
        assertEquals(listOf(null, "trips", "2026"), p.current.trail.map { it.nodeId })
        p.up()
        assertEquals("trips", p.current.at)
        assertNull(p.current.block)
        assertTrue(p.current.canChoose)
        // a guest's folder takes nothing, and the copy says so in its own words
        p.go("guest")
        assertEquals("trips", p.current.at, "a guest's folder is not listed to go into")
    }

    @Test
    fun a_move_that_ends_a_guests_folder_is_asked_first() = runTest {
        val door = Door(tree)
        val s = sheet(guest, door = door)
        s.go(null)
        assertTrue(s.current.canChoose)
        s.choose()
        assertTrue(s.current.confirming)
        assertEquals(emptyList<Pair<String, String?>>(), door.moves, "nothing sent before the confirm")
        s.cancelConfirm()
        assertFalse(s.current.confirming)
        assertEquals(emptyList<Pair<String, String?>>(), door.moves)
        s.choose()
        s.confirm()
        assertEquals(listOf<Pair<String, String?>>("guest" to null), door.moves)
        assertEquals(Notice(Msg.MOVE_MOVED, "guest", Notice(Msg.SPACE_PERSONAL)), s.current.done)
        assertNull(s.current.undo, "a guest's folder moved out has ended: no undo")
        // a copy of it asks nothing
        val copy = sheet(guest, copying = true, door = door)
        copy.go(null)
        copy.choose()
        assertFalse(copy.current.confirming)
        assertEquals(listOf("subtree:guest->null"), door.copies)
    }

    @Test
    fun a_copy_goes_the_kinds_way_and_may_go_where_its_original_is() = runTest {
        val door = Door(tree)
        val pot = sheet(rome, copying = true, door = door)
        assertNull(pot.current.block, "a copy may go beside its original")
        pot.choose()
        val folder = sheet(trips, copying = true, door = door)
        folder.choose()
        assertEquals(listOf("open:rome->2026", "subtree:trips->null"), door.copies)
        assertEquals(Notice(Msg.COPY_DONE, "rome", "2026"), pot.current.done)
        assertNull(pot.current.undo, "a copy offers no undo")
        // a copy that did not go through says why, and the sheet stays
        door.copyAnswer = Notice(Msg.COPY_NAME_TAKEN)
        val refused = sheet(trips, copying = true, door = door)
        refused.choose()
        assertEquals(Notice(Msg.COPY_NAME_TAKEN), refused.current.problem)
        assertNull(refused.current.done)
    }

    @Test
    fun a_copy_says_what_it_leaves_behind_and_out_and_one_without_room_cannot_be_chosen() = runTest {
        val door = Door(tree).apply {
            preview = SubtreeCopy.Preview(setOf("FOLDER", "EXPENSES_POT"), listOf(NodeExport.Skipped("x", "MEMORY", SubtreeCopy.NOT_COPYABLE)))
        }
        val s = sheet(trips, copying = true, door = door)
        assertEquals(listOf(Msg.COPY_LOSS_ALWAYS, Msg.COPY_LOSS_POT, Msg.COPY_LOSS_COVER, Msg.COPY_LOSS_FOLDER_COVER), s.current.losses)
        assertEquals(listOf(Notice(Msg.LEFT_OUT_LINE, "x", Notice(Msg.LEFT_OUT_NOT_COPYABLE))), s.current.leftOut)
        assertNull(s.current.noRoom)
        assertTrue(s.current.canChoose)
        door.room = 12_345
        val full = sheet(trips, copying = true, door = door)
        assertEquals(Notice(Msg.COPY_NO_ROOM, Bytes(12_345)), full.current.noRoom)
        assertFalse(full.current.canChoose)
        full.choose()
        assertEquals(emptyList<String>(), door.copies)
        // a move reads neither
        assertEquals(emptyList<Msg>(), sheet(trips, door = door).current.losses)
        assertNull(sheet(trips, door = door).current.noRoom)
    }

    @Test
    fun the_core_offers_a_moves_undo_for_its_window_and_then_drops_it() = runTest {
        val door = Door(tree)
        val s = sheet(rome, door = door)
        s.go("trips")
        s.choose()
        val said = Notice(Msg.MOVE_MOVED, "rome", "trips")
        assertEquals(said, s.current.done)
        assertEquals(said, s.current.undo)
        advanceTimeBy(me.raddatz.veyq.mobile.navigation.ShellModel.TRASH_UNDO_MS - 1)
        assertNotNull(s.current.undo, "still offered inside the window")
        advanceTimeBy(2)
        assertNull(s.current.undo, "gone once the window passed")
        assertNull(s.undo())
        assertEquals(listOf<Pair<String, String?>>("rome" to "trips"), door.moves, "an undo after its window moves nothing")

        // inside the window the undo moves it back to where it was
        val again = sheet(rome, door = door)
        again.go("trips")
        again.choose()
        advanceTimeBy(me.raddatz.veyq.mobile.navigation.ShellModel.TRASH_UNDO_MS / 2)
        assertNull(again.undo())
        assertEquals<Pair<String, String?>>("rome" to "2026", door.moves.last())
        assertNull(again.current.undo)
        // a move back that did not land says so
        val third = sheet(rome, door = door)
        third.go("trips")
        third.choose()
        door.moved = NodeMove.Outcome.CONFLICT
        assertEquals(Notice(Msg.MOVE_UNDO_FAILED, "rome"), third.undo())
        // a move that did not go through says why and offers nothing
        val refused = sheet(rome, door = door)
        refused.go("trips")
        refused.choose()
        assertEquals(Notice(Msg.MOVE_REFUSED_STALE_PLACEMENT), refused.current.problem)
        assertNull(refused.current.undo)
        assertNull(refused.current.done)
    }
}
