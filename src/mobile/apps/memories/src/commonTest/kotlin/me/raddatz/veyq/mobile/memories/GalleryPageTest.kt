package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.navigation.ObjectRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a phone opens for a Memory's chapter row and for a Memory or chapter just made (`GalleryModel.page`): Memories'
 * page, though a chapter is no object of a list (`NodeRef.asObject` gives none for it).
 */
class GalleryPageTest {
    init { me.raddatz.veyq.mobile.TestApplications.install(me.raddatz.veyq.mobile.memories.MemoriesKinds) }

    @Test
    fun a_chapter_row_and_a_node_made_open_as_memories_pages() {
        val chapter = NodeRef("s", "c1", "m", "CHAPTER", "Day one")
        // the list's own mapping names no application for a chapter: what this page exists for
        assertNull(chapter.asObject())
        assertEquals(ObjectRef("s", "c1", "CHAPTER", ApplicationId.MEMORIES, "Day one"), GalleryModel.rowPage(chapter))
        assertEquals(ObjectRef("s", "m", "MEMORY", ApplicationId.MEMORIES, "Trip"), GalleryModel.page(NodeAt("s", "m", "MEMORY", "Trip")))
    }

    @Test
    fun no_other_kind_opens_as_a_memories_page() {
        assertNull(GalleryModel.page(NodeAt("s", "f", "FOLDER", "Folder")))
        assertNull(GalleryModel.rowPage(NodeRef("s", "p", null, "EXPENSES_POT", "Pot")))
    }
}
