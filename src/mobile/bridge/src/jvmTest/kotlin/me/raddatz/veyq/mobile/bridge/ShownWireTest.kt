package me.raddatz.veyq.mobile.bridge

import me.raddatz.veyq.mobile.words.Amount
import me.raddatz.veyq.mobile.words.Bytes
import me.raddatz.veyq.mobile.words.Joined
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import me.raddatz.veyq.mobile.words.NoticeKind
import me.raddatz.veyq.mobile.words.NoticeSerializer
import me.raddatz.veyq.mobile.words.Piece
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a host reads of a notice and a line (ADR 0154 decisions 4 and 5): its kind, its words and strings — every size,
 * moment and amount written by the core in the host's language ([wireIn], `Config.language`), none left to format.
 */
class ShownWireTest {
    private val NB = '\u00A0'

    @Test
    fun aNoticesValuesAreWrittenInTheHostsLanguageAndItsKindTravels() {
        val n = Notice(Msg.COPY_NO_ROOM, listOf(Joined(listOf(Bytes(1_500_000), Amount(123_456, "EUR", 2)), " / ")), NoticeKind.HELD)
        assertEquals(
            """{"msg":"copy_no_room","args":[{"type":"joined","parts":[{"type":"text","text":"1,5 MB"},{"type":"text","text":"1.234,56${NB}€"}],"separator":" / "}],"kind":"HELD"}""",
            wireIn("de").encodeToString(NoticeSerializer, n),
        )
        assertEquals(""""1.5 MB"""", Regex("\"text\":(\"[^\"]*\")").find(wireIn("en").encodeToString(NoticeSerializer, n))!!.groupValues[1])
        assertEquals("SAID", Regex("\"kind\":\"(\\w+)\"").find(wireIn("en").encodeToString(NoticeSerializer, Notice(Msg.COPY_DONE, "a", "b")))!!.groupValues[1])
    }

    @Test
    fun aLinesDaysSizesAndAmountsArriveAsText() {
        val line = listOf(Piece.Day("2026-11-20"), Piece.Size(2048), Piece.Money(-5, "USD", 2), Piece.Plain("Anna"))
        assertEquals(
            """[{"type":"plain","text":"Nov 20, 2026"},{"type":"plain","text":"2 kB"},{"type":"plain","text":"-$0.05"},{"type":"plain","text":"Anna"}]""",
            wireIn("en").encodeToString(ListSerializer(Piece.serializer()), line),
        )
        assertEquals(
            """[{"type":"plain","text":"20.11.2026"},{"type":"plain","text":"2 kB"},{"type":"plain","text":"-0,05${NB}$"},{"type":"plain","text":"Anna"}]""",
            wireIn("de").encodeToString(ListSerializer(Piece.serializer()), line),
        )
    }
}
