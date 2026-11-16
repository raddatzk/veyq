package me.raddatz.veyq.mobile.memories.tracks

import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.memories.GalleryMedium
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A GPX track read on the device (ADR 0130 decisions 1, 2 and 4; ADR 0143 D8): the web's `track/gpx.test.ts` case for case
 * over the core's own XML reader — what counts as a track, how its file is read (a document type refused before anything is
 * read), the numbers beside it, what is drawn, and the check before an upload with its bounds.
 */
class GpxTest {
    private fun gpx(body: String, ns: String = "http://www.topografix.com/GPX/1/1") =
        """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="test" xmlns="$ns">$body</gpx>"""

    private fun pt(lat: Number, lon: Number, extra: String = "", tag: String = "trkpt") = """<$tag lat="$lat" lon="$lon">$extra</$tag>"""

    private fun ms(iso: String) = kotlin.time.Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun aTrackIsOneByTheGpxTypeOrByAGpxNameUnderAGenericType() {
        assertTrue(GalleryMedium.isTrack(Gpx.MEDIA_TYPE, null))
        assertTrue(GalleryMedium.isTrack("APPLICATION/GPX+XML", null))
        assertTrue(GalleryMedium.isTrack("application/xml", "Ride.GPX"))
        assertTrue(GalleryMedium.isTrack("application/octet-stream", "ride.gpx"))
        assertTrue(GalleryMedium.isTrack(null, "ride.gpx"))
        assertFalse(GalleryMedium.isTrack(null, null))
        assertFalse(GalleryMedium.isTrack("image/jpeg", "IMG_1.jpg"))
        assertFalse(GalleryMedium.isTrack("image/jpeg", "trick.gpx"))
        assertFalse(GalleryMedium.isTrack("application/xml", "feed.xml"))
    }

    @Test
    fun aPickedFileIsTakenByItsGpxTypeOrItsExtension() {
        assertTrue(Gpx.isGpxFile("a.gpx", ""))
        assertTrue(Gpx.isGpxFile("a", Gpx.MEDIA_TYPE))
        assertFalse(Gpx.isGpxFile("a.xml", "application/xml"))
    }

    @Test
    fun eachTrackSegmentIsALineWithElevationAndTime() {
        val parsed = Gpx.parse(gpx(
            "<metadata><name>Meta name</name></metadata><trk><name>Morning ride</name>" +
                "<trkseg>${pt(48.1, 11.5, "<ele>520</ele><time>2026-06-01T08:00:00Z</time>")}${pt(48.2, 11.6)}</trkseg>" +
                "<trkseg>${pt(48.3, 11.7)}</trkseg></trk>",
        ))
        assertEquals("Morning ride", parsed.name)
        assertEquals(2, parsed.segments.size)
        assertEquals(Gpx.Point(48.1, 11.5, 520.0, ms("2026-06-01T08:00:00Z")), parsed.segments[0][0])
        assertEquals(Gpx.Point(48.2, 11.6), parsed.segments[0][1])
        assertEquals(listOf(Gpx.Point(48.3, 11.7)), parsed.segments[1])
    }

    @Test
    fun gpx10AndAFileWithoutANamespaceReadAlike() {
        assertEquals(2, Gpx.parse(gpx("<trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk>", "http://www.topografix.com/GPX/1/0")).segments[0].size)
        assertEquals(2, Gpx.parse("<gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>").segments[0].size)
        // a prefixed one too: an element is known by its local name
        assertEquals(2, Gpx.parse("<g:gpx xmlns:g=\"x\"><g:trk><g:trkseg>${pt(1, 2, tag = "g:trkpt")}${pt(3, 4, tag = "g:trkpt")}</g:trkseg></g:trk></g:gpx>").segments[0].size)
    }

    @Test
    fun theNameFallsBackToTheMetadataThenTheRouteThenNone() {
        val seg = "<trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg>"
        assertEquals("From metadata", Gpx.parse(gpx("<metadata><name>From metadata</name></metadata><trk>$seg</trk>")).name)
        assertNull(Gpx.parse(gpx("<trk>$seg</trk>")).name)
        // text with the predefined entities, a character reference and CDATA reads as written
        assertEquals("Rock & Roll ✓ <x>", Gpx.parse(gpx("<trk><name>Rock &amp; Roll &#x2713; <![CDATA[<x>]]></name>$seg</trk>")).name)
    }

