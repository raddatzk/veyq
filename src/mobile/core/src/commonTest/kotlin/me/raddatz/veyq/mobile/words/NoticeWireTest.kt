package me.raddatz.veyq.mobile.words

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A notice on the wire (ADR 0123's Consequences, the bridge): its key, its kind and each argument by its kind — a size, a
 * moment or an amount written as its text in the wire's language first (ADR 0154 decision 5), the rest read back the same.
 */
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
        assertEquals(Shown.args(n, "en"), json.decodeFromString(NoticeSerializer, written))
        assertEquals(listOf("Anna", 3, 4L, "9 B", Shown.moment(1_700_000_000_000, "en"), "€12.34"), json.decodeFromString(NoticeSerializer, written).args.take(6))
        val german = Json { serializersModule = WireWords.module("de") }
        assertEquals("12,34\u00A0€", german.decodeFromString(NoticeSerializer, german.encodeToString(NoticeSerializer, n)).args[5])
        assertEquals(NoticeKind.UNDO, json.decodeFromString(NoticeSerializer, json.encodeToString(NoticeSerializer, n.living(NoticeKind.UNDO))).kind)
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
    fun aPieceIsWordsOrText() {
        val pieces = listOf(Piece.Said(Notice(Msg.CAT_FOOD)), Piece.Plain("Anna"), Piece.Day("2026-11-10"), Piece.Size(2048), Piece.Money(4500, "CHF", 2))
        val written = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Piece.serializer()), pieces)
        assertEquals(pieces.map { Shown.piece(it, "en") }, json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Piece.serializer()), written))
        assertEquals(listOf("said", "plain", "plain", "plain", "plain"), Regex(""""type":"(\w+)"""").findAll(written).map { it.groupValues[1] }.toList())
        assertEquals(listOf(Piece.Plain("Nov 10, 2026"), Piece.Plain("2 kB"), Piece.Plain("CHF\u00A045.00")), pieces.drop(2).map { Shown.piece(it, "en") })
    }
}
