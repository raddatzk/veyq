package me.raddatz.veyq.mobile.navigation

import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.Piece
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rows' facts the apps read after every change, merged once over the applications' parts (the apps had drifted on covers and failures). */
class RowDetailsTest {
    private val pots = mapOf("pot" to listOf<Piece>(Piece.Plain("EUR")))
    private val files = mapOf("f" to listOf<Piece>(Piece.Said(Notice(Msg.DEPOT_NOT_UPLOADED))))
    private val boxes = mapOf("box" to listOf<Piece>(Piece.Said(Notice(Msg.DROPBOX_PEOPLE_NONE))))

    @Test
    fun every_row_says_its_facts_and_a_part_that_did_not_read_keeps_its_rows() {
        val first = RowDetails.merge(mapOf("a.pots" to pots, "b.files" to files, "b.boxes" to boxes), mapOf("pot" to "b@0,0,1,1"), RowDetails.Read.EMPTY)
        assertEquals(pots + files + boxes, first.details)
        // the next read failed for the Pots and the covers: theirs stay; the files read anew (none now)
        val next = RowDetails.merge(mapOf("a.pots" to null, "b.files" to emptyMap(), "b.boxes" to boxes), null, first)
        assertEquals(setOf("pot", "box"), next.details.keys)
        assertEquals(mapOf("pot" to "b@0,0,1,1"), next.covers)
    }

    @Test
    fun a_part_that_never_read_says_nothing() {
        assertEquals(emptyMap(), RowDetails.merge(mapOf("a.pots" to null), null, RowDetails.Read.EMPTY).details)
    }
}