    @Test
    fun theRoutesAreReadWhenThereAreNoTrackPointsAndNeverTheWaypoints() {
        val parsed = Gpx.parse(gpx("""<wpt lat="9" lon="9"/><rte><name>Planned</name>${pt(1, 2, "", "rtept")}${pt(3, 4, "", "rtept")}</rte>"""))
        assertEquals("Planned", parsed.name)
        assertEquals(listOf(listOf(Gpx.Point(1.0, 2.0), Gpx.Point(3.0, 4.0))), parsed.segments)
    }

    @Test
    fun aPointWithoutOrOutOfItsCoordinatesAndAnImpossibleElevationAreSkipped() {
        val parsed = Gpx.parse(gpx(
            """<trk><trkseg>${pt(1, 2)}<trkpt lat="x" lon="2"/>${pt(91, 2)}${pt(1, 181)}<trkpt lon="2"/>${pt(3, 4, "<ele>200000</ele>")}</trkseg></trk>""",
        ))
        assertEquals(listOf(listOf(Gpx.Point(1.0, 2.0), Gpx.Point(3.0, 4.0))), parsed.segments)
    }

    /** The entity case: a document type is refused before anything is read, so no entity is ever declared or expanded. */
    @Test
    fun aDocumentTypeDeclarationIsRefusedBeforeAnythingIsRead() {
        val withEntity = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x "y">]><gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>"""
        assertEquals(Gpx.Problem.INVALID, assertFailsWith<Gpx.Refused> { Gpx.parse(withEntity) }.problem)
        assertFailsWith<Xml.Malformed> { Xml.parse(withEntity) }
        val bare = """<?xml version="1.0"?><!doctype gpx><gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>"""
        assertEquals(Gpx.Problem.INVALID, assertFailsWith<Gpx.Refused> { Gpx.parse(bare) }.problem)
        // the control: the same document without it reads
        assertEquals(1, Gpx.parse("""<?xml version="1.0"?><gpx><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></gpx>""").segments.size)
        // and an entity no declaration made is no word of the file's
        assertEquals(Gpx.Problem.INVALID, assertFailsWith<Gpx.Refused> { Gpx.parse(gpx("<trk><name>&x;</name><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk>")) }.problem)
    }

    @Test
    fun anotherRootAndATextThatIsNoXmlAreRefusedAndTooFewPointsAreTooShort() {
        fun problem(text: String) = runCatching { Gpx.parse(text) }.exceptionOrNull()?.let { (it as Gpx.Refused).problem }
        assertEquals(Gpx.Problem.INVALID, problem("<kml><trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk></kml>"))
        assertEquals(Gpx.Problem.INVALID, problem("not xml at all <<<"))
        assertEquals(Gpx.Problem.INVALID, problem("<gpx><trk></gpx>"))
        assertEquals(Gpx.Problem.TOO_SHORT, problem(gpx("<trk><trkseg>${pt(1, 2)}</trkseg></trk>")))
        assertEquals(Gpx.Problem.TOO_SHORT, problem(gpx("")))
        assertEquals(Gpx.Problem.INVALID, assertFailsWith<Gpx.Refused> { Gpx.read(byteArrayOf(0xC3.toByte(), 0x28)) }.problem, "bytes that are no UTF-8")
    }

    @Test
    fun aTrackIsNamedByItsOwnNameThenItsFileNameWithoutGpxThenNone() {
        assertEquals("Ride", Gpx.trackName(" Ride ", "x.gpx"))
        assertEquals("Alps day 2", Gpx.trackName(null, "Alps day 2.GPX"))
        assertEquals("Alps", Gpx.trackName(null, "Alps.gpx"))
        assertNull(Gpx.trackName(null, ".gpx"))
        assertNull(Gpx.trackName(null, null))
    }

    @Test
    fun theDistanceWalksEachSegmentNeverTheGapBetweenTwo() {
        val a = Gpx.Point(0.0, 0.0)
        val b = Gpx.Point(0.0, 0.01)
        val far = Gpx.Point(10.0, 10.0)
        val one = Gpx.distanceMeters(a, b)
        assertTrue(one > 1100 && one < 1120, "$one")
        assertEquals(one, Gpx.stats(listOf(listOf(a, b), listOf(far, far))).distanceMeters, 1e-6)
    }

    @Test
    fun theTimeRunsFromTheFirstToTheLastTimestampAndIsLeftOutWithoutOne() {
        val t0 = ms("2026-06-01T08:00:00Z")
        val stats = Gpx.stats(listOf(listOf(Gpx.Point(0.0, 0.0, time = t0), Gpx.Point(0.0, 0.01), Gpx.Point(0.0, 0.02, time = t0 + 3_600_000))))
        assertEquals(3_600_000L, stats.durationMs)
        assertEquals(t0, stats.startedAt)
        assertNull(Gpx.stats(listOf(listOf(Gpx.Point(0.0, 0.0), Gpx.Point(0.0, 1.0)))).durationMs)
    }

    @Test
    fun aClimbAndADescentCountOnlyOnceTheElevationMovedByThreeMetres() {
        fun at(ele: Double) = Gpx.Point(0.0, 0.0, ele)
        val stats = Gpx.stats(listOf(listOf(at(100.0), at(102.0), at(100.0), at(101.0), at(110.0), at(104.0))))
        assertEquals(10.0, stats.ascentMeters)
        assertEquals(6.0, stats.descentMeters)
        assertNull(Gpx.stats(listOf(listOf(Gpx.Point(0.0, 0.0), Gpx.Point(0.0, 1.0)))).ascentMeters)
    }

    @Test
    fun aShortLineIsKeptWholeAndALongOneDrawnInAtMostMaxPointsItsEndsKept() {
        assertEquals(listOf(1, 2, 3), Gpx.simplifyLine(listOf(1, 2, 3), 5))
        val drawn = Gpx.simplifyLine((0..10_000).toList(), 2000)
        assertEquals(2000, drawn.size)
        assertEquals(0, drawn.first())
        assertEquals(10_000, drawn.last())
        assertEquals(drawn.sorted(), drawn)
    }

    @Test
    fun theColoursCycleByThePlaceInTheList() {
        assertEquals(Gpx.COLORS[0], Gpx.color(0))
        assertEquals(Gpx.COLORS[0], Gpx.color(Gpx.COLORS.size))
        assertTrue(Gpx.color(1) != Gpx.color(0))
        assertEquals(0xFF7C6CF0, Gpx.color(0), "the web's first, #7c6cf0")
    }

    private val good = gpx("<trk><trkseg>${pt(1, 2)}${pt(3, 4)}</trkseg></trk>")

    @Test
    fun aGpxTrackTheNodeHasRoomForIsTaken() = runTest {
        val c = Gpx.check(good.length.toLong(), Gpx.PER_MEMORY - 1) { good.encodeToByteArray() }
        assertEquals(1, assertIs<Gpx.Check.Taken>(c).parsed.segments.size)
    }

    /** The bound case: the 51st track, a file over 25 MiB, no GPX and too few points — each refused by its own reason. */
    @Test
    fun aFullNodeAFileTooLargeNoGpxAndTooFewPointsAreEachRefusedByTheirOwnReason() = runTest {
        var reads = 0
        val read: suspend () -> ByteArray = { reads++; good.encodeToByteArray() }
        assertEquals(Gpx.Check.Refused(Gpx.Problem.TOO_MANY), Gpx.check(good.length.toLong(), Gpx.PER_MEMORY, read), "the 51st track")
        assertEquals(Gpx.Check.Refused(Gpx.Problem.TOO_LARGE), Gpx.check(Gpx.MAX_BYTES + 1, 0, read))
        assertEquals(0, reads, "nothing read of a refused file")
        assertIs<Gpx.Check.Taken>(Gpx.check(Gpx.MAX_BYTES, 0, read))
        assertEquals(Gpx.Check.Refused(Gpx.Problem.INVALID), Gpx.check(7, 0) { "<html/>".encodeToByteArray() })
        assertEquals(Gpx.Check.Refused(Gpx.Problem.TOO_SHORT), Gpx.check(7, 0) { gpx("<trk><trkseg>${pt(1, 2)}</trkseg></trk>").encodeToByteArray() })
        assertEquals(Notice(Msg.TRACK_PROBLEM_TOO_MANY, "Alps.gpx"), Gpx.Problem.TOO_MANY.notice("Alps.gpx"), "each refusal names the file")
    }
}
