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
import kotlin.test.assertTrue

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

    @Test
    fun aWireFieldsRawValueTravelsWithItsTextBesideIt() {
        // a moment (epoch ms) and a size as the host reads them, and the raw value beside them for whoever still reads it
        val family = SettingsFamily("f", 1_763_640_000_000, null, true, null, false)
        val en = wireIn("en").encodeToString(SettingsFamily.serializer(), family)
        assertTrue(en.contains("\"createdAt\":1763640000000"), en)
        assertTrue(Regex("\"createdShown\":\"Nov 20, 2025, \\d{1,2}:\\d{2} [AP]M\"").containsMatchIn(en), en)
        assertTrue(en.contains("\"lastUsedShown\":null"), en)
        val row = TrashShown.Row("n", "memory", "Rome", "2026-11-10T12:00:00Z", "2026-12-10T12:00:00Z", true, false)
        val de = wireIn("de").encodeToString(TrashShown.Row.serializer(), row)
        assertTrue(de.contains("\"deletedDay\":\"10.11.2026\"") && de.contains("\"purgeDay\":\"10.12.2026\""), de)
        // a Memory's date: its first day and its range in the numeric form the web's memoryDateRange writes
        val date = MemoryDateShown(DateMode.RANGE, "2026-03-12", "2026-03-14")
        assertEquals(
            """{"mode":"RANGE","from":"2026-03-12","to":"2026-03-14","fromShown":"12.03.2026","range":"12.03.2026 – 14.03.2026"}""",
            wireIn("de").encodeToString(MemoryDateShown.serializer(), date),
        )
        assertEquals(
            """{"mode":"SINGLE","from":"2026-03-12","to":null,"fromShown":"03/12/2026","range":"03/12/2026"}""",
            wireIn("en").encodeToString(MemoryDateShown.serializer(), MemoryDateShown(DateMode.SINGLE, "2026-03-12", null)),
        )
        // a track's distance in words with its number written; the clocks of what plays
        val now = NowPlayingShown("t", "Song", null, null, true, 61_500, 3_725_000)
        val played = wireIn("en").encodeToString(NowPlayingShown.serializer(), now)
        assertTrue(played.contains("\"position\":\"1:02\"") && played.contains("\"duration\":\"1:02:05\""), played)
    }

    @Test
    fun theHostReadsTheCoresTableOfKindsAndItsSeparator() {
        val lifetimes = Answer.Lifetimes(1, 2, 3, 4)
        assertEquals(NoticeKind.SAID, lifetimes.kinds["link_kept"])
        assertEquals(NoticeKind.UNDO, lifetimes.kinds["trash_moved"])
        assertEquals(null, lifetimes.kinds["share_failed"], "a word not in the table is held")
    }
}
