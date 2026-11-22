package me.raddatz.veyq.mobile.memories.tracks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.raddatz.veyq.mobile.blob.RangeReader
import me.raddatz.veyq.mobile.blob.UploadSource
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.MediaDetails
import me.raddatz.veyq.mobile.memories.MemoryMedia
import me.raddatz.veyq.mobile.memories.music.TrackLabel
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.NodeRef
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.words.Msg
import me.raddatz.veyq.mobile.words.Notice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Memory's Map view's tracks (the web's `track/useMemoryTracks.test.ts`; ADR 0143 D8) over a gallery the test plays:
 * nothing read before the view shows, then every track one at a time; one that does not read listed as failed by its
 * file name; named by the member's name else its file's; ordered by capture time and coloured by that order; hidden,
 * shown and selected on this view only — and the add with its labels asked first, the 51st refused.
 */
class TrackListTest {
    private val memory = NodeAt("s", "m", "MEMORY", "Alps 2026")

    private fun gpx(name: String?, vararg points: Pair<Double, Double>) =
        "<gpx><trk>${name?.let { "<name>$it</name>" } ?: ""}<trkseg>" +
            points.joinToString("") { (a, b) -> """<trkpt lat="$a" lon="$b"/>""" } + "</trkseg></trk></gpx>"

    private fun track(id: String, at: Long, fileName: String, own: String? = null, activity: String? = null) = MemoryMedia.Item(
        "s", "m", id, MemoryMedia.Blob("o-$id", 1, 100, "", null), null, null,
        MemoryMedia.Meta(null, null, at, null, "file", null, null, null, null, trackName = own, trackActivity = activity),
        Gpx.MEDIA_TYPE, fileName, "user:u1", false, ByteArray(0),
    )

    private class Door(var items: List<MemoryMedia.Item>, val files: Map<String, String>) : GalleryModel.Door {
        override val revisions = MutableStateFlow<Any?>(Any())
        override val uploaded = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val reads = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun gallery() = MemoryMedia.Gallery(items, 1, 0)
        override suspend fun scopeNodes() = emptyList<NodeRef>()
        override suspend fun read(item: MemoryMedia.Item, blob: MemoryMedia.Blob): ByteArray? {
            reads += item.mediaId
            gate?.await()
            return files[item.mediaId]?.encodeToByteArray()
        }
        override fun reader(item: MemoryMedia.Item): RangeReader? = null
        override suspend fun stream(item: MemoryMedia.Item, sink: suspend (ByteArray) -> Unit) = false
        override suspend fun exif(item: MemoryMedia.Item): MediaDetails.Exif? = null
        override suspend fun uploaders(items: List<MemoryMedia.Item>) = emptyMap<String, MediaDetails.Uploader>()
        override fun upload(sources: List<UploadSource>) = 0
    }

    private class Played : TrackList.Door {
        val queued = mutableListOf<Pair<String, TrackLabel.Label>>()
        val left = mutableListOf<String>()
        override suspend fun check(source: UploadSource, held: Int) = TrackUpload.check(source, held)
        override fun queue(tracks: List<Pair<TrackUpload.Check.Taken, TrackLabel.Label>>): Int {
            tracks.forEach { (t, l) -> queued += t.source.name to l }
            return tracks.size
        }
        override fun left(source: UploadSource) {
            left += source.name
        }
        override suspend fun relabel(item: MemoryMedia.Item, label: TrackLabel.Label) = TrackLabel.Outcome.Done(label.name)
        override suspend fun report(item: MemoryMedia.Item, category: String, reason: String, contact: String) = NodeReport.Sent(NodeReport.Outcome.SENT, null)
        override suspend fun reportOffered() = true
        override val origin = "https://family.example"
    }

    private fun TestScope.open(door: Door, played: Played = Played()): TrackList {
        val gallery = GalleryModel(memory, door, backgroundScope, backgroundScope)
        runCurrent()
        return TrackList(gallery, played, backgroundScope).also { runCurrent() }
    }

    private val a = gpx("Morning", 1.0 to 1.0, 1.0 to 1.01)
    private val b = gpx(null, 2.0 to 2.0, 2.0 to 2.01, 2.0 to 2.02)

    @Test
    fun nothingIsReadBeforeTheViewShowsThenEveryTrackOneAtATime() = runTest {
        val door = Door(listOf(track("t1", 10, "a.gpx"), track("t2", 20, "Ridge walk.gpx")), mapOf("t1" to a, "t2" to b))
        val list = open(door)
        assertEquals(emptyList(), door.reads, "nothing before the view shows")
        assertEquals(listOf(TrackList.Status.LOADING, TrackList.Status.LOADING), list.current.entries!!.map { it.status })
        door.gate = CompletableDeferred()
        list.active(true)
        runCurrent()
        assertEquals(listOf("t1"), door.reads, "one at a time: the second waits for the first")
        door.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("t1", "t2"), door.reads)
        val (one, two) = list.current.entries!!
        assertEquals(TrackList.Status.READY, one.status)
        assertEquals("Morning", one.name, "the file's own track name")
        assertEquals("Ridge walk", two.name, "else its file name without .gpx")
        assertEquals(2, two.lines.single().size.minus(1))
        assertTrue(one.facts.first() is TrackFacts.Fact.Distance)
        assertEquals(2, list.current.drawn.size)
    }

    @Test
    fun aTrackThatDoesNotReadIsListedAsFailedByItsFileNameAndTheOthersRead() = runTest {
        val door = Door(listOf(track("bad", 10, "Broken.gpx"), track("ok", 20, "ok.gpx")), mapOf("bad" to "<kml/>", "ok" to a))
        val list = open(door)
        list.active(true)
        runCurrent()
        val (bad, ok) = list.current.entries!!
        assertEquals(TrackList.Status.FAILED to "Broken", bad.status to bad.name)
        assertEquals(TrackList.Status.READY, ok.status)
    }

    @Test
    fun aTrackIsNamedByTheMembersNameElseItsFileAndCarriesItsActivity() = runTest {
        val door = Door(listOf(track("t1", 10, "a.gpx", own = "Summit day", activity = "cycling"), track("t2", 20, "b.gpx", activity = "paragliding")), mapOf("t1" to a, "t2" to b))
        val list = open(door)
        list.active(true)
        runCurrent()
        val (one, two) = list.current.entries!!
        assertEquals(Triple("Summit day", "Morning", "cycling"), Triple(one.name, one.fileTrackName, one.activity))
        assertEquals(listOf(10, 7), one.dash)
        assertNull(two.activity, "a newer client's activity reads as none")
        assertNull(two.dash)
    }

    @Test
    fun theOrderIsTheListedCaptureTimeAndTheColoursFollowItStable() = runTest {
        val door = Door(listOf(track("late", 30, "c.gpx"), track("early", 10, "a.gpx"), track("mid", 20, "b.gpx")), mapOf())
        val list = open(door)
        assertEquals(listOf("early", "mid", "late"), list.current.entries!!.map { it.mediaId })
        assertEquals(listOf(Gpx.color(0), Gpx.color(1), Gpx.color(2)), list.current.entries!!.map { it.color })
        assertEquals(listOf(1, 2, 3), list.current.entries!!.map { it.number })
    }

    @Test
    fun hidingShowingAndSelectingAreThisViewsOnlyAndSelectingAHiddenTrackShowsIt() = runTest {
        val door = Door(listOf(track("t1", 10, "a.gpx"), track("t2", 20, "b.gpx")), mapOf("t1" to a, "t2" to b))
        val list = open(door)
        list.active(true)
        runCurrent()
        list.setShown("t1", false)
        assertEquals(listOf("t2"), list.current.drawn.map { it.mediaId })
        list.select("t1")
        assertEquals("t1", list.current.selected)
        assertEquals(2, list.current.drawn.size, "selecting a hidden track shows it")
        list.setShown("t1", false)
        assertNull(list.current.selected, "a hidden track is no longer selected")
    }

    private fun picked(name: String, text: String) = UploadSource.of(name, text.length.toLong(), null, null) { from, length ->
        text.encodeToByteArray().copyOfRange(from.toInt(), (from + length).toInt())
    }

    @Test
    fun anAddedFileIsCheckedItsLabelAskedFirstAndCancelUploadsNothing() = runTest {
        val played = Played()
        val list = open(Door(emptyList(), mapOf()), played)
        val said = list.add(listOf(picked("Ride.gpx", a), picked("notes.gpx", "<html/>")))
        assertEquals(listOf(Notice(Msg.TRACK_PROBLEM_INVALID, "notes.gpx")), said)
        assertEquals(listOf("notes.gpx"), played.left, "a refused file is handed back at once")
        assertEquals(listOf("Ride.gpx"), list.current.asking.map { it.fileName })
        assertEquals("Morning", list.current.asking.single().fileTrackName)
        assertEquals(emptyList(), played.queued, "nothing is uploaded before its label is given")
        list.cancel()
        assertEquals(emptyList(), played.queued)
        assertEquals(listOf("notes.gpx", "Ride.gpx"), played.left)
        list.add(listOf(picked("Ride.gpx", a)))
        assertEquals(1, list.confirm(listOf(TrackLabel.Label("Ride home", "cycling"))))
        assertEquals(listOf("Ride.gpx" to TrackLabel.Label("Ride home", "cycling")), played.queued)
    }

    /** The bound case: a node holding 50 tracks takes no 51st. */
    @Test
    fun aNodeHoldingFiftyTracksTakesNoFiftyFirst() = runTest {
        val played = Played()
        val full = (1..Gpx.PER_MEMORY).map { track("t$it", it.toLong(), "t$it.gpx") }
        val list = open(Door(full, mapOf()), played)
        assertEquals(listOf(Notice(Msg.TRACK_PROBLEM_TOO_MANY, "Ride.gpx")), list.add(listOf(picked("Ride.gpx", a))))
        assertEquals(emptyList(), list.current.asking)
        // one short of full: the first is taken, the second of the same pick is the 51st
        val room = open(Door(full.drop(1), mapOf()), played)
        assertEquals(listOf(Notice(Msg.TRACK_PROBLEM_TOO_MANY, "Two.gpx")), room.add(listOf(picked("One.gpx", a), picked("Two.gpx", a))))
        assertEquals(listOf("One.gpx"), room.current.asking.map { it.fileName })
        assertNotNull(room.current.entries)
    }
}
