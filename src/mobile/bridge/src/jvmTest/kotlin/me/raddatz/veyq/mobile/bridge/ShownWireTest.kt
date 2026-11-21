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

    private fun field(json: String, name: String): String? = Regex("\"$name\":(null|\"[^\"]*\")").find(json)?.groupValues?.get(1)?.removeSurrounding("\"")?.takeIf { it != "null" }

    /**
     * The values a view carries raw for the host's own use arrive beside the text the core writes for them (ADR 0154
     * decision 5): a size, a moment, a day, a server instant's day, a Memory's days, an
     * amount and a player's clock, in the host's language — none for a host to format.
     */
    @Test
    fun aViewsRawValuesArriveWithTheirTextInTheHostsLanguage() {
        val en = wireIn("en")
        val de = wireIn("de")
        val detail = DetailRow(Msg.COPY_DONE, null, 1_500_000, null, null)
        assertEquals("1.5 MB", field(en.encodeToString(DetailRow.serializer(), detail), "sizeText"))
        assertEquals("1,5 MB", field(de.encodeToString(DetailRow.serializer(), detail), "sizeText"))
        assertEquals(null, field(en.encodeToString(DetailRow.serializer(), detail), "momentText"))
        val link = ShareShown.ShownLink("s", Piece.Plain("t"), emptyList(), "2026-11-20T12:00:00Z", null, null, false, false, false, false, false, false)
        assertEquals("Nov 20, 2026", field(en.encodeToString(ShareShown.ShownLink.serializer(), link), "createdText"))
        assertEquals("20.11.2026", field(de.encodeToString(ShareShown.ShownLink.serializer(), link), "createdText"))
        val range = MemoryDateShown(DateMode.RANGE, "2026-03-12", "2026-03-14")
        assertEquals("03/12/2026 – 03/14/2026", field(en.encodeToString(MemoryDateShown.serializer(), range), "text"))
        assertEquals("12.03.2026", field(de.encodeToString(MemoryDateShown.serializer(), MemoryDateShown(DateMode.ONGOING, "2026-03-12", null)), "text"))
        assertEquals("", field(en.encodeToString(MemoryDateShown.serializer(), MemoryDateShown(DateMode.AUTO, null, null)), "text"))
        val now = NowPlayingShown("t", "Song", null, null, true, 59_600, 3_725_000)
        val clocks = en.encodeToString(NowPlayingShown.serializer(), now)
        assertEquals("1:00" to "1:02:05", field(clocks, "positionText") to field(clocks, "durationText"))
        val chip = ChipShown("day:2026-11-20", me.raddatz.veyq.mobile.words.ShownText.day("2026-11-20"))
        assertEquals("""{"chip":"day:2026-11-20","label":"20.11.2026","rest":null}""", de.encodeToString(ChipShown.serializer(), chip))
        val total = me.raddatz.veyq.mobile.words.ShownText.money(-123_456, "EUR", 2)
        assertEquals("-€1,234.56", en.encodeToString(me.raddatz.veyq.mobile.words.ShownText.serializer(), total).removeSurrounding("\""))
    }

    /** What a host draws its own notices and lines by: the lifetimes, the words that live as `SAID` or `UNDO`, the separator. */
    @Test
    fun theLifetimesNameTheKindsOfWordsAndTheSeparator() {
        val l = noticeLifetimes()
        assertEquals(listOf(4_000L, 6_000L, 2_000L, 5_000L), listOf(l.saidMs, l.undoMs, l.copiedMs, l.nothingPlaysMs))
        assertEquals(" · ", l.separator)
        assert("profile_saved" in l.saidWords && "download_done" in l.saidWords && "trash_moved" in l.undoWords) { l.toString() }
        assert("download_failed" !in l.saidWords && "download_failed" !in l.undoWords)
    }

    /** The banner's too-large bound named as the web's BannerDialog names it ("40 MB"), in the host's language. */
    @Test
    fun theBannersBoundIsTheWebs() {
        val shown = BannerEditorShown(false, false, null, false, null, BannerSaid.TOO_LARGE, false, false, false)
        kotlin.test.assertTrue(wireIn("de").encodeToString(BannerEditorShown.serializer(), shown).endsWith(""""tooLargeBound":"40 MB"}"""))
    }
}
