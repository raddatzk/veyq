package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.session.NodeCover
import me.raddatz.veyq.mobile.session.NodeRename
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.ShareNames
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Notices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A node's header both apps drew (ADR 0129 decision 2): its rights, pin, trash with its undo, rename — over a door the test plays. */
class NodeHeaderModelTest {
    private val folder = NodeAt("s", "f", "FOLDER", "Trip")
    private val pot = NodeAt("s", "p", "EXPENSES_POT", "Rome")
    private val file = NodeAt("s", "x", "FILE", "a.txt")
    private val dropBox = NodeAt("s", "d", "DEPOT_DROPBOX", "Inbox")
    private val memory = NodeAt("s", "m", "MEMORY", "Summer")

    private class Door : NodeHeaderModel.Door {
        var pinned: Boolean? = false
        var identity = false
        var links = true
        var report = true
        var stores = true
        var gate: CompletableDeferred<Unit>? = null
        var trashed = NodeTrash.Deleted.TRASHED
        var renamed = NodeRename.Result(NodeRename.Outcome.RENAMED)
        val closed = mutableListOf<NodeAt>()
        val restored = mutableListOf<String>()
        var changes = 0
        override fun isFavorite(ref: NodeAt) = pinned
        override fun inIdentityFolder(ref: NodeAt) = identity
        override fun linksOn() = links
        override suspend fun reportOffered() = report
        override suspend fun toggleFavorite(ref: NodeAt): Boolean {
            gate?.await()
            if (stores) pinned = pinned?.not()
            return stores
        }
        override suspend fun trash(ref: NodeAt) = ShellModel.Trashed(
            trashed, Notices.trashed(trashed, ref.name), OpenNode(ref.scopeId, ref.nodeId, null, ref.kind, ref.name, ByteArray(32)),
        )
        override suspend fun undoTrash(node: OpenNode): Notice? { restored += node.id; return null }
        override suspend fun rename(ref: NodeAt, name: String) = ShellModel.Renamed(renamed, Notices.renameRefused(renamed))
        override fun closeOpen(ref: NodeAt) { closed += ref }
        override fun changed() { changes++ }
        var stored: NodeCover.Crop? = null
        var picture: ByteArray? = null
        var coverLands = true
        val coverWrites = mutableListOf<String>()
        override suspend fun cover(ref: NodeAt) = stored
        override suspend fun coverPicture(ref: NodeAt) = picture
        override suspend fun setCover(ref: NodeAt, jpeg: ByteArray, crop: NodeCover.Crop): NodeCover.Outcome {
            coverWrites += "set ${jpeg.size}"
            if (!coverLands) return NodeCover.Outcome.REFUSED
            stored = crop; picture = jpeg
            return NodeCover.Outcome.DONE
        }
        override suspend fun recropCover(ref: NodeAt, crop: NodeCover.Crop): NodeCover.Outcome {
            coverWrites += "recrop"
            stored = crop
            return NodeCover.Outcome.DONE
        }
        override suspend fun removeCover(ref: NodeAt): NodeCover.Outcome {
            coverWrites += "remove"
            stored = null; picture = null
            return NodeCover.Outcome.DONE
        }
    }

    private fun TestScope.header(node: NodeAt, door: Door = Door(), facts: AppKinds.Facts? = AppKinds.Facts()) =
        NodeHeaderModel(node, facts, door, backgroundScope).also { runCurrent() }

    @Test
    fun the_rights_per_kind_and_what_its_page_knows() = runTest {
        val all = NodeHeader.Rights(favorite = true, share = true, rename = true, move = true, cover = true, copy = true, download = true, report = true, delete = true)
        assertEquals(all, header(folder).current.rights)
        // a Pot not read yet: nothing offered; read only (Expenses off): no cover, no copy, its download kept
        val p = header(pot, facts = null)
        assertNull(p.current.rights)
        assertNull(p.plan(2))
        p.update(AppKinds.Facts(canWrite = false))
        assertEquals(all.copy(cover = false, copy = false), p.current.rights)
        p.update(AppKinds.Facts(canWrite = true))
        assertEquals(all, p.current.rights)
        // a file takes no cover; without content no copy and no download
        assertEquals(all.copy(cover = false), header(file).current.rights)
        assertEquals(all.copy(cover = false, copy = false, download = false), header(file, facts = AppKinds.Facts(hasContent = false)).current.rights)
        // a drop box is never renamed; a Memory brings no share sheet, no copy, no download
        assertEquals(all.copy(rename = false), header(dropBox).current.rights)
        assertEquals(all.copy(share = false, copy = false, download = false), header(memory).current.rights)
        // the tenant's links off, no reports taken, the pin set not loaded
        val off = header(folder, Door().apply { links = false; report = false; pinned = null })
        assertEquals(all.copy(favorite = false, share = false, report = false), off.current.rights)
        // a guest's folder: moved out only, never shared, renamed, covered or deleted as a node
        assertEquals(
            all.copy(share = false, rename = false, cover = false, delete = false),
            header(folder, Door().apply { identity = true }).current.rights,
        )
        assertEquals(Msg.POT_DELETE, p.deleteTitle)
        assertEquals(Notice(Msg.POT_DELETE_CONFIRM, "Rome"), p.deleteQuestion())
        assertEquals(Msg.POT_DELETE, p.label(NodeHeader.DELETE, hasCover = false))
    }

