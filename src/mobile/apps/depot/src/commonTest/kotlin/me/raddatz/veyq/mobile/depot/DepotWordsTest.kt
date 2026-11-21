package me.raddatz.veyq.mobile.depot

import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Moment
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals

/** What Depot's rows and file page say, both apps' once (the web's Depot `details`, `detail`, `uploaderOf`, `people`). */
class DepotWordsTest {
    private fun file(blobId: String? = "b", size: Long? = 1234, type: String? = "text/plain", modifiedAt: Long? = 99) =
        DepotFiles.File("s", "n", blobId, 1, 1300, null, size, type, "a.txt", modifiedAt, null)

    private val modified = Piece.Said(Notice(Msg.DEPOT_MODIFIED, Moment(99)))

    @Test
    fun a_files_row_says_its_size_and_change_or_that_it_is_not_uploaded() {
        assertEquals(listOf(Piece.Size(1234), modified), DepotFiles.rowDetails(file()))
        assertEquals(listOf(Piece.Said(Notice(Msg.DEPOT_NOT_UPLOADED))), DepotFiles.rowDetails(file(blobId = null)))
    }

    @Test
    fun a_files_page_says_its_size_its_claimed_type_or_unknown_and_its_change() {
        assertEquals(listOf(Piece.Size(1234), Piece.Plain("text/plain"), modified), DepotFiles.facts(file()))
        assertEquals(listOf(Piece.Said(Notice(Msg.FILE_TYPE_UNKNOWN))), DepotFiles.facts(file(size = null, type = null, modifiedAt = null)))
    }

    @Test
    fun the_uploader_is_named_as_a_member_one_who_left_or_a_guest() {
        assertEquals(Notice(Msg.DEPOT_UPLOADED_BY, "Anna"), Uploaders.words(Uploaders.Uploader(Uploaders.Kind.MEMBER, "u", "Anna")))
        assertEquals(Notice(Msg.DEPOT_UPLOADED_BY, Notice(Msg.PEOPLE_FORMER_MEMBER)), Uploaders.words(Uploaders.Uploader(Uploaders.Kind.FORMER)))
        assertEquals(Notice(Msg.DEPOT_UPLOADED_BY, Notice(Msg.DEPOT_GUEST_NAME, "Clara")), Uploaders.words(Uploaders.Uploader(Uploaders.Kind.GUEST, name = "Clara")))
        assertEquals(Notice(Msg.DEPOT_UPLOADED_BY, Notice(Msg.DEPOT_A_GUEST)), Uploaders.words(Uploaders.Uploader(Uploaders.Kind.GUEST)))
    }

    @Test
    fun a_drop_box_counts_its_guests_folders_and_a_page_without_content_says_why() {
        val nodes = listOf(
            NodeRef("s", "box", null, DepotFiles.DROPBOX, "Box"),
            NodeRef("s", "g1", "box", "FOLDER", "Clara"),
            NodeRef("s", "g2", "box", "FOLDER", "Dan"),
            NodeRef("s", "f", "box", DepotFiles.FILE, "loose.txt"),
            NodeRef("s", "empty", null, DepotFiles.DROPBOX, "Empty"),
        )
        assertEquals(mapOf("box" to Notice(Msg.DROPBOX_PEOPLE, 2), "empty" to Notice(Msg.DROPBOX_PEOPLE_NONE)), DropBoxes.people(nodes))
        assertEquals(Msg.FILE_TOO_LARGE, FileView.message(FileView.Kind.TOO_LARGE))
        assertEquals(Msg.FILE_EMPTY, FileView.message(FileView.Kind.EMPTY))
        assertEquals(Msg.FILE_UNSUPPORTED, FileView.message(FileView.Kind.IMAGE))
    }
}
