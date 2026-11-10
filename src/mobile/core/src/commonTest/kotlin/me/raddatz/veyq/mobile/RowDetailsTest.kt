package me.raddatz.veyq.mobile

import me.raddatz.veyq.mobile.depot.DepotFiles
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rows' facts both apps read after every change, merged once (the apps had drifted on covers and failures). */
class RowDetailsTest {
    private val file = DepotFiles.File("s", "f", null, 0, 0, null, null, null, null, null, null)
    private val nodes = listOf(NodeRef("s", "box", null, DepotFiles.DROPBOX, "Box"))

    @Test
    fun every_row_says_its_facts_and_a_part_that_did_not_read_keeps_its_rows() {
        val first = RowDetails.merge(RowDetails.Parts(mapOf("pot" to listOf("EUR")), mapOf("f" to file), nodes, mapOf("pot" to "b@0,0,1,1")), RowDetails.Read.EMPTY)
        assertEquals(
            mapOf(
                "pot" to listOf(Piece.Plain("EUR")),
                "f" to listOf(Piece.Said(Notice(Msg.DEPOT_NOT_UPLOADED))),
                "box" to listOf(Piece.Said(Notice(Msg.DROPBOX_PEOPLE_NONE))),
            ),
            first.details,
        )
        // the next read failed for the Pots and the covers: theirs stay; the files read anew (none now)
        val next = RowDetails.merge(RowDetails.Parts(null, emptyMap(), nodes, null), first)
        assertEquals(setOf("pot", "box"), next.details.keys)
        assertEquals(mapOf("pot" to "b@0,0,1,1"), next.covers)
        assertEquals(emptyMap(), next.files)
    }
}