    @Test
    fun the_heart_turns_at_once_and_back_when_the_pin_did_not_land() = runTest {
        val door = Door().apply { gate = CompletableDeferred() }
        val h = header(folder, door)
        assertEquals(false, h.current.pinned)
        assertEquals(Msg.NODE_FAVORITE, h.label(NodeHeader.FAVORITE, false))
        door.stores = false
        val said = async { h.toggleFavorite() }
        runCurrent()
        assertEquals(true, h.current.pinned, "turned before the write answered")
        assertEquals(Msg.NODE_UNFAVORITE, h.label(NodeHeader.FAVORITE, false))
        door.gate!!.complete(Unit)
        assertEquals(Notice(Msg.NODE_FAVORITE_FAILED), said.await())
        assertEquals(false, h.current.pinned, "turned back: the pin did not land")
        door.stores = true
        assertNull(h.toggleFavorite())
        assertEquals(true, h.current.pinned)
    }

    @Test
    fun the_core_offers_the_undo_for_its_window_and_then_drops_it() = runTest {
        val door = Door()
        val h = header(folder, door)
        assertEquals(Notice(Msg.TRASH_MOVED, "Trip"), h.trash())
        assertEquals(Notice(Msg.TRASH_MOVED, "Trip"), h.current.undo)
        advanceTimeBy(ShellModel.TRASH_UNDO_MS - 1)
        assertNotNull(h.current.undo, "still offered inside the window")
        advanceTimeBy(2)
        assertNull(h.current.undo, "gone once the window passed")
        assertNull(h.undoTrash())
        assertEquals(emptyList(), door.restored, "an undo after its window brings nothing back")

        // inside the window the undo restores it
        val again = header(folder, door)
        again.trash()
        advanceTimeBy(ShellModel.TRASH_UNDO_MS / 2)
        assertNull(again.undoTrash())
        assertEquals(listOf("f"), door.restored)
        assertNull(again.current.undo)
        assertFalse(again.current.gone)
    }

    @Test
    fun the_open_node_closes_once_it_went_to_the_trash() = runTest {
        val door = Door()
        val h = header(pot, door)
        h.trash()
        assertEquals(listOf(pot), door.closed)
        assertTrue(h.current.gone)
        // refused: it stays, open, and no undo is offered
        door.closed.clear()
        door.trashed = NodeTrash.Deleted.SCOPE_DELETING
        val stays = header(pot, door)
        assertEquals(Notice(Msg.DELETE_REFUSED_SCOPE_DELETING), stays.trash())
        assertEquals(emptyList(), door.closed)
        assertFalse(stays.current.gone)
        assertNull(stays.current.undo)
    }

    @Test
    fun a_rename_says_a_link_kept_the_old_name_and_why_it_did_not_land() = runTest {
        val door = Door()
        val h = header(folder, door)
        val clean = h.rename("Trip 2")
        assertNull(clean.problem)
        assertNull(clean.notice)
        door.renamed = NodeRename.Result(NodeRename.Outcome.RENAMED, links = ShareNames.Report(listOf("a"), emptyList(), emptyList(), listOf("b")))
        val kept = h.rename("Trip 3")
        assertNull(kept.problem, "the rename stands")
        assertEquals(Notice(Msg.SHARE_NAME_RESEAL_FAILED), kept.notice)
        door.renamed = NodeRename.Result(NodeRename.Outcome.FAILED)
        val failed = h.rename("Trip 4")
        assertEquals(Notice(Msg.NODE_SAVE_FAILED), failed.problem)
        assertNull(failed.notice)
        assertEquals(3, door.changes)
    }

    @Test
    fun the_cover_is_read_at_open_set_framed_anew_and_removed() = runTest {
        val door = Door().apply { stored = NodeCover.Crop(0.5, 0.0, 0.5, 1.0) }
        val h = header(folder, door)
        assertEquals(NodeCover.Crop(0.5, 0.0, 0.5, 1.0), h.current.cover, "read when the header opens")
        assertEquals(Msg.COVER_CHANGE, h.label(NodeHeader.COVER))
        // no picture picked: the stored one framed anew, its blob kept
        val left = NodeCover.Crop(0.0, 0.0, 0.5, 1.0)
        assertNull(h.saveCover(null, left))
        assertEquals(listOf("recrop"), door.coverWrites)
        assertEquals(left, h.current.cover)
        // a picked picture is uploaded with its frame
        assertNull(h.saveCover(ByteArray(3), NodeCover.Crop.FULL))
        assertEquals("set 3", door.coverWrites.last())
        assertEquals(3, h.coverPicture()?.size)
        // a write that did not land says so, the frame read again
        door.coverLands = false
        assertEquals(Notice(Msg.COVER_FAILED), h.saveCover(ByteArray(4), left))
        assertEquals(NodeCover.Crop.FULL, h.current.cover)
        assertNull(h.removeCover())
        assertNull(h.current.cover)
        assertEquals(Msg.COVER_SET, h.label(NodeHeader.COVER))
        // nothing to frame anew without a cover
        assertEquals(Notice(Msg.COVER_FAILED), h.saveCover(null, left))
        assertEquals(listOf("recrop", "set 3", "set 4", "remove"), door.coverWrites)
        assertEquals(3, door.changes, "only the writes that landed")
        // a kind that takes no cover asks for none
        assertNull(header(file, Door().apply { stored = left }).current.cover)
    }
}
