package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.session.NodeCreate
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * New Memory and New chapter (`MemoryNodeDialog.vue`): offered exactly where the kinds' placement allows them, a name
 * checked against the loaded tree before anything is sent (named by the sibling's kind, core's `collidingSibling`), and a
 * server's refusal said as the dialog says it.
 */
class MemoryCreateTest {
    @Test
    fun aMemoryIsOfferedAtASpacesTopAndInAFolderAChapterOnlyInAMemory() {
        assertEquals(listOf(MemoryCreate.MEMORY), MemoryCreate.offered(null))
        assertEquals(listOf(MemoryCreate.MEMORY), MemoryCreate.offered("FOLDER"))
        assertEquals(listOf(MemoryCreate.CHAPTER), MemoryCreate.offered("MEMORY"))
        for (parent in listOf("CHAPTER", "EXPENSES_POT", "FILE", "DEPOT_DROPBOX", "SHARE_BOOKMARK", "SOMETHING_NEW")) {
            assertEquals(emptyList(), MemoryCreate.offered(parent), "nothing of Memories' in a $parent")
        }
    }

    @Test
    fun aNameIsCheckedBeforeItIsSent() {
        val siblings = listOf(MemoryCreate.Sibling("MEMORY", "Ostsee 2026"), MemoryCreate.Sibling("FOLDER", "Trip"))
        assertNull(MemoryCreate.check("MEMORY", null, "  Alps  ", siblings))
        assertEquals(
            Notice(Msg.NODE_NAME_TAKEN_BY, Notice(Msg.MEMORY_A_MEMORY), "Ostsee 2026"),
            MemoryCreate.check("MEMORY", "FOLDER", " ostsee 2026 ", siblings),
            "a sibling folding to the same name is named with its kind",
        )
        assertNull(MemoryCreate.check("MEMORY", null, "Trip", siblings), "a folder's name folds apart from a Memory's")
        assertEquals(
            Notice(Msg.NODE_NAME_TAKEN_BY, Notice(Msg.MEMORY_A_CHAPTER), "Day one"),
            MemoryCreate.check("CHAPTER", "MEMORY", "Day One", listOf(MemoryCreate.Sibling("CHAPTER", "Day one"))),
        )
        assertEquals(Notice(Msg.FOLDER_NAME_EMPTY), MemoryCreate.check("MEMORY", null, "   ", siblings))
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), MemoryCreate.check("CHAPTER", null, "Day one", emptyList()), "no chapter at a top")
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), MemoryCreate.check("MEMORY", "MEMORY", "Inner", emptyList()), "no Memory in a Memory")
    }

    @Test
    fun aRefusalIsSaidAsTheDialogSaysIt() {
        assertEquals(Notice(Msg.MEMORY_SLUG_TAKEN), MemoryCreate.refused(NodeCreate.Reason.NAME_TAKEN))
        assertEquals(Notice(Msg.NODE_ILLEGAL_STRUCTURE), MemoryCreate.refused(NodeCreate.Reason.NOT_ALLOWED))
        for (r in listOf(NodeCreate.Reason.BUSY, NodeCreate.Reason.BLOCKED, NodeCreate.Reason.REFUSED, null)) {
            assertEquals(Notice(Msg.NODE_SAVE_FAILED), MemoryCreate.refused(r), "$r")
        }
    }
}
