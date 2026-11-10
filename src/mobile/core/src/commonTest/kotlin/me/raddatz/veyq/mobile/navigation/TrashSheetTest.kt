package me.raddatz.veyq.mobile.navigation

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.session.NodeTrash
import me.raddatz.veyq.mobile.words.Msg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A space's trash sheet both apps drew (the web's `SpaceTrashDialog.vue`), over a trash the test plays. */
class TrashSheetTest {
    private fun row(id: String, name: String = id) = NodeTrash.Trashed("s", id, null, "FOLDER", name, 1, 1, 1, "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", false)

    private class Door(var rows: MutableList<NodeTrash.Trashed>?) : TrashSheet.Door {
        var restored = NodeTrash.Restored.RESTORED
        var purged = NodeTrash.Purged.PURGED
        val names = mutableListOf<String?>()
        override suspend fun list() = rows?.toList()
        override fun canRestore(row: NodeTrash.Trashed) = row.kind == "FOLDER"
        override suspend fun restore(row: NodeTrash.Trashed, newName: String?): NodeTrash.Restored {
            names += newName
            if (restored == NodeTrash.Restored.RESTORED) rows?.removeAll { it.id == row.id }
            return restored
        }
        override suspend fun purge(row: NodeTrash.Trashed): NodeTrash.Purged {
            if (purged != NodeTrash.Purged.FAILED) rows?.removeAll { it.id == row.id }
            return purged
        }
    }

    @Test
    fun a_restore_whose_name_was_taken_asks_for_another_and_restores_under_it() = runTest {
        val a = row("a", "Trip")
        val door = Door(mutableListOf(a, row("b")))
        var changes = 0
        val sheet = TrashSheet(door) { changes++ }
        sheet.load()
        door.restored = NodeTrash.Restored.NAME_TAKEN
        sheet.restore(a)
        assertEquals("a", sheet.current.renaming)
        assertEquals("Trip", sheet.current.retryName)
        assertEquals(Msg.NODE_SLUG_TAKEN, sheet.current.error)
        assertEquals(0, changes)
        sheet.type("   ")
        assertFalse(sheet.current.retryable(a))
        sheet.type("Trip 2")
        assertTrue(sheet.current.retryable(a))
        door.restored = NodeTrash.Restored.RESTORED
        sheet.retry(a)
        assertEquals(listOf(null, "Trip 2"), door.names)
        assertNull(sheet.current.renaming)
        assertEquals(listOf("b"), sheet.current.rows!!.map { it.id })
        assertEquals(1, changes)
        assertNull(sheet.current.busy)
    }

    @Test
    fun delete_forever_asks_first_and_a_row_gone_meanwhile_is_read_again() = runTest {
        val a = row("a")
        val door = Door(mutableListOf(a, row("b")))
        val sheet = TrashSheet(door) {}
        sheet.load()
        sheet.purge() // nothing asked: nothing erased
        assertEquals(2, sheet.current.rows!!.size)
        sheet.askPurge(a)
        door.purged = NodeTrash.Purged.FAILED
        sheet.purge()
        assertEquals("a" to Msg.TRASH_PURGE_FAILED, sheet.current.errorRow to sheet.current.error)
        door.purged = NodeTrash.Purged.GONE
        door.rows!!.removeAll { it.id == "b" } // someone else restored b meanwhile
        sheet.askPurge(a)
        sheet.purge()
        assertEquals(emptyList(), sheet.current.rows!!.map { it.id })
        assertNull(sheet.current.error)
    }

    @Test
    fun a_trash_that_does_not_read_says_so() = runTest {
        val sheet = TrashSheet(Door(null)) {}
        sheet.load()
        assertTrue(sheet.current.failed)
    }
}
