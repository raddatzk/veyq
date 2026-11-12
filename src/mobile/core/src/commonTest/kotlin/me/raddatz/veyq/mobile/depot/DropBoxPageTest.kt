package me.raddatz.veyq.mobile.depot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.session.OpenNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A drop box's page both phones drew (ADR 0129 decision 2, the Depot CR's D2 and D9) — over a tree the test plays. */
class DropBoxPageTest {
    private val box = NodeAt("s", "box", DepotFiles.DROPBOX, "Inbox")

    private fun folder(id: String, label: String) =
        DropBoxes.GuestFolder(OpenNode("s", id, "box", "FOLDER", label, ByteArray(0), shareIdentityId = "g-$id"), label, emptyList())

    private class Door(var folders: List<DropBoxes.GuestFolder>?) : DropBoxPage.Door {
        var reads = 0
        val lists = MutableStateFlow(0)
        override suspend fun folders(): List<DropBoxes.GuestFolder>? {
            reads++
            return folders
        }
        override val revisions = lists
    }

    private fun TestScope.open(door: Door) = DropBoxPage(box, door, backgroundScope).also { runCurrent() }

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
