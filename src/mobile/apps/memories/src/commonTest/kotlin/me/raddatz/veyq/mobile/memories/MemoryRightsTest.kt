package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.memories.MemoryRights.Access
import me.raddatz.veyq.mobile.memories.MemoryRights.DeleteMedia
import me.raddatz.veyq.mobile.session.ShareLinks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Memories' rights matrix (`docs/apps/memories.md` § What a guest may do, the web's `rights.ts` and `rights.test.ts`): a
 * member and each link role on a Memory, a chapter, a folder and a space's top, every field written out, so a right granted
 * where the matrix refuses it — or refused where it grants it — turns a case red. Fail-closed: an unknown role or kind
 * grants nothing.
 */
class MemoryRightsTest {
    /** The rights a case expects: the fields named true, the rest false. */
    private fun rights(vararg on: String, delete: DeleteMedia = DeleteMedia.NONE) = MemoryRights.Rights(
        navigate = "navigate" in on, download = "download" in on, upload = "upload" in on, deleteMedia = delete,
        createMemory = "createMemory" in on, createChapter = "createChapter" in on, editMeta = "editMeta" in on,
        setBanner = "setBanner" in on, mark = "mark" in on, filterFacts = "filterFacts" in on, participants = "participants" in on,
        mediaTrash = "mediaTrash" in on, buckets = "buckets" in on,
    )

    private val memberOnMedia = arrayOf("navigate", "download", "upload", "editMeta", "setBanner", "mark", "filterFacts", "mediaTrash", "buckets")

    @Test
    fun aMemberHasEveryMemoriesRightOnAMemoryAndAChapterAndMakesAMemoryAtALevel() {
        val m = Access.Member()
        assertEquals(rights(*memberOnMedia, "createChapter", "participants", delete = DeleteMedia.ANY), MemoryRights.of(m, "MEMORY"))
        assertEquals(rights(*memberOnMedia, delete = DeleteMedia.ANY), MemoryRights.of(m, "CHAPTER"), "a chapter takes no chapter, has no participants")
        assertEquals(rights("navigate", "createMemory"), MemoryRights.of(m, "FOLDER"))
        assertEquals(rights("navigate", "createMemory"), MemoryRights.of(m, null), "a space's top")
        assertEquals(rights("navigate"), MemoryRights.of(m.copy(inIdentityFolder = true), "FOLDER"), "a guest's identity folder takes no Memory")
        assertEquals(MemoryRights.NONE, MemoryRights.of(m, "EXPENSES_POT"), "not a Memories kind")
    }

    @Test
    fun memoriesSwitchedOffReadsOnlyButStillTrashesAndAPendingDeletionRemovesNothing() {
        val off = Access.Member(memoriesOn = false)
        assertEquals(
            rights("navigate", "download", "mark", "filterFacts", "mediaTrash", "buckets", "participants", delete = DeleteMedia.ANY),
            MemoryRights.of(off, "MEMORY"),
        )
        assertEquals(rights("navigate"), MemoryRights.of(off, null))
        val deleting = Access.Member(readonly = true)
        assertEquals(
            rights("navigate", "download", "mark", "filterFacts", "mediaTrash", "buckets", "participants"),
            MemoryRights.of(deleting, "MEMORY"),
        )
    }

    @Test
    fun aViewerReadsAndDownloadsOnly() {
        val g = Access.Guest(ShareLinks.READ)
        for (kind in listOf("MEMORY", "CHAPTER")) assertEquals(rights("navigate", "download"), MemoryRights.of(g, kind), kind)
        assertEquals(rights("navigate"), MemoryRights.of(g, "FOLDER"))
        assertEquals(MemoryRights.NONE, MemoryRights.of(g, null), "a guest has no space's top")
    }

    @Test
    fun aContributorUploadsAndDeletesItsOwnMediaOnlyAndMakesNothing() {
        val g = Access.Guest(ShareLinks.READ_WRITE)
        for (kind in listOf("MEMORY", "CHAPTER")) {
            assertEquals(rights("navigate", "download", "upload", delete = DeleteMedia.OWN), MemoryRights.of(g, kind), kind)
        }
        assertEquals(rights("navigate"), MemoryRights.of(g, "FOLDER"), "a folder link's contributor makes no Memory")
    }

    @Test
    fun anUploaderSendsAndRemovesItsOwnAndReadsNothing() {
        val g = Access.Guest(ShareLinks.WRITE_ONLY)
        for (kind in listOf("MEMORY", "CHAPTER")) assertEquals(rights("upload", delete = DeleteMedia.OWN), MemoryRights.of(g, kind), kind)
        assertEquals(MemoryRights.NONE, MemoryRights.of(g, "FOLDER"))
    }

    @Test
    fun anUnknownViewerRoleOrKindGrantsNothing() {
        assertEquals(MemoryRights.NONE, MemoryRights.of(Access.None, "MEMORY"))
        assertEquals(MemoryRights.NONE, MemoryRights.of(Access.Guest("ADMIN"), "MEMORY"))
        assertEquals(MemoryRights.NONE, MemoryRights.of(Access.Guest(ShareLinks.READ_WRITE), "FILE"))
        assertEquals(MemoryRights.NONE, MemoryRights.of(Access.Member(), "SOMETHING_NEW"))
    }
}
