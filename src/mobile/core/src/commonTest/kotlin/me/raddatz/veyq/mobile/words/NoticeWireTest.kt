package me.raddatz.veyq.mobile.words

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A notice on the wire (CR core-as-a-native-library D6): its key and each argument by its kind, read back the same. */
class NoticeWireTest {
    private val json = Json

    @Test
    fun everyKindOfArgumentReadsBackAsItWas() {
        val n = Notice(
            Msg.POT_EXPENSE_BY,
            listOf("Anna", 3, 4L, Bytes(9), Moment(1_700_000_000_000), Amount(1234, "EUR", 2), Notice(Msg.POT_DELETED_PERSON),
                Joined(listOf("Ben", Notice(Msg.POT_DELETED_PERSON)), ", ")),
        )
        val written = json.encodeToString(NoticeSerializer, n)
        assertEquals(n, json.decodeFromString(NoticeSerializer, written))
        // the key is the catalog's, the one every platform's words are generated under
        assertEquals("pot_expense_by", Msg.POT_EXPENSE_BY.key)
        assertEquals(true, written.startsWith("""{"msg":"pot_expense_by","""))
    }

    @Test
    fun anArgumentOfAnotherKindIsRefusedNotWrittenAsText() {
        assertFailsWith<SerializationException> { json.encodeToString(NoticeSerializer, Notice(Msg.POT_PAYS, listOf(1.5, "x"))) }
        assertFailsWith<SerializationException> { json.decodeFromString(NoticeSerializer, """{"msg":"no_such_word","args":[]}""") }
    }

    @Test
    fun aPieceIsTaggedByItsKind() {
        val pieces = listOf(Piece.Said(Notice(Msg.CAT_FOOD)), Piece.Plain("Anna"), Piece.Day("2026-11-10"), Piece.Size(2048), Piece.Money(4500, "CHF", 2))
        val written = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Piece.serializer()), pieces)
        assertEquals(pieces, json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Piece.serializer()), written))
        assertEquals(listOf("said", "plain", "day", "size", "money"), Regex(""""type":"(\w+)"""").findAll(written).map { it.groupValues[1] }.toList())
    }
}
