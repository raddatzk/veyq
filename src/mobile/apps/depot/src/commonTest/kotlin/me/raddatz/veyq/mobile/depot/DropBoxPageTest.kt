package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.LivePoll
import me.raddatz.veyq.mobile.session.OpenNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A drop box's page both phones drew (ADR 0161 decision 2 and its Consequences, Depot's screens) — over a tree the test plays. */
class DropBoxPageTest {
    private val box = NodeAt("s", "box", DepotFiles.DROPBOX, "Inbox")

    private fun folder(id: String, label: String) =
        DropBoxes.GuestFolder(OpenNode("s", id, "box", "FOLDER", label, ByteArray(0), shareIdentityId = "g-$id"), label, emptyList())

    private class Door(var folders: List<DropBoxes.GuestFolder>?) : DropBoxPage.Door {
        var reads = 0
        val lists = MutableStateFlow(0)
        /** Each tick's ask whether the scope's tree moved; [moves] answers it (a move reads the lists anew, as the shell's refresh). */
        var asked = 0
        var moves = false
        override suspend fun folders(): List<DropBoxes.GuestFolder>? {
            reads++
            return folders
        }
        override val revisions = lists
        override suspend fun treeMoved(): Boolean {
            asked++
            if (moves) lists.value++
            return moves
        }
    }

    private fun TestScope.open(door: Door) = DropBoxPage(box, door, backgroundScope) { testScheduler.currentTime }.also { runCurrent() }

    @Test
    fun theFoldersAreReadAnewOnEachRevisionOfTheLists() = runTest {
        val anna = folder("a", "Anna")
        val door = Door(listOf(anna))
        val page = open(door)
        assertEquals(listOf("Anna"), page.current.folders?.map { it.label })
        assertEquals(1, door.reads)
        // a move out, an upload, another device: whatever read the lists anew, the page reads with them
        door.folders = listOf(anna, folder("b", "Ben"))
        door.lists.value = 1
        runCurrent()
        assertEquals(listOf("Anna", "Ben"), page.current.folders?.map { it.label })
        assertEquals(2, door.reads)
        // a read that fails keeps what is shown and says so
        door.folders = null
        door.lists.value = 2
        runCurrent()
        assertEquals(listOf("Anna", "Ben"), page.current.folders?.map { it.label })
        assertEquals(true, page.current.failed)
    }

    /**
     * A guest's new folder shows while the page stands (native-apps § Open, V2 block B): its live poll asks at the item
     * lists' cadence whether the scope's tree moved — the conditional read the gallery's poll sends, ADR 0081 —, and only a
     * move reads the page anew (through the lists' new revision). A tree that did not move reads nothing (the control),
     * and the poll ends with the page. Bitten by a tick that never asks: red.
     */
    @Test
    fun aGuestsNewFolderShowsThroughTheLivePollAndAnUnmovedTreeReadsNothing() = runTest {
        val anna = folder("a", "Anna")
        val door = Door(listOf(anna))
        val page = open(door)
        val live = backgroundScope.launch { page.live() }
        advanceTimeBy(LivePoll.ITEMS.intervalMs * 2)
        runCurrent()
        assertTrue(door.asked >= 1, "the poll asks whether the tree moved")
        assertEquals(1, door.reads, "a tree that did not move reads nothing")
        // a guest dropped into a new folder: the tree moved, the lists are read anew, the page with them
        door.folders = listOf(anna, folder("b", "Ben"))
        door.moves = true
        advanceTimeBy(LivePoll.ITEMS.intervalMs * 2)
        runCurrent()
        assertEquals(listOf("Anna", "Ben"), page.current.folders?.map { it.label })
        // the page goes: the poll ends, nothing more is asked
        page.close()
        runCurrent()
        assertTrue(live.isCompleted, "the poll ends with the page")
        val after = door.asked
        advanceTimeBy(LivePoll.ITEMS.intervalMs * 4)
        runCurrent()
        assertEquals(after, door.asked)
    }

    @Test
    fun aFoldersDeleteIsOnlyEverDoneAfterItWasAsked() = runTest {
        val anna = folder("a", "Anna")
        val ben = folder("b", "Ben")
        val page = open(Door(listOf(anna, ben)))
        assertNull(page.delete(anna), "not asked: nothing to trash")
        page.askDelete(anna)
        assertEquals(anna, page.current.deleting)
        assertNull(page.delete(ben), "asked for another folder")
        assertNull(page.current.deleting, "the question is over either way")
        page.askDelete(anna)
        page.cancelAsk()
        assertNull(page.delete(anna), "declined")
        page.askDelete(anna)
        assertEquals(NodeAt("s", "a", "FOLDER", "Anna"), page.delete(anna), "asked and answered: the node the header trashes")
        assertNull(page.current.deleting)
        assertEquals(me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = false, hasContent = true), page.folderFacts)
    }
}
