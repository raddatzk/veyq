package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.NoticeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A level's selection's delete (inbox #1049, the web's `DeleteNodesDialog` and `deleteSelection.ts`) over a door the test plays. */
class SelectionTrashTest {
    init { me.raddatz.veyq.mobile.composition.AppComposition.install() }

    private val folder = NodeAt("s", "f", "FOLDER", "Trips")
    private val memory = NodeAt("s", "m", "MEMORY", "Rome")
    private val pot = NodeAt("s", "p", "EXPENSES_POT", "Rome")

    private class Door(val refused: Set<String> = emptySet()) : SelectionTrash.Door {
        val trashed = mutableListOf<String>()
        val restored = mutableListOf<String>()
        var changes = 0
        @OptIn(me.raddatz.veyq.mobile.PlatformApi::class)
        override suspend fun trash(ref: NodeAt): NodeHeaderModel.Trashed {
            trashed += ref.nodeId
            if (ref.nodeId in refused) return NodeHeaderModel.Trashed(NodeTrash.Deleted.SCOPE_DELETING, Notice(Msg.DELETE_REFUSED_SCOPE_DELETING), null)
            return NodeHeaderModel.Trashed(NodeTrash.Deleted.TRASHED, Notice(Msg.TRASH_MOVED, ref.name), OpenNode("s", ref.nodeId, null, ref.kind, ref.name, ByteArray(32)))
        }
        override suspend fun undoTrash(node: OpenNode): Notice? {
            restored += node.id
            return null
        }
        override fun changed() { changes++ }
    }

    @Test
    fun one_node_is_asked_its_own_question_and_several_one_naming_how_many_and_their_kinds() {
        val one = SelectionTrash(listOf(memory), Door())
        assertEquals(Notice(Msg.MEMORY_DELETE_MEMORY), one.title)
        assertEquals(Notice(Msg.MEMORY_DELETE_CONFIRM, "Rome"), one.question)
        val three = SelectionTrash(listOf(folder, memory, pot, NodeAt("s", "g", "FOLDER", "x")), Door())
        assertEquals(Notice(Msg.DELETE_MANY_TITLE, 4), three.title)
        // each kind once, in the selection's order, with its article
        assertEquals(
            Notice(Msg.DELETE_MANY_COPY, 4, Joined(listOf(Notice(Msg.NODE_A_FOLDER), Notice(Msg.MEMORY_A_MEMORY), Notice(Msg.POT_A)), ", ")),
            three.question,
        )
    }

    @Test
    fun each_goes_a_refusal_stops_nothing_and_one_undo_brings_back_what_went() = runTest {
        val door = Door(refused = setOf("m"))
        val sheet = SelectionTrash(listOf(folder, memory, pot), door, backgroundScope)
        val said = sheet.trash()
        assertEquals(listOf("f", "m", "p"), door.trashed, "each in order, the refusal stopping nothing")
        assertEquals(1, door.changes, "the lists are told once")
        assertEquals(Msg.SELECT_SUMMARY, said.msg)
        assertEquals(Notice(Msg.DELETE_MANY_MOVED, 2), said.args[0])
        assertEquals(
            Notice(Msg.DELETE_MANY_NOT_DELETED, Joined(listOf(Notice(Msg.SELECT_REASON, "Rome", Notice(Msg.DELETE_REFUSED_SCOPE_DELETING))), " ")),
            said.args[1],
        )
        assertEquals(NoticeKind.UNDO, assertNotNull(sheet.current.undo).kind)
        assertNull(sheet.undo())
        assertEquals(listOf("f", "p"), door.restored, "exactly what went comes back")
        assertNull(sheet.current.undo)
        // a second run says what the first did and sends nothing
        assertEquals(said, sheet.trash())
        assertEquals(3, door.trashed.size)
    }

    @Test
    fun all_through_is_one_notice_with_its_undo_and_one_node_says_its_own() = runTest {
        val all = SelectionTrash(listOf(folder, pot), Door(), backgroundScope)
        assertEquals(Notice(Msg.DELETE_MANY_MOVED, 2), all.trash())
        assertNotNull(all.current.undo)
        val one = SelectionTrash(listOf(folder), Door(), backgroundScope)
        assertEquals(Notice(Msg.TRASH_MOVED, "Trips"), one.trash())
        assertNotNull(one.current.undo)
    }
}
